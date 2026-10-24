package com.example.myapplication.player

import android.content.Context
import com.example.myapplication.data.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Small, dependency-light persistence boundary for playback state.
 *
 * The player must survive Activity recreation and process restarts without
 * forcing the UI to own queue state.  SharedPreferences is intentional here:
 * this data is a compact snapshot, not a relational library database.
 */
class PlaybackPersistence(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun restorePlaybackState(): PlaybackState? {
        val queueJson = preferences.getString(KEY_QUEUE, null) ?: return null
        val queue: List<Track> = runCatching {
            gson.fromJson<List<Track>>(queueJson, object : TypeToken<List<Track>>() {}.type)
        }.getOrNull()?.filter { it.id.isNotBlank() }.orEmpty()
        if (queue.isEmpty()) return null

        val currentIndex = preferences.getInt(KEY_CURRENT_INDEX, 0)
            .coerceIn(0, queue.lastIndex)
        val position = preferences.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L)
        return PlaybackState(
            currentTrack = queue[currentIndex],
            isPlaying = false,
            positionMs = position,
            durationMs = queue[currentIndex].durationSeconds * 1_000L,
            queue = queue,
            currentIndex = currentIndex,
            isShuffle = preferences.getBoolean(KEY_SHUFFLE, false),
            isRepeat = preferences.getBoolean(KEY_REPEAT, false),
            isLoading = false,
            errorMessage = null,
            playbackSpeed = preferences.getFloat(KEY_SPEED, 1f),
            pitch = preferences.getFloat(KEY_PITCH, 1f)
        )
    }

    fun savePlaybackState(state: PlaybackState) {
        if (state.queue.isEmpty()) return
        preferences.edit()
            .putString(KEY_QUEUE, gson.toJson(state.queue))
            .putInt(KEY_CURRENT_INDEX, state.currentIndex.coerceAtLeast(0))
            .putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, state.isShuffle)
            .putBoolean(KEY_REPEAT, state.isRepeat)
            .putFloat(KEY_SPEED, state.playbackSpeed)
            .putFloat(KEY_PITCH, state.pitch)
            .apply()
    }

    fun loadFavoriteIds(): Set<String> = runCatching {
        gson.fromJson<Set<String>>(
            preferences.getString(KEY_FAVORITES, null),
            object : TypeToken<Set<String>>() {}.type
        )
    }.getOrNull().orEmpty()

    fun saveFavoriteIds(ids: Set<String>) {
        preferences.edit().putString(KEY_FAVORITES, gson.toJson(ids)).apply()
    }

    fun clearPlaybackState() {
        preferences.edit()
            .remove(KEY_QUEUE)
            .remove(KEY_CURRENT_INDEX)
            .remove(KEY_POSITION_MS)
            .apply()
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
