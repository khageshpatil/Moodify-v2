package com.moodify.app.ui.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moodify.app.data.Track
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/**
 * The single track row used everywhere: artwork, title/artist, a "now playing"
 * equalizer, an optional like button and an actions menu. Long-press also
 * opens the actions sheet. [leading] can replace the artwork (e.g. a track
 * number on album pages).
 */
@Composable
fun TrackRow(
    track: Track,
    queueContext: List<Track>,
    modifier: Modifier = Modifier,
    showLike: Boolean = true,
    subtitle: String? = null,
    leadingNumber: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val actions = LocalMoodifyActions.current
    val nowPlaying = LocalNowPlaying.current
    val isCurrent = nowPlaying.trackId == track.id
    val isFavorite = track.id in LocalFavoriteIds.current
    val isDownloaded = track.id in LocalDownloadedIds.current
    val colors = MaterialTheme.colorScheme
    val titleColor by animateColorAsState(
        if (isCurrent) colors.primary else colors.onSurface,
        label = "rowTitle",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(Radii.md))
            .background(if (isCurrent) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
            .pressable(
                pressedScale = 0.98f,
                onLongClick = { actions.openTrackMenu(track) },
                onClick = onClick ?: { actions.play(track, queueContext) },
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Play next") { actions.playNext(track); true },
                    CustomAccessibilityAction("Add to queue") { actions.addToQueue(track); true },
                    CustomAccessibilityAction(if (isFavorite) "Remove from liked songs" else "Like") {
                        actions.toggleFavorite(track); true
                    },
                    CustomAccessibilityAction("More options") { actions.openTrackMenu(track); true },
                )
            }
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingNumber != null) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    EqualizerBars(playing = nowPlaying.isPlaying)
                } else {
                    Text(
                        "$leadingNumber",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        } else {
            Box(Modifier.size(52.dp)) {
                ArtworkImage(
                    url = track.albumArtUrl,
                    contentDescription = null,
                    placeholderKey = track.id,
                    shape = RoundedCornerShape(Radii.sm),
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = isCurrent,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        EqualizerBars(playing = nowPlaying.isPlaying, color = Color.White)
                    }
                }
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(6.dp))
                }
                if (isDownloaded) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = colors.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    subtitle ?: listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showLike) {
            IconButton(onClick = { actions.toggleFavorite(track) }) {
                LikeIcon(isFavorite)
            }
        }
        IconButton(onClick = { actions.openTrackMenu(track) }) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "More options for ${track.title}",
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** Heart that pops when toggled on. */
@Composable
fun LikeIcon(isFavorite: Boolean, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isFavorite) 1f else 0.9f,
        animationSpec = com.moodify.app.ui.theme.Motion.bouncy(),
        label = "likeScale",
    )
    Icon(
        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = if (isFavorite) "Remove from liked songs" else "Like",
        tint = if (isFavorite) MaterialTheme.colorScheme.primary else tint,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    )
}

@Composable
fun ExplicitBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            "E",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
            color = MaterialTheme.colorScheme.surface,
        )
    }
}
