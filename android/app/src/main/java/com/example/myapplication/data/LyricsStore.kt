package com.example.myapplication.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

/** Persistent, bounded lyrics cache so opening the player never depends on a second network call. */
class LyricsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("moodify_lyrics", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(trackId: String): LyricsDocument? {
        val raw = preferences.getString(key(trackId), null) ?: return null
        val cached = runCatching { gson.fromJson<CachedLyrics>(raw, CachedLyrics::class.java) }.getOrNull()
            ?: return null
        if (System.currentTimeMillis() - cached.savedAtMs > MAX_AGE_MS) {
            preferences.edit().remove(key(trackId)).apply()
            return null
        }
        return runCatching { gson.fromJson(cached.document, LyricsDocument::class.java) }.getOrNull()
    }

    fun write(trackId: String, document: LyricsDocument) {
        val cached = CachedLyrics(
            savedAtMs = System.currentTimeMillis(),
            document = gson.toJson(document),
        )
        preferences.edit().putString(key(trackId), gson.toJson(cached)).apply()
    }

    private fun key(trackId: String) = "lyrics_${trackId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

    private data class CachedLyrics(
        val savedAtMs: Long,
        val document: String,
    )

    private companion object {
        const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
