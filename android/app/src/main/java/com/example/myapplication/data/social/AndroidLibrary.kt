package com.moodify.app.data.social

import com.google.gson.Gson
import com.moodify.app.data.FavoriteEntity
import com.moodify.app.data.ListeningHistoryEntity
import com.moodify.app.data.LocalPlaylistEntity
import com.moodify.app.data.MoodifyDao
import com.moodify.app.data.PlaylistTrackEntity
import com.moodify.app.data.SavedCollectionEntity
import com.moodify.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * This phone's library as sync records (sync.md §3): likes, playlists, saved
 * albums/playlists/artists and recent plays. Room is written directly; the
 * screens observe it, so synced changes appear on their own.
 */
class AndroidLibraryAdapter(private val dao: MoodifyDao, private val gson: Gson = Gson()) : LibraryAdapter {
    override val collections = setOf("like", "playlist", "saved", "recent")
    override val mergeable = setOf("recent")
    override fun merge(c: String, local: Any?, remote: Any?): Any? = SyncWire.mergePlays(local, remote)

    override suspend fun snapshot(): Map<String, Any> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Any>()
        for (f in dao.favorites()) {
            val track = trackFrom(f.trackJson) ?: continue // favorites from before v6 have no details
            out["like:${f.trackId}"] = JSONObject().put("track", SyncWire.track(track))
        }
        val rows = dao.allPlaylistTracks().groupBy { it.playlistId }
        for (p in dao.playlists()) {
            val tracks = rows[p.id].orEmpty().sortedBy { it.position }.mapNotNull { trackFrom(it.trackJson) }
            out["playlist:${p.id}"] = JSONObject()
                .put("name", p.name)
                .put("createdAt", p.createdAtMs)
                .apply { if (p.isPinned) put("pinned", true) }
                .put("tracks", JSONArray(tracks.map(SyncWire::track)))
        }
        for (s in dao.savedCollections()) {
            out["saved:${s.type}:${s.id}"] = JSONObject()
                .put("kind", s.type)
                .put("browseId", s.id)
                .put("title", s.title)
                .apply {
                    if (s.subtitle.isNotBlank()) put("subtitle", s.subtitle)
                    if (s.artworkUrl.isNotBlank()) put("artworkUrl", s.artworkUrl)
                    s.params?.takeIf(String::isNotBlank)?.let { put("params", it) }
                    s.pageType?.takeIf(String::isNotBlank)?.let { put("pageType", it) }
                }
                .put("savedAt", s.savedAtMs)
        }
        val plays = dao.history(SyncWire.RECENT_MAX).mapNotNull { h ->
            trackFrom(h.trackJson)?.let { JSONObject().put("track", SyncWire.track(it)).put("playedAt", h.playedAtMs) }
        }
        if (plays.isNotEmpty()) out["recent:plays"] = JSONObject().put("plays", JSONArray(plays))
        out
    }

    override suspend fun apply(changes: List<SyncChange>) = withContext(Dispatchers.IO) {
        for (ch in changes) runCatching { applyOne(ch) }
    }

    private suspend fun applyOne(ch: SyncChange) {
        val data = ch.data as? JSONObject
        when (ch.c) {
            "like" -> {
                if (data == null) return dao.deleteFavorite(ch.id)
                val track = SyncWire.parseTrack(data.optJSONObject("track")) ?: return
                dao.upsertFavorite(FavoriteEntity(trackId = ch.id, trackJson = gson.toJson(track), addedAtMs = ch.updatedAt))
            }
            "playlist" -> {
                if (data == null) {
                    dao.deletePlaylistTracks(ch.id)
                    dao.deletePlaylist(ch.id)
                    return
                }
                val tracks = data.optJSONArray("tracks")?.let { a -> (0 until a.length()).mapNotNull { SyncWire.parseTrack(a.optJSONObject(it)) } }.orEmpty()
                dao.upsertPlaylist(
                    LocalPlaylistEntity(
                        id = ch.id,
                        name = data.optString("name").ifBlank { "Playlist" }.take(100),
                        createdAtMs = data.optLong("createdAt").takeIf { it > 0 } ?: ch.updatedAt,
                        updatedAtMs = ch.updatedAt,
                        isPinned = data.optBoolean("pinned"),
                    ),
                )
                dao.deletePlaylistTracks(ch.id)
                dao.upsertPlaylistTracks(tracks.distinctBy { it.id }.mapIndexed { i, t -> PlaylistTrackEntity(ch.id, t.id, gson.toJson(t), i) })
            }
            "saved" -> {
                val browseId = ch.id.substringAfter(':')
                if (data == null) return dao.deleteSavedCollection(browseId)
                val kind = data.optString("kind").takeIf { it in setOf("album", "playlist", "artist") } ?: return
                dao.upsertSavedCollection(
                    SavedCollectionEntity(
                        id = browseId,
                        type = kind,
                        title = data.optString("title").take(200),
                        subtitle = data.optString("subtitle").take(200),
                        artworkUrl = data.optString("artworkUrl").takeIf { it.startsWith("https://") }.orEmpty(),
                        params = data.optString("params").takeIf(String::isNotBlank),
                        pageType = data.optString("pageType").takeIf(String::isNotBlank),
                        savedAtMs = data.optLong("savedAt").takeIf { it > 0 } ?: ch.updatedAt,
                    ),
                )
            }
            "recent" -> {
                val plays = data?.optJSONArray("plays") ?: return
                val have = dao.history(500).mapTo(HashSet()) { "${it.trackId}@${it.playedAtMs}" }
                for (i in 0 until plays.length()) {
                    val p = plays.optJSONObject(i) ?: continue
                    val track = SyncWire.parseTrack(p.optJSONObject("track")) ?: continue
                    val at = p.optLong("playedAt")
                    if (at <= 0 || "${track.id}@$at" in have) continue
                    dao.addHistory(ListeningHistoryEntity(trackId = track.id, trackJson = gson.toJson(track), playedAtMs = at))
                }
            }
        }
    }

    private fun trackFrom(json: String): Track? =
        json.takeIf(String::isNotBlank)?.let { runCatching { gson.fromJson(it, Track::class.java) }.getOrNull() }?.takeIf { it.id.isNotBlank() }
}

/** The shared wire format for songs and plays (the same as shares, chat and the web). */
object SyncWire {
    const val RECENT_MAX = 50
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    fun track(t: Track): JSONObject = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("artist", t.artist)
        .apply {
            if (t.album.isNotBlank()) put("album", t.album)
            if (t.albumArtUrl.isNotBlank()) put("artworkUrl", t.albumArtUrl)
            if (t.durationSeconds > 0) put("durationSeconds", t.durationSeconds)
            t.artistId?.takeIf(String::isNotBlank)?.let { put("artistId", it) }
        }

    fun parseTrack(json: JSONObject?): Track? {
        val id = json?.optString("id")?.takeIf { it.matches(VIDEO_ID) } ?: return null
        return Track(
            id = id,
            title = json.optString("title").take(200),
            artist = json.optString("artist").take(200),
            album = json.optString("album").take(200),
            albumArtUrl = json.optString("artworkUrl").takeIf { it.startsWith("https://") }.orEmpty(),
            durationSeconds = json.optInt("durationSeconds"),
            artistId = json.optString("artistId").takeIf { it.startsWith("UC") },
        )
    }

    /** Union of two lists of plays by song and time, newest first, capped. */
    fun mergePlays(local: Any?, remote: Any?): JSONObject {
        val all = listOfNotNull((local as? JSONObject)?.optJSONArray("plays"), (remote as? JSONObject)?.optJSONArray("plays"))
            .flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            .filter { it.optJSONObject("track")?.optString("id").orEmpty().isNotEmpty() && it.optLong("playedAt") > 0 }
        val unique = LinkedHashMap<String, JSONObject>()
        for (p in all) unique.putIfAbsent("${p.getJSONObject("track").getString("id")}@${p.getLong("playedAt")}", p)
        return JSONObject().put("plays", JSONArray(unique.values.sortedByDescending { it.getLong("playedAt") }.take(RECENT_MAX)))
    }
}
