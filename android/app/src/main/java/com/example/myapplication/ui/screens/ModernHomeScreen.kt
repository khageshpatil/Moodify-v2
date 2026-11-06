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
import com.moodify.app.ui.designsystem.isArtist
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
    /** Albums, playlists and artists saved to the Library. */
    val saved: List<com.moodify.app.data.CatalogItem> = emptyList(),
    /** Albums and playlists you recently played from. */
    val recent: List<com.moodify.app.data.CatalogItem> = emptyList(),
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
    scrollToTopKey: Int = 0,
    onPreview: (com.moodify.app.data.Track?) -> Unit = {},
    onOpenMood: (MoodOption) -> Unit = {},
    previewingId: String? = null,
) {
    val actions = LocalMoodifyActions.current
    val favoriteIds = com.moodify.app.ui.designsystem.LocalFavoriteIds.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.moodify.app.ui.designsystem.ScrollToTopEffect(scrollToTopKey, listState)
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
                    HomeHeader(onOpenSettings = onOpenSettings)
                }
                item(key = "moods") {
                    MoodRow(moods = orderedMoods(state.favoriteMoods), onMood = onOpenMood)
                }
                if (state.isStale) {
                    item(key = "offline") { OfflineBanner(onRetry = onRefresh) }
                    if (state.offlineTracks.isNotEmpty()) {
                        item(key = "offlineShelf") {
                            TrackShelf(title = "Available offline", tracks = state.offlineTracks)
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
                                    onUnlike = { track -> if (track.id in favoriteIds) actions.toggleFavorite(track) },
                                    onPreview = onPreview,
                                    musicPlaying = com.moodify.app.ui.designsystem.LocalNowPlaying.current.isPlaying,
                                    previewingId = previewingId,
                                )
                            } else {
                                SectionHeader(title = "Quick picks")
                                Spacer(Modifier.height(Spacing.md))
                                QuickPicksGrid(tracks = state.quickPicks)
                            }
                        }
                    }
                }
                if (state.keepListening.isNotEmpty()) {
                    item(key = "keepListening") {
                        TrackShelf(title = "Keep listening", tracks = state.keepListening, style = ShelfStyle.ROWS)
                    }
                }
                if (state.becauseArtist != null && state.becauseTracks.isNotEmpty()) {
                    item(key = "because") {
                        TrackShelf(title = "More like ${state.becauseArtist}", tracks = state.becauseTracks)
                    }
                }
                if (state.forgotten.isNotEmpty()) {
                    item(key = "forgotten") {
                        TrackShelf(title = "Forgotten favourites", tracks = state.forgotten, style = ShelfStyle.LARGE)
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
                if (state.recent.isNotEmpty()) {
                    item(key = "recentlyPlayed") {
                        Column {
                            SectionHeader(title = "Recently played")
                            Spacer(Modifier.height(Spacing.md))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                            ) {
                                items(state.recent, key = { "recent:${it.id}" }) { item ->
                                    com.moodify.app.ui.designsystem.MediaCard(
                                        item = item,
                                        onClick = { item.endpoint?.let { actions.openBrowse(it, item.title) } },
                                        width = 128.dp,
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.saved.isNotEmpty()) {
                    item(key = "fromLibrary") {
                        Column {
                            SectionHeader(title = "From your library")
                            Spacer(Modifier.height(Spacing.md))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                            ) {
                                items(state.saved.take(12), key = { "lib:${it.id}" }) { item ->
                                    com.moodify.app.ui.designsystem.MediaCard(
                                        item = item,
                                        onClick = { item.endpoint?.let { actions.openBrowse(it, item.title) } },
                                    )
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
                    sections.isNotEmpty() -> {
                        // Feature the first shelf of playlists/albums with bigger cards.
                        val featuredId = sections.firstOrNull { s -> s.layout == "carousel" && s.items.none { it.track != null } && s.items.none { it.isArtist } }?.id
                        items(sections, key = { "shelf:${it.id}:${it.title}" }) { section ->
                            CatalogShelf(section, cardWidth = if (section.id == featuredId) 208.dp else 152.dp)
                        }
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

/**
 * One calm line: the greeting and Settings. Search has its own tab, so Home
 * gives its first screen to music instead of a second search bar.
 */
@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = Spacing.gutter, end = Spacing.sm)
            .padding(top = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            greeting(),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
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

/** How a song shelf is drawn, so Home isn't one wall of identical cards. */
private enum class ShelfStyle { CARDS, LARGE, ROWS }

/** A horizontal shelf of songs; playing one queues the whole shelf. */
@Composable
private fun TrackShelf(title: String, tracks: List<Track>, style: ShelfStyle = ShelfStyle.CARDS) {
    val actions = LocalMoodifyActions.current
    Column {
        SectionHeader(title = title)
        Spacer(Modifier.height(Spacing.md))
        if (style == ShelfStyle.ROWS) {
            QuickPicksGrid(tracks = tracks)
            return@Column
        }
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
                    width = if (style == ShelfStyle.LARGE) 208.dp else 152.dp,
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