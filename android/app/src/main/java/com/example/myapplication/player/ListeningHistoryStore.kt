package com.example.myapplication.player

import android.content.Context
import com.example.myapplication.data.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ListeningHistoryEntry(
    val track: Track,
    val playedAtMs: Long
)

class ListeningHistoryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("moodify_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun record(track: Track) {
        val updated = (listOf(ListeningHistoryEntry(track, System.currentTimeMillis())) + entries())
            .distinctBy { it.track.id }
            .take(MAX_ENTRIES)
        preferences.edit().putString(KEY_ENTRIES, gson.toJson(updated)).apply()
    }

    fun entries(): List<ListeningHistoryEntry> = runCatching {
        gson.fromJson<List<ListeningHistoryEntry>>(
            preferences.getString(KEY_ENTRIES, null),
            object : TypeToken<List<ListeningHistoryEntry>>() {}.type
        )
    }.getOrNull() ?: emptyList()

    fun clear() = preferences.edit().remove(KEY_ENTRIES).apply()

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 100
    }
}
