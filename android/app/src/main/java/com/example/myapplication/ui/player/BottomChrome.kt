package com.moodify.app.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moodify.app.data.Track
import com.moodify.app.player.PlaybackProgress
import com.moodify.app.player.PlaybackState
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.ProgressRing
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.LocalArtworkColors
import com.moodify.app.ui.theme.Motion
import com.moodify.app.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class MainTab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home, Icons.Outlined.Home),
    SEARCH("Search", Icons.Rounded.Search, Icons.Outlined.Search),
    LIBRARY("Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
}

val MiniPlayerHeight = 64.dp
val NavBarHeight = 64.dp

/**
 * Floating mini player. Tap or drag up to open the full player; swipe
 * sideways to skip. The artwork spins slowly like a record while playing,
 * wrapped in a progress ring.
 */
@Composable
fun MiniPlayer(
    state: PlaybackState,
    progress: StateFlow<PlaybackProgress>,
    sheet: PlayerSheetState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val artwork = LocalArtworkColors.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val swipe = remember { Animatable(0f) }
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .height(MiniPlayerHeight)
            .shadow(18.dp, RoundedCornerShape(22.dp), ambientColor = artwork.glow, spotColor = Color.Black)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceContainerHigh)
            .background(
                Brush.horizontalGradient(
                    listOf(artwork.accent.copy(alpha = 0.30f), artwork.accent.copy(alpha = 0.06f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(22.dp))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> sheet.dragBy(delta) },
                onDragStopped = { velocity -> sheet.settle(velocity) },
            )
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val threshold = with(density) { 72.dp.toPx() }
                            when {
                                swipe.value < -threshold -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onNext()
                                }
                                swipe.value > threshold -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPrevious()
                                }
                            }
                            swipe.animateTo(0f, Motion.bouncy())
                        }
                    },
                    onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        scope.launch { swipe.snapTo(swipe.value + delta * 0.6f) }
                    },
                )
            }
            .pressable(pressedScale = 0.98f, onClick = { scope.launch { sheet.expand() } })
            .semantics { contentDescription = "Now playing: ${track.title} by ${track.artist}. Open player" },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(swipe.value.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(swipe.value) / 600f).coerceIn(0f, 0.5f) }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinningArtwork(track = track, isPlaying = state.isPlaying, progress = progress, ringColor = artwork.accent)
            Spacer(Modifier.width(Spacing.md))
            AnimatedContent(
                targetState = track,
                transitionSpec = {
                    (fadeIn(tween(Motion.MEDIUM)) + slideInHorizontally { it / 4 })
                        .togetherWith(fadeOut(tween(Motion.SHORT)) + slideOutHorizontally { -it / 4 })
                },
                contentKey = { it.id },
                modifier = Modifier.weight(1f),
                label = "miniTrack",
            ) { shown ->
                Column {
                    Text(
                        shown.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            state.phase is com.moodify.app.player.PlaybackPhase.Resolving -> "Finding a stream…"
                            state.errorMessage != null -> "Tap to retry"
                            else -> shown.artist
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (state.phase.isBusy) {
                    CircularProgressIndicator(Modifier.size(40.dp), color = artwork.accent, strokeWidth = 2.dp)
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = colors.onSurface,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", tint = colors.onSurface)
            }
        }
    }
}

@Composable
private fun SpinningArtwork(
    track: Track,
    isPlaying: Boolean,
    progress: StateFlow<PlaybackProgress>,
    ringColor: Color,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            rotation.animateTo(rotation.value + 360f, tween(14_000, easing = LinearEasing))
        }
    }
    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        ProgressRing(progress = progress, color = ringColor, modifier = Modifier.fillMaxSize())
        ArtworkImage(
            url = track.albumArtUrl,
            contentDescription = null,
            placeholderKey = track.id,
            shape = CircleShape,
            modifier = Modifier
                .size(40.dp)
                .rotate(rotation.value),
        )
        // Spindle hole sells the "record" look.
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

/**
 * Floating, frosted navigation pill with a sliding selection indicator.
 */
@Composable
fun MoodifyNavBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .height(NavBarHeight)
            .shadow(24.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh.copy(alpha = 0.97f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
            .padding(6.dp),
    ) {
        val tabWidth = maxWidth / MainTab.entries.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selected.ordinal,
            animationSpec = Motion.bouncy(),
            label = "navIndicator",
        )
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.16f)),
        )
        Row(Modifier.fillMaxSize()) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .pressable(pressedScale = 0.92f, role = Role.Tab) {
                            if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(tab)
                        }
                        .semantics {
                            this.selected = isSelected
                            role = Role.Tab
                            contentDescription = tab.label
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isSelected) tab.selectedIcon else tab.icon,
                        contentDescription = null,
                        tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
