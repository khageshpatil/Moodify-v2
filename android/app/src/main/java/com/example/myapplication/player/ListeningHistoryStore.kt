package com.moodify.app.player

import android.content.Context
import com.moodify.app.data.ListeningHistoryEntity
import com.moodify.app.data.MoodifyDatabase
import com.moodify.app.data.OnceGate
import com.moodify.app.data.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class ListeningHistoryEntry(
    val track: Track,
    val playedAtMs: Long,
)

/** Room-backed, bounded listening history with a lazy one-time legacy import. */
class ListeningHistoryStore(
    context: Context,
    database: MoodifyDatabase = MoodifyDatabase.get(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val dao = database.moodifyDao()
    private val preferences = appContext.getSharedPreferences("moodify_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val legacyImport = OnceGate()

    suspend fun record(track: Track) = withContext(Dispatchers.IO) {
        ensureImported()
        val playedAt = System.currentTimeMillis()
        dao.deleteHistoryForTrack(track.id)
        dao.addHistory(
            ListeningHistoryEntity(
                trackId = track.id,
                trackJson = gson.toJson(track.copy(isFavorite = false)),
                playedAtMs = playedAt,
            ),
        )
        dao.pruneHistory(MAX_ENTRIES)
        dao.recordPlay(track.id, gson.toJson(track.copy(isFavorite = false)), playedAt)
    }

    /** Played at least [minPlays] times but not since [cutoffMs]. */
    suspend fun forgotten(cutoffMs: Long, minPlays: Int, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        ensureImported()
        dao.forgotten(cutoffMs, minPlays, limit).mapNotNull { row ->
            runCatching { gson.fromJson(row.trackJson, Track::class.java) }.getOrNull()
        }
    }

    /** Most-played tracks, most-played first. */
    suspend fun mostPlayed(limit: Int): List<Pair<Track, Int>> = withContext(Dispatchers.IO) {
        ensureImported()
        dao.mostPlayed(limit).mapNotNull { row ->
            runCatching { gson.fromJson(row.trackJson, Track::class.java) to row.playCount }.getOrNull()
        }
    }

    /** Most-recent-first history, re-emitted whenever a play is recorded or history is cleared. */
    fun observe(): Flow<List<ListeningHistoryEntry>> = flow {
        ensureImported()
        emitAll(dao.observeHistory(MAX_ENTRIES).map { rows -> rows.mapNotNull(::toEntry) })
    }.flowOn(Dispatchers.IO)

    suspend fun entries(): List<ListeningHistoryEntry> = withContext(Dispatchers.IO) {
        ensureImported()
        dao.history(MAX_ENTRIES).mapNotNull(::toEntry)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        ensureImported()
        dao.clearHistory()
    }

    private fun toEntry(row: ListeningHistoryEntity): ListeningHistoryEntry? = runCatching {
        ListeningHistoryEntry(
            track = gson.fromJson(row.trackJson, Track::class.java),
            playedAtMs = row.playedAtMs,
        )
    }.getOrNull()

    /** Visible for tests: completes the one-time legacy import. */
    suspend fun ensureImported() = legacyImport.run { migrateLegacySnapshot() }

    private suspend fun migrateLegacySnapshot() = withContext(Dispatchers.IO) {
        val legacy = runCatching {
            gson.fromJson<List<ListeningHistoryEntry>>(
                preferences.getString(KEY_ENTRIES, null),
                object : TypeToken<List<ListeningHistoryEntry>>() {}.type,
            )
        }.getOrNull().orEmpty()
        if (legacy.isEmpty()) return@withContext
        val entries = legacy.asReversed().map { entry ->
            ListeningHistoryEntity(
                trackId = entry.track.id,
                trackJson = gson.toJson(entry.track),
                playedAtMs = entry.playedAtMs,
            )
        }
        // Merge in one transaction. Existing Room history wins; missing
        // legacy rows are imported, so retrying after interruption is safe.
        dao.importLegacyHistory(entries, MAX_ENTRIES)
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 100
    }
}
