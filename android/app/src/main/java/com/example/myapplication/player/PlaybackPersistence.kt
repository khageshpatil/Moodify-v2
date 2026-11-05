package com.moodify.app.player

import android.content.Context
import com.moodify.app.data.FavoriteEntity
import com.moodify.app.data.MoodifyDatabase
import com.moodify.app.data.QueueSnapshotEntity
import com.moodify.app.data.Track
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.moodify.app.data.OnceGate

/** A saved favorite. [track] is null only for favorites saved before schema v6. */
data class FavoriteRecord(val trackId: String, val track: Track?)

/**
 * Room-backed persistence boundary for queue, playback options, and favorites.
 *
 * Nothing here blocks the calling thread: reads are suspend functions or
 * Flows, and writes are queued on IO so the Media3 callback thread never
 * waits on SQLite. The one-time legacy import runs lazily before first use.
 */
class PlaybackPersistence(
    context: Context,
    database: MoodifyDatabase = MoodifyDatabase.get(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val dao = database.moodifyDao()
    private val preferences = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    // One writer at a time keeps queue snapshots in order: an older snapshot
    // can never overwrite a newer one.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val legacyImport = OnceGate()

    /** Visible for tests: completes the one-time legacy import. */
    suspend fun ensureImported() = legacyImport.run { migrateLegacySnapshot() }

    suspend fun restorePlaybackState(): PlaybackState? = withContext(Dispatchers.IO) {
        ensureImported()
        val snapshot = dao.queue() ?: return@withContext null
        val queue = runCatching {
            gson.fromJson<List<Track>>(snapshot.queueJson, object : TypeToken<List<Track>>() {}.type)
        }.getOrNull()?.filter { it.id.isNotBlank() }.orEmpty()
        if (queue.isEmpty()) return@withContext null

        val currentIndex = snapshot.currentIndex.coerceIn(0, queue.lastIndex)
        PlaybackState(
            currentTrack = queue[currentIndex],
            isPlaying = false,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            durationMs = snapshot.durationMs.takeIf { it > 0L }
                ?: queue[currentIndex].durationSeconds * 1_000L,
            queue = queue,
            currentIndex = currentIndex,
            isShuffle = snapshot.shuffle,
            isRepeat = snapshot.repeat,
            isLoading = false,
            errorMessage = null,
            playbackSpeed = snapshot.playbackSpeed,
            pitch = snapshot.pitch,
        )
    }

    fun savePlaybackState(state: PlaybackState) {
        if (state.queue.isEmpty()) return
        val snapshot = QueueSnapshotEntity(
            queueJson = gson.toJson(state.queue),
            currentIndex = state.currentIndex.coerceIn(0, state.queue.lastIndex),
            positionMs = state.positionMs.coerceAtLeast(0L),
            shuffle = state.isShuffle,
            repeat = state.isRepeat,
            playbackSpeed = state.playbackSpeed,
            pitch = state.pitch,
            durationMs = state.durationMs.coerceAtLeast(0L),
            updatedAtMs = System.currentTimeMillis(),
        )
        ioScope.launch {
            ensureImported()
            dao.saveQueue(snapshot)
        }
    }

    suspend fun loadFavoriteIds(): Set<String> = withContext(Dispatchers.IO) {
        ensureImported()
        dao.favoriteIds().toSet()
    }

    /** Favorites, newest first, re-emitted on every change. */
    fun observeFavorites(): Flow<List<FavoriteRecord>> = flow {
        ensureImported()
        emitAll(
            dao.observeFavorites().map { rows ->
                rows.map { row ->
                    FavoriteRecord(
                        trackId = row.trackId,
                        track = row.trackJson.takeIf(String::isNotBlank)?.let { json ->
                            runCatching { gson.fromJson(json, Track::class.java) }.getOrNull()
                        },
                    )
                }
            }
        )
    }.flowOn(Dispatchers.IO)

    suspend fun setFavorite(track: Track, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        ensureImported()
        if (isFavorite) {
            dao.upsertFavorite(
                FavoriteEntity(
                    trackId = track.id,
                    trackJson = gson.toJson(track.copy(isFavorite = false)),
                    addedAtMs = System.currentTimeMillis(),
                ),
            )
        } else {
            dao.deleteFavorite(track.id)
        }
    }

    fun clearPlaybackState() {
        ioScope.launch {
            ensureImported()
            dao.clearQueue()
        }
    }

    private suspend fun migrateLegacySnapshot() {
        withContext(Dispatchers.IO) {
            val queueJson = preferences.getString(KEY_QUEUE, null)
            val parsedQueue = if (queueJson.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    gson.fromJson<List<Track>>(
                        queueJson,
                        object : TypeToken<List<Track>>() {}.type,
                    ) ?: throw JsonParseException("Legacy queue is not a JSON array")
                }.getOrNull()
            }
            val queueImportComplete = queueJson.isNullOrBlank() || parsedQueue != null
            if (queueImportComplete && dao.queue() == null) {
                val queue = parsedQueue.orEmpty().filter { it.id.isNotBlank() }
                if (queue.isNotEmpty()) {
                    dao.saveQueue(
                        QueueSnapshotEntity(
                            queueJson = gson.toJson(queue),
                            currentIndex = preferences.getInt(KEY_CURRENT_INDEX, 0)
                                .coerceIn(0, queue.lastIndex),
                            positionMs = preferences.getLong(KEY_POSITION_MS, 0L),
                            shuffle = preferences.getBoolean(KEY_SHUFFLE, false),
                            repeat = preferences.getBoolean(KEY_REPEAT, false),
                            playbackSpeed = preferences.getFloat(KEY_SPEED, 1f),
                            pitch = preferences.getFloat(KEY_PITCH, 1f),
                            durationMs = 0L,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            val hasLegacyFavorites = preferences.contains(KEY_FAVORITES)
            val favoritesJson = preferences.getString(KEY_FAVORITES, null)
            val parsedFavorites = if (!hasLegacyFavorites) {
                emptySet()
            } else if (favoritesJson.isNullOrBlank()) {
                emptySet()
            } else {
                runCatching {
                    gson.fromJson<Set<String>>(
                        favoritesJson,
                        object : TypeToken<Set<String>>() {}.type,
                    ) ?: throw JsonParseException("Legacy favorites are not a JSON set")
                }.getOrNull()
            }
            val favoritesImportComplete = !hasLegacyFavorites || parsedFavorites != null
            val existingFavorites = dao.favoriteIds().toSet()
            val missingFavorites = parsedFavorites.orEmpty() - existingFavorites
            if (missingFavorites.isNotEmpty()) {
                dao.upsertFavorites(missingFavorites.map { FavoriteEntity(trackId = it) })
            }
            preferences.edit().apply {
                if (queueImportComplete) {
                    remove(KEY_QUEUE)
                    remove(KEY_CURRENT_INDEX)
                    remove(KEY_POSITION_MS)
                    remove(KEY_SHUFFLE)
                    remove(KEY_REPEAT)
                    remove(KEY_SPEED)
                    remove(KEY_PITCH)
                }
                if (favoritesImportComplete) remove(KEY_FAVORITES)
            }.apply()
        }
    }

    private companion object {
        const val FILE_NAME = "moodify_playback"
        const val KEY_QUEUE = "queue"
        const val KEY_CURRENT_INDEX = "current_index"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
        const val KEY_SPEED = "speed"
        const val KEY_PITCH = "pitch"
        const val KEY_FAVORITES = "favorites"
    }
}
