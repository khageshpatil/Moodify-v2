package com.moodify.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.moodify.app.data.CatalogSection
import com.moodify.app.data.LocalPlaylist
import com.moodify.app.data.Track
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.CatalogShelf
import com.moodify.app.ui.designsystem.ErrorState
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.designsystem.LocalNowPlaying
import com.moodify.app.ui.designsystem.OfflineBanner
import com.moodify.app.ui.designsystem.QuickPicksGrid
import com.moodify.app.ui.designsystem.SectionHeader
import com.moodify.app.ui.designsystem.SkeletonShelf
import com.moodify.app.ui.designsystem.musicOnly
import com.moodify.app.ui.designsystem.sharedArtwork
import com.moodify.app.ui.designsystem.MoodOption
import com.moodify.app.ui.designsystem.orderedMoods
import com.moodify.app.ui.designsystem.placeholderHue
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.player.PlaylistCollage
import com.moodify.app.ui.theme.LocalArtworkColors
import com.moodify.app.ui.theme.MoodHues
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/** Everything Home needs, as plain data, so the screen stays a pure function of state. */
data class HomeUiState(
    val sections: List<CatalogSection> = emptyList(),
    val quickPicks: List<Track> = emptyList(),
    /** True when [quickPicks] are personal recommendations rather than a recap. */
    val quickPicksArePersonal: Boolean = false,
    val pinnedPlaylists: List<LocalPlaylist> = emptyList(),
    val isLoading: Boolean = false,
    val isStale: Boolean = false,
    val error: String? = null,
    /** Mood ids picked during setup, shown first. */
    val favoriteMoods: List<String> = emptyList(),
    val keepListening: List<Track> = emptyList(),
    val forgotten: List<Track> = emptyList(),
    val becauseArtist: String? = null,
    val becauseTracks: List<Track> = emptyList(),
    /** Downloaded songs, surfaced at the top when the network is unavailable. */
    val offlineTracks: List<Track> = emptyList(),
    /** YouTube Music's own Home filters (Relax, Feel good, …). */
    val chips: List<com.moodify.app.data.CatalogChip> = emptyList(),
    val selectedChip: com.moodify.app.data.CatalogChip? = null,
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (LocalPlaylist) -> Unit,
    onSelectChip: (com.moodify.app.data.CatalogChip) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefreshDiscover: () -> Unit = {},
) {
    val actions = LocalMoodifyActions.current
    val favoriteIds = com.moodify.app.ui.designsystem.LocalFavoriteIds.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Keep Home endless: fetch the next page of shelves near the bottom.
    val latestLoadMore by androidx.compose.runtime.rememberUpdatedState(onLoadMore)
    val nearEnd by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    androidx.compose.runtime.LaunchedEffect(state.canLoadMore) {
        if (!state.canLoadMore) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { nearEnd }.collect { if (it) latestLoadMore() }
    }
    val sections = remember(state.sections, state.quickPicks) {
        val pickIds = state.quickPicks.mapTo(mutableSetOf()) { it.id }
        state.sections
            .map { it.musicOnly() }
            .map { section -> section.copy(items = section.items.filterNot { it.track?.id in pickIds }) }
            .filter { it.items.isNotEmpty() }
    }

    PullToRefreshBox(
        isRefreshing = state.isLoading && state.sections.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            AuraGlow()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.section),
            ) {
                item(key = "header") {
                    HomeHeader(onOpenSearch = onOpenSearch, onOpenSettings = onOpenSettings)
                }
                item(key = "moods") {
                    MoodRow(moods = orderedMoods(state.favoriteMoods), onMood = { actions.searchFor(it.query) })
                }
                if (state.isStale) {
                    item(key = "offline") { OfflineBanner(onRetry = onRefresh) }
                    if (state.offlineTracks.isNotEmpty()) {
                        item(key = "offlineShelf") {
                            TrackShelf(title = "Available offline", eyebrow = "Your downloads", tracks = state.offlineTracks)
                        }
                    }
                }
                if (state.quickPicks.isNotEmpty()) {
                    item(key = "quickpicks") {
                        Column {
                            if (state.quickPicksArePersonal) {
                                SectionHeader(title = "Discover", eyebrow = "Swipe → to like · ← to skip")
                                Spacer(Modifier.height(Spacing.md))
                                com.moodify.app.ui.designsystem.DiscoverDeck(
                                    tracks = state.quickPicks,
                                    onPlay = { track, queue -> actions.play(track, queue) },
                                    onLike = { track -> if (track.id !in favoriteIds) actions.toggleFavorite(track) },
                                    onQueue = actions.addToQueue,
                                    onRefresh = onRefreshDiscover,
                                )
                            } else {
                                SectionHeader(title = "Quick picks", eyebrow = "Start here")
                                Spacer(Modifier.height(Spacing.md))
                                QuickPicksGrid(tracks = state.quickPicks)
                            }
                        }
                    }
                }
                if (state.keepListening.isNotEmpty()) {
                    item(key = "keepListening") {
                        TrackShelf(title = "Keep listening", eyebrow = "On repeat", tracks = state.keepListening)
                    }
                }
                if (state.becauseArtist != null && state.becauseTracks.isNotEmpty()) {
                    item(key = "because") {
                        TrackShelf(title = "Because you like ${state.becauseArtist}", eyebrow = "Similar artists", tracks = state.becauseTracks)
                    }
                }
                if (state.forgotten.isNotEmpty()) {
                    item(key = "forgotten") {
                        TrackShelf(title = "Forgotten favourites", eyebrow = "Remember these?", tracks = state.forgotten)
                    }
                }
                if (state.pinnedPlaylists.isNotEmpty()) {
                    item(key = "pinned") {
                        Column {
                            SectionHeader(title = "Your pinned playlists")
                            Spacer(Modifier.height(Spacing.md))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                            ) {
                                items(state.pinnedPlaylists, key = { it.id }) { playlist ->
                                    PlaylistCard(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
                                }
                            }
                        }
                    }
                }
                if (state.chips.isNotEmpty()) {
                    item(key = "chips") {
                        HomeChipRow(chips = state.chips, selected = state.selectedChip, onSelect = onSelectChip)
                    }
                }
                when {
                    sections.isNotEmpty() -> items(sections, key = { "shelf:${it.id}:${it.title}" }) { section ->
                        CatalogShelf(section)
                    }
                    state.isLoading -> items(3, key = { "skeleton$it" }) { SkeletonShelf() }
                    state.error != null -> item(key = "error") {
                        ErrorState(
                            message = state.error,
                            onRetry = onRefresh,
                            title = "Couldn't load your music",
                            secondaryLabel = "Search instead",
                            onSecondary = onOpenSearch,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(onOpenSearch: () -> Unit, onOpenSettings: () -> Unit) {
    val accent = LocalArtworkColors.current.accent
    val onBackground = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    greeting().uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "What's your\nmood today?",
                    style = MaterialTheme.typography.displaySmall.merge(
                        TextStyle(brush = Brush.linearGradient(listOf(onBackground, onBackground, accent))),
                    ),
                    modifier = Modifier.semantics { heading() },
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        // A big, friendly entry into search. Tapping goes straight to the field.
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .pressable(pressedScale = 0.98f, onClick = onOpenSearch)
                .semantics { contentDescription = "Search songs, artists, or a feeling" }
                .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.md))
            Text(
                "Songs, artists, or a feeling…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MoodRow(moods: List<MoodOption>, onMood: (MoodOption) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(moods, key = { it.id }) { mood ->
            Row(
                Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(mood.hue.copy(alpha = 0.32f), mood.hue.copy(alpha = 0.12f))))
                    .border(1.dp, mood.hue.copy(alpha = 0.35f), CircleShape)
                    .pressable(pressedScale = 0.94f, haptic = true) { onMood(mood) }
                    .padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mood.icon, contentDescription = null, tint = mood.hue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(mood.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun PlaylistCard(playlist: LocalPlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(152.dp).pressable(onClick = onClick)) {
        PlaylistCollage(
            playlist,
            Modifier
                .size(152.dp)
                .sharedArtwork("playlist:${playlist.id}")
                .shadow(6.dp, RoundedCornerShape(Radii.md)),
            RoundedCornerShape(Radii.md),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${playlist.tracks.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 0..4 -> "Still up"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/** A horizontal shelf of songs as cards; playing one queues the whole shelf. */
@Composable
private fun TrackShelf(title: String, eyebrow: String?, tracks: List<Track>) {
    val actions = LocalMoodifyActions.current
    Column {
        SectionHeader(title = title, eyebrow = eyebrow)
        Spacer(Modifier.height(Spacing.md))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            items(tracks, key = { it.id }) { track ->
                com.moodify.app.ui.designsystem.MediaCard(
                    item = com.moodify.app.data.CatalogItem(
                        type = "track",
                        id = track.id,
                        title = track.title,
                        artworkUrl = track.albumArtUrl,
                        track = track,
                    ),
                    onClick = { actions.play(track, tracks) },
                )
            }
        }
    }
}

/** YouTube Music's Home filters. Selecting one reloads the shelves below. */
@Composable
private fun HomeChipRow(
    chips: List<com.moodify.app.data.CatalogChip>,
    selected: com.moodify.app.data.CatalogChip?,
    onSelect: (com.moodify.app.data.CatalogChip) -> Unit,
) {
    Column {
        SectionHeader(title = "Explore", eyebrow = selected?.let { "Showing ${it.title}" } ?: "Tune your feed")
        Spacer(Modifier.height(Spacing.md))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(chips, key = { it.params }) { chip ->
                androidx.compose.material3.FilterChip(
                    selected = chip.params == selected?.params,
                    onClick = { onSelect(chip) },
                    label = { Text(chip.title) },
                    shape = CircleShape,
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}