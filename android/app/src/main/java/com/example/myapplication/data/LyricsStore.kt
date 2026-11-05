package com.moodify.app.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Room-backed lyrics cache with a one-time import from the old JSON keys. */
class LyricsStore(
    context: Context,
    database: MoodifyDatabase = MoodifyDatabase.get(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val dao = database.moodifyDao()
    private val preferences = appContext.getSharedPreferences("moodify_lyrics", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val legacyImport = OnceGate()

    /** Visible for tests: completes the one-time legacy import. */
    suspend fun ensureImported() = legacyImport.run {
        migrateLegacySnapshot()
        // Lyrics cached before matching version 2 may belong to another song or
        // carry another recording's timings; drop them once so they are re-fetched.
        if (preferences.getInt(KEY_MATCHING_VERSION, 1) < MATCHING_VERSION) {
            withContext(Dispatchers.IO) { dao.clearLyrics() }
            preferences.edit().putInt(KEY_MATCHING_VERSION, MATCHING_VERSION).apply()
        }
    }

    suspend fun read(trackId: String): LyricsDocument? = withContext(Dispatchers.IO) {
        ensureImported()
        val cached = dao.lyrics(trackId) ?: return@withContext null
        if (System.currentTimeMillis() - cached.savedAtMs > MAX_AGE_MS) {
            dao.deleteLyrics(trackId)
            return@withContext null
        }
        val document = runCatching {
            gson.fromJson(cached.documentJson, LyricsDocument::class.java)
        }.getOrNull()
        if (document?.plainText?.trim()?.equals("probe", ignoreCase = true) == true) {
            dao.deleteLyrics(trackId)
            return@withContext null
        }
        document
    }

    suspend fun write(trackId: String, document: LyricsDocument) = withContext(Dispatchers.IO) {
        ensureImported()
        dao.upsertLyrics(
            LyricsCacheEntity(
                trackId = trackId,
                documentJson = gson.toJson(document),
                savedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun migrateLegacySnapshot() {
        withContext(Dispatchers.IO) {
            val all = preferences.all
            val migratedKeys = mutableListOf<String>()
            all.forEach { (key, value) ->
                if (!key.startsWith("lyrics_") || value !is String) return@forEach
                val cached = runCatching { gson.fromJson(value, CachedLyrics::class.java) }.getOrNull()
                    ?: return@forEach
                val trackId = key.removePrefix("lyrics_")
                val document = runCatching {
                    gson.fromJson(cached.document, LyricsDocument::class.java)
                }.getOrNull() ?: return@forEach
                dao.upsertLyrics(
                    LyricsCacheEntity(
                        trackId = trackId,
                        documentJson = gson.toJson(document),
                        savedAtMs = cached.savedAtMs,
                    ),
                )
                migratedKeys += key
            }
            // Remove only records that were imported successfully. Keeping an
            // unparsed key allows a later app version to retry its migration.
            if (migratedKeys.isNotEmpty()) {
                preferences.edit().apply {
                    migratedKeys.forEach(::remove)
                }.apply()
            }
        }
    }

    private data class CachedLyrics(
        val savedAtMs: Long,
        val document: String,
    )

    private companion object {
        const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
        const val KEY_MATCHING_VERSION = "lyrics_matching_version"
        const val MATCHING_VERSION = 2
    }
}
