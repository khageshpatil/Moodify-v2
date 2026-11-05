package com.moodify.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.CatalogPage
import com.moodify.app.data.CatalogSection
import com.moodify.app.data.Track
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.CatalogShelf
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.ErrorState
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.designsystem.SectionHeader
import com.moodify.app.ui.designsystem.SkeletonRows
import com.moodify.app.ui.designsystem.TrackRow
import com.moodify.app.ui.designsystem.formatDuration
import com.moodify.app.ui.designsystem.sharedArtwork
import com.moodify.app.ui.navigation.BrowseKind
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/** Album, artist, playlist or generic browse page. */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onDownloadAll: (List<Track>) -> Unit = {},
) {
    val actions = LocalMoodifyActions.current
    val page = state.page
    val tracks = remember(page) {
        page?.sections.orEmpty().flatMap { section -> section.items.mapNotNull { it.track } }.distinctBy { it.id }
    }
    val sections = remember(page, state.kind) { page?.let { displaySections(it, state.kind) }.orEmpty() }
    val heroArt = remember(page) { heroArtwork(page) }
    val listState = rememberLazyListState()

    // Infinite scroll on long playlists.
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(page?.nextCursor) {
        if (page?.nextCursor.isNullOrBlank()) return@LaunchedEffect
        snapshotFlow { nearEnd }.collect { if (it) latestLoadMore() }
    }

    val runtime = tracks.sumOf { it.durationSeconds.toLong() } * 1000
    val subtitle = buildString {
        if (tracks.isNotEmpty() && state.kind != BrowseKind.ARTIST) {
            append(countLabel(tracks.size))
            append(runtimeSuffix(tracks))
        }
    }

    DetailScaffold(
        title = state.title.ifBlank { state.kind.label },
        subtitle = subtitle,
        eyebrow = state.kind.label,
        heroArtworkUrl = heroArt,
        bottomPadding = bottomPadding,
        onBack = onBack,
        listState = listState,
        onPlay = tracks.takeIf { it.isNotEmpty() }?.let { { actions.play(it.first(), it) } },
        onShuffle = tracks.takeIf { it.size > 1 }?.let { { val s = it.shuffled(); actions.play(s.first(), s) } },
        topActions = {
            if (tracks.isNotEmpty() && state.kind != BrowseKind.ARTIST) {
                androidx.compose.material3.IconButton(onClick = { onDownloadAll(tracks) }) {
                    androidx.compose.material3.Icon(Icons.Rounded.Download, contentDescription = "Download all")
                }
            }
        },
        hero = {
            val shape = if (state.kind == BrowseKind.ARTIST) CircleShape else RoundedCornerShape(Radii.lg)
            ArtworkImage(
                url = heroArt,
                contentDescription = null,
                placeholderKey = state.title,
                shape = shape,
                modifier = Modifier
                    .size(232.dp)
                    .sharedArtwork(state.browseId)
                    .shadow(32.dp, shape),
            )
        },
    ) {
        when {
            page == null && state.isLoading -> item(key = "loading") { SkeletonRows(count = 8) }
            page == null && state.error != null -> item(key = "error") { ErrorState(message = state.error, onRetry = onRetry) }
            sections.isEmpty() -> item(key = "empty") {
                EmptyState(icon = Icons.Rounded.LibraryMusic, title = "Nothing here yet", message = "This collection has no music to show.")
            }
            else -> sections.forEachIndexed { index, section ->
                val sectionTracks = section.items.mapNotNull { it.track }
                val isTrackList = sectionTracks.size >= section.items.size / 2 && sectionTracks.isNotEmpty() && section.layout != "carousel"
                if (isTrackList || (index == 0 && sectionTracks.isNotEmpty() && state.kind != BrowseKind.OTHER)) {
                    if (sections.size > 1 || state.kind == BrowseKind.ARTIST) {
                        item(key = "h:${section.id}:$index") {
                            Column {
                                Spacer(Modifier.height(Spacing.md))
                                SectionHeader(title = section.title)
                                Spacer(Modifier.height(Spacing.sm))
                            }
                        }
                    }
                    val shown = if (state.kind == BrowseKind.ARTIST && index == 0) sectionTracks.take(6) else sectionTracks
                    itemsIndexedTracks(
                        key = "${section.id}:$index",
                        tracks = shown,
                        context = tracks,
                        numbered = state.kind == BrowseKind.ALBUM,
                    )
                } else {
                    item(key = "s:${section.id}:$index") {
                        Box(Modifier.padding(top = Spacing.xl)) { CatalogShelf(section) }
                    }
                }
            }
        }
        if (state.isLoadingMore) {
            item(key = "more") { SkeletonRows(count = 2, modifier = Modifier.padding(top = Spacing.md)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedTracks(
    key: String,
    tracks: List<Track>,
    context: List<Track>,
    numbered: Boolean,
) {
    items(tracks.size, key = { "$key:${tracks[it].id}" }) { position ->
        val track = tracks[position]
        TrackRow(
            track = track,
            queueContext = context,
            leadingNumber = if (numbered) position + 1 else null,
            subtitle = if (numbered) track.artist else null,
            modifier = Modifier.padding(horizontal = Spacing.gutter - Spacing.sm),
        )
    }
}

private fun heroArtwork(page: CatalogPage?): String? {
    page ?: return null
    return page.sections.asSequence()
        .flatMap { it.items.asSequence() }
        .mapNotNull { item -> item.artworkUrl.takeIf(String::isNotBlank) ?: item.track?.albumArtUrl?.takeIf(String::isNotBlank) }
        .firstOrNull()
}

/** Gives generic "Music" shelves meaningful names for the page type. */
private fun displaySections(page: CatalogPage, kind: BrowseKind): List<CatalogSection> =
    page.sections.filter { it.items.isNotEmpty() }.mapIndexed { index, section ->
        if (!section.title.equals("Music", ignoreCase = true) && section.title.isNotBlank()) return@mapIndexed section
        val types = section.items.map { it.type.lowercase() }.toSet()
        val title = when (kind) {
            BrowseKind.ALBUM -> if (index == 0 && "track" in types) "Tracks" else "More from this artist"
            BrowseKind.PLAYLIST -> if (index == 0) "Songs" else "You might also like"
            BrowseKind.ARTIST -> when {
                "track" in types -> if (index == 0) "Top songs" else "More songs"
                "album" in types -> "Albums"
                "playlist" in types -> "Playlists"
                else -> "More music"
            }
            BrowseKind.OTHER -> section.title.ifBlank { "Music" }
        }
        section.copy(title = title)
    }
