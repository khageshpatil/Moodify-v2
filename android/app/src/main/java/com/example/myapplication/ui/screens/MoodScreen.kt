package com.moodify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.moodify.app.AppGraph
import com.moodify.app.data.CatalogSection
import com.moodify.app.data.Track
import com.moodify.app.ui.designsystem.CatalogShelf
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.ErrorState
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.designsystem.MoodOption
import com.moodify.app.ui.designsystem.MoodOptions
import com.moodify.app.ui.designsystem.SectionHeader
import com.moodify.app.ui.designsystem.SkeletonRows
import com.moodify.app.ui.designsystem.TrackRow
import com.moodify.app.ui.designsystem.musicOnly
import com.moodify.app.ui.navigation.MoodRoute
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoodUiState(
    val mood: MoodOption,
    val songs: List<Track> = emptyList(),
    /** YouTube Music's own playlists for this mood (from "Moods & genres"), when it has the mood. */
    val shelves: List<CatalogSection> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * One page per mood, whichever way you got there (Home pills, Search tiles,
 * onboarding): songs for the mood plus YouTube's mood playlists for your region.
 */
class MoodViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val route = savedStateHandle.toRoute<MoodRoute>()
    private val mood = MoodOptions.firstOrNull { it.id == route.id } ?: MoodOptions.first()
    private val repository = AppGraph.repository

    private val _state = MutableStateFlow(MoodUiState(mood))
    val state: StateFlow<MoodUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val songs = async {
                runCatching {
                    repository.searchCatalog(mood.query, "song").sections
                        .flatMap { section -> section.items.mapNotNull { it.track } }
                        .distinctBy { it.id }
                        .take(30)
                }
            }
            val shelves = async { runCatching { catalogShelves() } }
            val songResult = songs.await()
            val shelfResult = shelves.await()
            val error = (songResult.exceptionOrNull() ?: shelfResult.exceptionOrNull())
                ?.takeIf { it !is CancellationException }
            _state.update {
                it.copy(
                    songs = songResult.getOrDefault(emptyList()),
                    shelves = shelfResult.getOrDefault(emptyList()),
                    isLoading = false,
                    error = if (songResult.isFailure && shelfResult.getOrDefault(emptyList()).isEmpty()) {
                        error?.message?.takeIf(String::isNotBlank) ?: "Couldn't load this mood."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Finds the same mood in YouTube's "Moods & genres" and returns its shelves. */
    private suspend fun catalogShelves(): List<CatalogSection> {
        val moods = repository.getMoodsAndGenresCatalog()
        val tile = mood.catalogTitles.firstNotNullOfOrNull { wanted ->
            moods.sections.asSequence()
                .flatMap { it.items.asSequence() }
                .firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) && !it.endpoint?.browseId.isNullOrBlank() }
        } ?: return emptyList()
        val endpoint = tile.endpoint ?: return emptyList()
        val page = repository.browseCatalog(
            browseId = endpoint.browseId.orEmpty(),
            params = endpoint.params,
            surface = "browse",
            title = mood.label,
        )
        return page.sections.map { it.musicOnly() }.filter { it.items.isNotEmpty() }
    }
}

@Composable
fun MoodScreen(
    state: MoodUiState,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val actions = LocalMoodifyActions.current
    val mood = state.mood
    val songs = state.songs
    DetailScaffold(
        title = mood.label,
        subtitle = if (songs.isNotEmpty()) "Songs and playlists for this mood" else "",
        eyebrow = "Mood",
        heroArtworkUrl = songs.firstOrNull()?.albumArtUrl,
        bottomPadding = bottomPadding,
        onBack = onBack,
        onPlay = songs.takeIf { it.isNotEmpty() }?.let { { actions.play(it.first(), it) } },
        onShuffle = songs.takeIf { it.size > 1 }?.let { { val s = it.shuffled(); actions.play(s.first(), s) } },
        hero = {
            Box(
                Modifier
                    .size(196.dp)
                    .shadow(28.dp, RoundedCornerShape(Radii.xl), ambientColor = mood.hue, spotColor = mood.hue)
                    .clip(RoundedCornerShape(Radii.xl))
                    // Opaque base first: a see-through gradient let the shadow show through as a square.
                    .background(mood.hue)
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.30f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(mood.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(88.dp))
            }
        },
    ) {
        when {
            state.isLoading && songs.isEmpty() -> item(key = "loading") { SkeletonRows(count = 8) }
            state.error != null -> item(key = "error") { ErrorState(message = state.error, onRetry = onRetry) }
            songs.isEmpty() && state.shelves.isEmpty() -> item(key = "empty") {
                EmptyState(icon = Icons.Rounded.MusicOff, title = "Nothing for this mood yet", message = "Try again in a moment.")
            }
            else -> {
                if (songs.isNotEmpty()) {
                    item(key = "songsHeader") {
                        Column {
                            SectionHeader(title = "Songs")
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                    items(songs.take(12), key = { "song:${it.id}" }) { track ->
                        TrackRow(
                            track = track,
                            queueContext = songs,
                            modifier = Modifier.padding(horizontal = Spacing.gutter - Spacing.sm),
                        )
                    }
                }
                items(state.shelves, key = { "shelf:${it.id}:${it.title}" }) { section ->
                    Box(Modifier.padding(top = Spacing.xl)) { CatalogShelf(section) }
                }
            }
        }
    }
}
