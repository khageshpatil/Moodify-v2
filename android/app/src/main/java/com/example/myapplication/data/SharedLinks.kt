package com.moodify.app.data

import java.net.URI
import java.net.URLDecoder

/** Something a YouTube / YouTube Music link points at, opened inside Moodify. */
sealed interface SharedLink {
    data class Song(val videoId: String) : SharedLink
    data class Playlist(val playlistId: String) : SharedLink
    data class Album(val browseId: String) : SharedLink
    data class Artist(val channelId: String) : SharedLink
}

/**
 * Reads YouTube and YouTube Music links, including ones buried in shared text
 * ("Check this out: https://music.youtube.com/watch?v=…&si=…").
 */
object SharedLinks {
    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val LIST_ID = Regex("""^[A-Za-z0-9_-]{10,64}$""")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")

    fun parse(text: String?): SharedLink? {
        val candidate = URL.find(text.orEmpty())?.value?.trimEnd('.', ',', ')', '"', '\'') ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val path = uri.path.orEmpty().trim('/')
        val query = queryParams(uri.rawQuery)

        if (host.endsWith("youtu.be")) {
            return path.substringBefore('/').takeIf { VIDEO_ID.matches(it) }?.let(SharedLink::Song)
        }
        val segments = path.split('/').filter(String::isNotBlank)
        return when (segments.firstOrNull()) {
            "watch" -> query["v"]?.takeIf { VIDEO_ID.matches(it) }?.let(SharedLink::Song)
                ?: query["list"]?.takeIf { LIST_ID.matches(it) }?.let(SharedLink::Playlist)
            "shorts", "live", "embed" -> segments.getOrNull(1)?.takeIf { VIDEO_ID.matches(it) }?.let(SharedLink::Song)
            "playlist" -> query["list"]?.takeIf { LIST_ID.matches(it) }?.let { list ->
                // Album pages are shared as playlists with an "OLAK5uy_" id.
                SharedLink.Playlist(list)
            }
            "browse" -> segments.getOrNull(1)?.let { id ->
                when {
                    id.startsWith("MPRE") -> SharedLink.Album(id)
                    id.startsWith("UC") -> SharedLink.Artist(id)
                    id.startsWith("VL") -> SharedLink.Playlist(id.removePrefix("VL"))
                    else -> null
                }
            }
            "channel" -> segments.getOrNull(1)?.takeIf { it.startsWith("UC") }?.let(SharedLink::Artist)
            else -> null
        }
    }

    private fun queryParams(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "").takeIf(String::isNotBlank) ?: return@mapNotNull null
            key to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.toMap()
}
