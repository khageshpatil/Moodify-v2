package com.moodify.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Device-owned playlist repository.
 *
 * Room is the source of truth and is observed as a [Flow], so the UI updates
 * automatically after every change. The one-time SharedPreferences import runs
 * lazily on IO before the first read or write instead of in the constructor.
 */
class PlaylistStore(
    context: Context,
    database: MoodifyDatabase = MoodifyDatabase.get(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val dao = database.moodifyDao()
    private val preferences = appContext.getSharedPreferences("moodify_library", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val legacyImport = OnceGate()

    /** Every playlist with its tracks, pinned first, re-emitted on any change. */
    fun observe(): Flow<List<LocalPlaylist>> = flow {
        ensureImported()
        emitAll(
            combine(dao.observePlaylists(), dao.observePlaylistTracks()) { playlists, rows ->
                val tracksByPlaylist = rows.groupBy { it.playlistId }
                playlists.map { playlist -> playlist.toModel(tracksByPlaylist[playlist.id].orEmpty()) }
            }
        )
    }.flowOn(Dispatchers.IO)

    suspend fun list(): List<LocalPlaylist> = withContext(Dispatchers.IO) {
        ensureImported()
        dao.playlists().map { playlist -> playlist.toModel(dao.playlistTracks(playlist.id)) }
    }

    suspend fun create(name: String): LocalPlaylist = withContext(Dispatchers.IO) {
        ensureImported()
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(UUID.randomUUID().toString(), name.trim())
        dao.upsertPlaylist(
            LocalPlaylistEntity(
                id = playlist.id,
                name = playlist.name,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
        playlist
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        ensureImported()
        dao.deletePlaylistTracks(id)
        dao.deletePlaylist(id)
    }

    suspend fun addTrack(id: String, track: Track) = withContext(Dispatchers.IO) {
        ensureImported()
        val playlist = dao.playlists().firstOrNull { it.id == id } ?: return@withContext
        val existing = dao.playlistTracks(id)
        if (existing.any { it.trackId == track.id }) return@withContext
        dao.upsertPlaylistTracks(
            listOf(
                PlaylistTrackEntity(
                    playlistId = id,
                    trackId = track.id,
                    trackJson = gson.toJson(track.copy(isFavorite = false)),
                    position = (existing.maxOfOrNull { it.position } ?: -1) + 1,
                ),
            ),
        )
        dao.upsertPlaylist(playlist.copy(updatedAtMs = System.currentTimeMillis()))
    }

    suspend fun removeTrack(id: String, trackId: String) = withContext(Dispatchers.IO) {
        ensureImported()
        dao.deletePlaylistTrack(id, trackId)
        dao.playlists().firstOrNull { it.id == id }?.let {
            dao.upsertPlaylist(it.copy(updatedAtMs = System.currentTimeMillis()))
        }
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        ensureImported()
        dao.playlists().firstOrNull { it.id == id }?.let {
            dao.upsertPlaylist(it.copy(name = name.trim(), updatedAtMs = System.currentTimeMillis()))
        }
    }

    suspend fun setPinned(id: String, isPinned: Boolean) = withContext(Dispatchers.IO) {
        ensureImported()
        dao.setPlaylistPinned(id, isPinned, System.currentTimeMillis())
    }

    private fun LocalPlaylistEntity.toModel(rows: List<PlaylistTrackEntity>) = LocalPlaylist(
        id = id,
        name = name,
        tracks = rows.mapNotNull { row ->
            runCatching { gson.fromJson(row.trackJson, Track::class.java) }.getOrNull()
        },
        isPinned = isPinned,
    )

    /** Visible for tests: completes the one-time legacy import. */
    suspend fun ensureImported() = legacyImport.run { migrateLegacySnapshot() }

    private suspend fun migrateLegacySnapshot() = withContext(Dispatchers.IO) {
        val legacy = runCatching {
            gson.fromJson<List<LocalPlaylist>>(
                preferences.getString(KEY_PLAYLISTS, null),
                object : TypeToken<List<LocalPlaylist>>() {}.type,
            )
        }.getOrNull().orEmpty()
        if (legacy.isEmpty()) return@withContext

        val now = System.currentTimeMillis()
        val playlists = legacy.mapIndexed { index, playlist ->
            LocalPlaylistEntity(
                id = playlist.id,
                name = playlist.name,
                createdAtMs = now + index,
                updatedAtMs = now + index,
            )
        }
        val tracks = legacy.flatMap { playlist ->
            playlist.tracks.mapIndexed { position, track ->
                PlaylistTrackEntity(
                    playlistId = playlist.id,
                    trackId = track.id,
                    trackJson = gson.toJson(track),
                    position = position,
                )
            }
        }
        // One Room transaction plus IGNORE conflicts makes a retry safe:
        // a process death cannot strand only part of the old snapshot or
        // overwrite a row already imported/edited in Room.
        dao.importLegacyPlaylistSnapshot(playlists, tracks)
        preferences.edit().remove(KEY_PLAYLISTS).apply()
    }

    private companion object {
        const val KEY_PLAYLISTS = "playlists"
    }
}
