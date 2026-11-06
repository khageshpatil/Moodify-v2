package com.moodify.app.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.moodify.app.data.BrowseEndpoint
import com.moodify.app.data.Track

/**
 * App-wide actions available to any composable. Screens and components call
 * these instead of threading a dozen callbacks through every layer.
 */
@Stable
class MoodifyActions(
    /** Play [track], using [context] as the new queue. */
    val play: (track: Track, context: List<Track>) -> Unit,
    val playNext: (Track) -> Unit,
    val addToQueue: (Track) -> Unit,
    val toggleFavorite: (Track) -> Unit,
    /** Opens the track actions sheet. */
    val openTrackMenu: (Track) -> Unit,
    /** Navigates to an album / artist / playlist / browse page. */
    val openBrowse: (BrowseEndpoint, title: String) -> Unit,
    /** Opens Search with [query] already submitted. */
    val searchFor: (query: String) -> Unit,
    /** Opens the track sheet with a "Remove from this playlist" action. */
    val openTrackMenuInPlaylist: (Track, playlistId: String) -> Unit = { track, _ -> openTrackMenu(track) },
    /** Opens the song's artist page (or an artist search when it isn't linked). */
    val openArtist: (Track) -> Unit = { searchFor(it.artist) },
) {
    /** Same actions with a different track-menu behaviour, for a local override. */
    fun withTrackMenu(openMenu: (Track) -> Unit) = MoodifyActions(
        play, playNext, addToQueue, toggleFavorite, openMenu, openBrowse, searchFor, openTrackMenuInPlaylist, openArtist,
    )
}

val LocalMoodifyActions = staticCompositionLocalOf<MoodifyActions> {
    error("MoodifyActions not provided")
}

/** What is playing right now — enough for rows and cards to show a "now playing" state. */
@Immutable
data class NowPlaying(val trackId: String? = null, val isPlaying: Boolean = false)

val LocalNowPlaying = compositionLocalOf { NowPlaying() }

/** Ids of liked tracks, so any heart in the app reflects the truth. */
val LocalFavoriteIds = compositionLocalOf { emptySet<String>() }

/** Ids of downloaded tracks, for the small offline badge on rows. */
val LocalDownloadedIds = compositionLocalOf { emptySet<String>() }

/**
 * The app's snackbar state. Bottom sheets render in their own window above the
 * main snackbar host, so they show [SheetSnackbarHost] to keep Undo reachable.
 */
val LocalAppSnackbar = staticCompositionLocalOf<androidx.compose.material3.SnackbarHostState?> { null }

/** Shared-element scope from the root SharedTransitionLayout (null in previews). */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope =
    staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }

/** The navigation destination's animation scope, provided inside each NavHost destination. */
val LocalNavAnimatedScope =
    compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/**
 * Lets artwork with the same [key] morph between screens (card → detail hero).
 * A no-op when no shared-transition scope is available.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@androidx.compose.runtime.Composable
fun androidx.compose.ui.Modifier.sharedArtwork(key: String): androidx.compose.ui.Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedArtwork.sharedElement(rememberSharedContentState(key = "artwork:$key"), animated)
    }
}