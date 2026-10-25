package com.example.myapplication.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/** SharedPreferences is sufficient for the compact offline playlist snapshot. */
class PlaylistStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("moodify_library", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun list(): List<LocalPlaylist> = runCatching {
        gson.fromJson<List<LocalPlaylist>>(
            preferences.getString(KEY_PLAYLISTS, null),
            object : TypeToken<List<LocalPlaylist>>() {}.type,
        )
    }.getOrNull().orEmpty()

    fun create(name: String): LocalPlaylist {
        val playlist = LocalPlaylist(UUID.randomUUID().toString(), name.trim())
        save(list() + playlist)
        return playlist
    }

    fun delete(id: String) = save(list().filterNot { it.id == id })

    fun addTrack(id: String, track: Track) {
        save(list().map { playlist ->
            if (playlist.id != id || playlist.tracks.any { it.id == track.id }) playlist
            else playlist.copy(tracks = playlist.tracks + track)
        })
    }

    fun removeTrack(id: String, trackId: String) {
        save(list().map { playlist ->
            if (playlist.id == id) playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId })
            else playlist
        })
    }

    private fun save(playlists: List<LocalPlaylist>) {
        preferences.edit().putString(KEY_PLAYLISTS, gson.toJson(playlists)).apply()
    }

    private companion object {
        const val KEY_PLAYLISTS = "playlists"
    }
}
