package com.moodify.app.ui.player

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.moodify.app.data.Track
import com.moodify.app.player.PlaybackPhase
import com.moodify.app.player.PlaybackProgress
import com.moodify.app.player.PlaybackState
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.LikeIcon
import com.moodify.app.ui.designsystem.MorphingPlayButton
import com.moodify.app.ui.designsystem.WavySeekBar
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.LocalArtworkColors
import com.moodify.app.ui.theme.Motion
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Callbacks the full player needs; grouped so the call site stays readable. */
class FullPlayerActions(
    val onCollapse: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSkipTo: (index: Int) -> Unit,
    val onSeek: (Long) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onToggleRepeat: () -> Unit,
    val onRetry: () -> Unit,
    val onToggleLike: (Track) -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenLyrics: () -> Unit,
    val onOpenMore: () -> Unit,
    val onOpenSleepTimer: () -> Unit,
    val onDownload: (Track) -> Unit,
    val onSearchArtist: (String) -> Unit,
)

/**
 * The immersive Now Playing surface: blurred, artwork-tinted backdrop with
 * drifting colour, a swipeable cover carousel, a wavy seek bar and a
 * morphing play button. Drag down anywhere outside the cover to collapse.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullPlayer(
    state: PlaybackState,
    progress: StateFlow<PlaybackProgress>,
    sheet: PlayerSheetState,
    isFavorite: Boolean,
    actions: FullPlayerActions,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val artwork = LocalArtworkColors.current
    val scope = rememberCoroutineScope()
    val isDownloaded = track.id in state.downloadedTrackIds

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(artwork.playerTop, artwork.playerBottom)))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> sheet.dragBy(delta) },
                onDragStopped = { velocity -> sheet.settle(velocity) },
            ),
    ) {
        PlayerBackdrop(track = track, accent = artwork.playerAccent, isPlaying = state.isPlaying)

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = Spacing.sm),
        ) {
            // ── Header ─────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onCollapse) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    if (state.queue.size > 1) {
                        Text(
                            "${state.currentIndex + 1} of ${state.queue.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                    }
                }
                IconButton(onClick = actions.onOpenMore) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "More options", tint = Color.White)
                }
            }

            // ── Cover carousel ─────────────────────────────────────────────
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CoverCarousel(
                    state = state,
                    onSkipTo = actions.onSkipTo,
                    glow = artwork.playerAccent,
                )
            }

            Column(Modifier.padding(horizontal = Spacing.xl)) {
                // ── Title + like ──────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = track,
                        contentKey = { it.id },
                        transitionSpec = { fadeIn(tween(Motion.MEDIUM)).togetherWith(fadeOut(tween(Motion.SHORT))) },
                        modifier = Modifier.weight(1f),
                        label = "playerTitle",
                    ) { shown ->
                        Column {
                            Text(
                                shown.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier
                                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000)
                                    .semantics { heading() },
                            )
                            Text(
                                shown.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.pressable(pressedScale = 0.97f) { actions.onSearchArtist(shown.artist) },
                            )
                        }
                    }
                    IconButton(onClick = { actions.onToggleLike(track) }) {
                        LikeIcon(isFavorite, tint = Color.White.copy(alpha = 0.85f), size = 26.dp)
                    }
                }

                // ── Status (finding a stream / error) ─────────────────────
                PlaybackStatus(phase = state.phase, onRetry = actions.onRetry, onSkip = actions.onNext)

                Spacer(Modifier.height(Spacing.md))
                WavySeekBar(
                    progress = progress,
                    isPlaying = state.isPlaying,
                    accent = artwork.playerAccent,
                    trackColor = Color.White,
                    onSeek = actions.onSeek,
                )

                // ── Transport ─────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleIcon(
                        icon = Icons.Rounded.Shuffle,
                        active = state.isShuffle,
                        accent = artwork.playerAccent,
                        description = if (state.isShuffle) "Shuffle on" else "Shuffle off",
                        onClick = actions.onToggleShuffle,
                    )
                    IconButton(onClick = actions.onPrevious, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    MorphingPlayButton(
                        isPlaying = state.isPlaying,
                        isBusy = state.phase.isBusy,
                        onClick = actions.onTogglePlay,
                        container = artwork.playerAccent,
                        content = artwork.onPlayerAccent,
                    )
                    IconButton(onClick = actions.onNext, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    ToggleIcon(
                        icon = if (state.isRepeat) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        active = state.isRepeat,
                        accent = artwork.playerAccent,
                        description = if (state.isRepeat) "Repeat one on" else "Repeat off",
                        onClick = actions.onToggleRepeat,
                    )
                }

                // ── Secondary actions ─────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.lg))
                        .background(Color.White.copy(alpha = 0.07f))
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    PillAction(Icons.Rounded.Lyrics, "Lyrics", onClick = actions.onOpenLyrics)
                    PillAction(
                        Icons.Rounded.Bedtime,
                        if (state.sleepTimerEndsAtMs != null || state.sleepAtEndOfTrack) "Sleep on" else "Sleep",
                        active = state.sleepTimerEndsAtMs != null || state.sleepAtEndOfTrack,
                        onClick = actions.onOpenSleepTimer,
                    )
                    PillAction(
                        if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                        when {
                            isDownloaded -> "Saved"
                            state.isDownloading -> state.downloadProgress?.let { "$it%" } ?: "Saving"
                            else -> "Download"
                        },
                        active = isDownloaded,
                        busy = state.isDownloading && !isDownloaded,
                        onClick = { if (!isDownloaded) actions.onDownload(track) },
                    )
                    PillAction(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onClick = actions.onOpenQueue)
                }
            }
        }
    }
}

/** Blurred artwork plus two slowly orbiting colour blobs. */
@Composable
private fun PlayerBackdrop(track: Track, accent: Color, isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val t by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "backdropPhase",
    )
    val energy by animateFloatAsState(if (isPlaying) 1f else 0.55f, tween(Motion.AMBIENT), label = "energy")
    Box(Modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AsyncImage(
                model = track.albumArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }
                    .blur(90.dp)
                    .alpha(0.55f),
            )
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val a = androidx.compose.ui.geometry.Offset(w * (0.2f + 0.15f * cos(t)), h * (0.25f + 0.08f * sin(t * 1.4f)))
            val b = androidx.compose.ui.geometry.Offset(w * (0.85f + 0.1f * sin(t * 0.9f)), h * (0.55f + 0.1f * cos(t * 0.7f)))
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * energy), Color.Transparent), a, w * 0.8f), w * 0.8f, a)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f * energy), Color.Transparent), b, w * 0.7f), w * 0.7f, b)
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f)))
        }
    }
}

/**
 * Horizontal pager over the queue. Neighbouring covers peek in at the edges;
 * swiping settles on another track and plays it. The current cover "breathes"
 * slightly smaller when paused.
 */
@Composable
private fun CoverCarousel(state: PlaybackState, onSkipTo: (Int) -> Unit, glow: Color) {
    val queue = state.queue
    if (queue.isEmpty()) return
    val currentIndex = state.currentIndex.coerceIn(0, queue.lastIndex)
    val pagerState = rememberPagerState(initialPage = currentIndex) { queue.size }
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestSkip by rememberUpdatedState(onSkipTo)
    val playingScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.88f,
        animationSpec = Motion.bouncy(),
        label = "coverScale",
    )

    // Follow external track changes (next/previous buttons, auto-advance).
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex && currentIndex < pagerState.pageCount) {
            if (abs(pagerState.currentPage - currentIndex) > 3) pagerState.scrollToPage(currentIndex)
            else pagerState.animateScrollToPage(currentIndex)
        }
    }
    // Play whatever the user swipes to.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> if (page != latestIndex) latestSkip(page) }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 64.dp, maxHeight - 24.dp).coerceAtLeast(160.dp)
        val horizontalPadding = (maxWidth - side) / 2
        // Soft colour glow behind the cover. Drawn here, outside the pager,
        // because the pager clips its pages and would cut a shadow off flat.
        val glowAlpha by animateFloatAsState(if (state.isPlaying) 0.55f else 0.3f, tween(Motion.AMBIENT), label = "coverGlow")
        androidx.compose.foundation.Canvas(Modifier.size(side * 1.5f)) {
            drawCircle(
                Brush.radialGradient(listOf(glow.copy(alpha = glowAlpha), Color.Transparent)),
                radius = size.minDimension / 2,
            )
        }
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            pageSpacing = Spacing.lg,
            key = { index -> queue.getOrNull(index)?.id ?: index },
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val item = queue[page]
            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
            val distance = abs(pageOffset).coerceIn(0f, 1f)
            Box(
                Modifier
                    .size(side)
                    .graphicsLayer {
                        val baseScale = lerp(1f, 0.82f, distance)
                        val scale = if (page == currentIndex) baseScale * playingScale else baseScale
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.45f, distance)
                        rotationY = pageOffset * -8f
                    }
                    .shadow(
                        elevation = if (page == currentIndex) 18.dp else 6.dp,
                        shape = RoundedCornerShape(Radii.xl),
                    )
                    .semantics { contentDescription = "Cover of ${item.title}" },
            ) {
                ArtworkImage(
                    url = item.albumArtUrl,
                    contentDescription = null,
                    placeholderKey = item.id,
                    shape = RoundedCornerShape(Radii.xl),
                    modifier = Modifier.fillMaxSize().aspectRatio(1f),
                )
            }
        }
    }
}

@Composable
private fun PlaybackStatus(phase: PlaybackPhase, onRetry: () -> Unit, onSkip: () -> Unit) {
    AnimatedVisibility(
        visible = phase is PlaybackPhase.Resolving || phase is PlaybackPhase.Error,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        when (phase) {
            is PlaybackPhase.Error -> Surface(
                color = Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(Radii.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            ) {
                Row(Modifier.padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(phase.message, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = onRetry) { Text("Retry", color = Color.White) }
                    TextButton(onClick = onSkip) { Text("Skip", color = Color.White.copy(alpha = 0.7f)) }
                }
            }
            is PlaybackPhase.Resolving -> Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), color = Color.White.copy(alpha = 0.7f), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    if (phase.attempt == 0) "Finding the best stream…" else "Trying another source…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, active: Boolean, accent: Color, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (active) accent else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp),
            )
            if (active) {
                Box(
                    Modifier
                        .padding(top = 30.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun PillAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    busy: Boolean = false,
) {
    val accent = LocalArtworkColors.current.playerAccent
    Column(
        Modifier
            .clip(RoundedCornerShape(Radii.md))
            .pressable(pressedScale = 0.9f, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, tint = if (active) accent else Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) accent else Color.White.copy(alpha = 0.8f))
    }
}
