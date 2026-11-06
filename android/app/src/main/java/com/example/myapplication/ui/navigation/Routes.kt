package com.moodify.app.ui.navigation

import kotlinx.serialization.Serializable

// Top-level tabs. Each keeps its own back stack and scroll position.
@Serializable data object HomeRoute
@Serializable data object SearchRoute
@Serializable data object LibraryRoute

// Destinations pushed on top of a tab.
@Serializable
data class BrowseRoute(
    val browseId: String,
    val params: String? = null,
    val pageType: String? = null,
    val title: String = "",
)

@Serializable data class PlaylistRoute(val id: String)
@Serializable data object LikedRoute
@Serializable data object DownloadsRoute
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute
@Serializable data object StatsRoute

/** One of Moodify's moods (see MoodOptions): songs plus YouTube's matching mood playlists. */
@Serializable data class MoodRoute(val id: String)

/** What kind of page a browse endpoint opens, used for layout and labels. */
enum class BrowseKind(val label: String) {
    ALBUM("Album"), ARTIST("Artist"), PLAYLIST("Playlist"), OTHER("Collection");

    companion object {
        fun of(browseId: String, pageType: String?): BrowseKind {
            val type = pageType.orEmpty()
            return when {
                type.contains("ARTIST", ignoreCase = true) || browseId.startsWith("UC") -> ARTIST
                type.contains("ALBUM", ignoreCase = true) || browseId.startsWith("MPRE") -> ALBUM
                type.contains("PLAYLIST", ignoreCase = true) || browseId.startsWith("VL") -> PLAYLIST
                else -> OTHER
            }
        }
    }
}
