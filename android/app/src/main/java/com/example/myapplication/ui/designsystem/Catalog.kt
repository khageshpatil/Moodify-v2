package com.moodify.app.ui.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.CatalogItem
import com.moodify.app.data.CatalogSection
import com.moodify.app.data.Track
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

private val NON_MUSIC_TYPES = setOf("video", "podcast", "episode")

/**
 * Drops podcasts and episodes. Episodes arrive as playable "tracks" whose
 * byline says "… • Episode", so the parsed album is checked as well.
 */
fun CatalogSection.musicOnly(): CatalogSection =
    copy(items = items.filter { it.type.lowercase() !in NON_MUSIC_TYPES && !it.isSpokenWord })

private val CatalogItem.isSpokenWord: Boolean
    get() = track?.album?.trim()?.lowercase() in NON_MUSIC_TYPES

val CatalogItem.isArtist: Boolean get() = type.equals("artist", ignoreCase = true)

/** Subtitle for a catalog item: artist for tracks, otherwise a readable type. */
val CatalogItem.subtitle: String
    get() = track?.artist?.takeIf(String::isNotBlank)
        ?: when (type.lowercase()) {
            "album" -> "Album"
            "single" -> "Single"
            "playlist" -> "Playlist"
            "artist" -> "Artist"
            "track", "song" -> "Song"
            else -> type.replaceFirstChar { it.uppercase() }
        }

/** Click behaviour shared by all catalog surfaces. */
@Composable
fun rememberCatalogClick(sectionTracks: List<Track>): (CatalogItem) -> Unit {
    val actions = LocalMoodifyActions.current
    return { item ->
        val track = item.track
        val endpoint = item.endpoint
        when {
            track != null -> actions.play(track, sectionTracks.ifEmpty { listOf(track) })
            endpoint != null && !endpoint.browseId.isNullOrBlank() -> actions.openBrowse(endpoint, item.title)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Square (or circular, for artists) artwork card with title and subtitle.
 * Playable items reveal a play button; the current track shows an equalizer.
 */
@Composable
fun MediaCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 152.dp,
) {
    val nowPlaying = LocalNowPlaying.current
    val track = item.track
    val isCurrent = track != null && nowPlaying.trackId == track.id
    val artwork = track?.albumArtUrl?.takeIf(String::isNotBlank) ?: item.artworkUrl
    val shape = if (item.isArtist) CircleShape else RoundedCornerShape(Radii.md)

    Column(
        modifier = modifier
            .width(width)
            .pressable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.title}, ${item.subtitle}"
            },
        horizontalAlignment = if (item.isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(if (isCurrent) 18.dp else 6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black),
        ) {
            ArtworkImage(
                url = artwork,
                contentDescription = null,
                placeholderKey = item.id,
                shape = shape,
                modifier = if (track == null) Modifier.fillMaxSize().sharedArtwork(item.id) else Modifier.fillMaxSize(),
            )
            if (track != null) {
                Surface(
                    shape = CircleShape,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.sm)
                        .size(36.dp),
                ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isCurrent) {
                                EqualizerBars(playing = nowPlaying.isPlaying, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            track?.title ?: item.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = if (item.isArtist) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Colourful tile for moods & genres. Uses the catalog's accent colour when present. */
@Composable
fun MoodTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = placeholderHue(title),
    height: Dp = 92.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(Radii.md))
            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f))))
            .pressable(onClick = onClick),
    ) {
        // A soft highlight in the corner gives the tile some depth.
        Box(
            Modifier
                .size(90.dp)
                .align(Alignment.TopEnd)
                .padding(top = 0.dp)
                .background(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)),
                    CircleShape,
                ),
        )
        // Pale catalogue colours (Chill, Feel good…) can't carry white text on
        // their own; a dark wash behind the label keeps it readable (>= 4.5:1).
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.md),
        )
    }
}

/**
 * Renders one catalog section in the layout the catalog asked for. Every
 * layout scrolls edge to edge with the screen gutter as content padding.
 */
@Composable
fun CatalogShelf(section: CatalogSection, modifier: Modifier = Modifier, cardWidth: Dp = 152.dp) {
    val sectionTracks = remember(section) { section.items.mapNotNull { it.track } }
    val onItemClick = rememberCatalogClick(sectionTracks)
    val actions = LocalMoodifyActions.current
    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            title = section.title,
            actionLabel = if (!section.endpoint?.browseId.isNullOrBlank()) "More" else null,
            onAction = section.endpoint?.let { endpoint -> { actions.openBrowse(endpoint, section.title) } },
        )
        Spacer(Modifier.height(Spacing.md))
        when (section.layout) {
            "list" -> QuickPicksGrid(tracks = sectionTracks, items = section.items, onItemClick = onItemClick)
            "grid", "mood-grid", "mood-carousel" -> LazyHorizontalGrid(
                rows = GridCells.Fixed(if (section.items.size > 4) 2 else 1),
                modifier = Modifier.height(if (section.items.size > 4) 196.dp else 92.dp),
                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(section.items, key = { "${it.type}:${it.id}" }) { item ->
                    val accent = vividTileColor(item.accentColor, item.title)
                    MoodTile(
                        title = item.title,
                        accent = accent,
                        onClick = { onItemClick(item) },
                        modifier = Modifier.width(168.dp),
                    )
                }
            }
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                items(section.items, key = { "${it.type}:${it.id}" }) { item ->
                    MediaCard(item = item, onClick = { onItemClick(item) }, width = cardWidth)
                }
            }
        }
    }
}

/**
 * Four rows of compact, instantly playable songs that page horizontally —
 * dense without feeling cramped. Each column is ~88% of the screen so the next
 * one peeks in and invites a swipe.
 */
@Composable
fun QuickPicksGrid(
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    items: List<CatalogItem>? = null,
    onItemClick: ((CatalogItem) -> Unit)? = null,
    columnWidth: Dp = 300.dp,
) {
    val actions = LocalMoodifyActions.current
    val rows = if (tracks.size >= 4) 4 else tracks.size.coerceAtLeast(1)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows),
        modifier = modifier.height((rows * 64 + (rows - 1) * 4).dp),
        contentPadding = PaddingValues(horizontal = Spacing.gutter - Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (items != null && onItemClick != null) {
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                val track = item.track
                if (track != null) {
                    TrackRow(
                        track = track,
                        queueContext = tracks,
                        showLike = false,
                        modifier = Modifier.width(columnWidth),
                    )
                } else {
                    CompactBrowseRow(item = item, onClick = { onItemClick(item) }, modifier = Modifier.width(columnWidth))
                }
            }
        } else {
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    queueContext = tracks,
                    showLike = false,
                    modifier = Modifier.width(columnWidth),
                    onClick = { actions.play(track, tracks) },
                )
            }
        }
    }
}

/** One album / artist / playlist as a compact row (circle art for artists). */
@Composable
fun CompactBrowseRow(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(Radii.md))
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            url = item.artworkUrl,
            contentDescription = null,
            placeholderKey = item.id,
            shape = if (item.isArtist) CircleShape else RoundedCornerShape(Radii.sm),
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
