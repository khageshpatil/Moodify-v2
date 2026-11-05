package com.moodify.app.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.moodify.app.data.Track
import com.moodify.app.ui.theme.Motion
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import com.moodify.app.ui.theme.rememberArtworkColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DeckVerdict { LIKE, SKIP, QUEUE }

/**
 * Discovery as a game: a stack of song cards to swipe through. Right likes the
 * song (which also feeds future recommendations), left skips, the queue button
 * sends it up into the queue, and a tap plays from that card onward. Cards tilt with the drag, a stamp
 * grows as the swipe approaches its threshold, the next cards rise up, and
 * the stack glows in the top card's colours.
 */
@Composable
fun DiscoverDeck(
    tracks: List<Track>,
    onPlay: (track: Track, queue: List<Track>) -> Unit,
    onLike: (Track) -> Unit,
    onQueue: (Track) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deckKey = remember(tracks) { tracks.joinToString { it.id }.hashCode() }
    var index by rememberSaveable(deckKey) { mutableIntStateOf(0) }
    val remaining = tracks.drop(index)
    val top = remaining.firstOrNull()
    val isDark = LocalMoodifyIsDark.current
    val glow = rememberArtworkColors(top?.albumArtUrl, isDark)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var armed by remember { mutableStateOf<DeckVerdict?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        val cardWidth = maxWidth
        val widthPx = with(density) { cardWidth.toPx() }
        val thresholdX = widthPx * 0.28f
        val thresholdY = with(density) { 120.dp.toPx() }

        fun verdictFor(x: Float, y: Float): DeckVerdict? = when {
            y < -thresholdY && abs(y) > abs(x) -> DeckVerdict.QUEUE
            x > thresholdX -> DeckVerdict.LIKE
            x < -thresholdX -> DeckVerdict.SKIP
            else -> null
        }

        fun commit(verdict: DeckVerdict) {
            val track = top ?: return
            scope.launch {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val flyX = when (verdict) {
                    DeckVerdict.LIKE -> widthPx * 1.6f
                    DeckVerdict.SKIP -> -widthPx * 1.6f
                    DeckVerdict.QUEUE -> offsetX.value
                }
                val flyY = if (verdict == DeckVerdict.QUEUE) -widthPx * 1.8f else offsetY.value
                launch { offsetX.animateTo(flyX, tween(Motion.MEDIUM)) }
                offsetY.animateTo(flyY, tween(Motion.MEDIUM))
                when (verdict) {
                    DeckVerdict.LIKE -> onLike(track)
                    DeckVerdict.QUEUE -> onQueue(track)
                    DeckVerdict.SKIP -> Unit
                }
                index += 1
                armed = null
                offsetX.snapTo(0f)
                offsetY.snapTo(0f)
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(CARD_HEIGHT + 28.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                // Coloured glow behind the stack, from the top card's artwork.
                Box(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .height(CARD_HEIGHT)
                        .offset(y = 24.dp)
                        .background(
                            Brush.radialGradient(listOf(glow.glow.copy(alpha = 0.55f), Color.Transparent)),
                            RoundedCornerShape(Radii.xl),
                        ),
                )
                if (top == null) {
                    DeckEmptyCard(onRefresh = {
                        index = 0
                        onRefresh()
                    })
                } else {
                    val dragProgress = (maxOf(abs(offsetX.value) / thresholdX, -offsetY.value / thresholdY)).coerceIn(0f, 1f)
                    // Cards behind the top one, drawn back to front.
                    remaining.take(3).asReversed().forEachIndexed { reversedIndex, track ->
                        val depth = remaining.take(3).size - 1 - reversedIndex
                        if (depth == 0) return@forEachIndexed
                        val rise = if (depth == 1) dragProgress else dragProgress * 0.5f
                        val scale = lerp(1f - depth * 0.06f, 1f - (depth - 1) * 0.06f, rise)
                        val lift = lerp(depth * 14f, (depth - 1) * 14f, rise)
                        DeckCard(
                            track = track,
                            modifier = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationY = lift.dp.toPx()
                                alpha = 1f - depth * 0.15f
                            },
                        )
                    }
                    DeckCard(
                        track = top,
                        modifier = Modifier
                            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                            .graphicsLayer { rotationZ = (offsetX.value / widthPx) * 14f }
                            // Horizontal only: vertical drags must keep scrolling Home.
                            .pointerInput(top.id) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val verdict = verdictFor(offsetX.value, 0f)
                                        if (verdict != null) {
                                            commit(verdict)
                                        } else {
                                            armed = null
                                            scope.launch { launch { offsetX.animateTo(0f, Motion.bouncy()) }; offsetY.animateTo(0f, Motion.bouncy()) }
                                        }
                                    },
                                    onDragCancel = {
                                        armed = null
                                        scope.launch { offsetX.animateTo(0f); offsetY.animateTo(0f) }
                                    },
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        scope.launch { offsetX.snapTo(offsetX.value + amount) }
                                        val verdict = verdictFor(offsetX.value, 0f)
                                        if (verdict != armed) {
                                            if (verdict != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            armed = verdict
                                        }
                                    },
                                )
                            }
                            .pressable(pressedScale = 0.98f) { onPlay(top, remaining) }
                            .semantics {
                                contentDescription = "${top.title} by ${top.artist}. Tap to play, swipe right to like, left to skip, up to queue."
                                customActions = listOf(
                                    CustomAccessibilityAction("Like") { commit(DeckVerdict.LIKE); true },
                                    CustomAccessibilityAction("Skip") { commit(DeckVerdict.SKIP); true },
                                    CustomAccessibilityAction("Add to queue") { commit(DeckVerdict.QUEUE); true },
                                )
                            },
                        likeAlpha = (offsetX.value / thresholdX).coerceIn(0f, 1f),
                        skipAlpha = (-offsetX.value / thresholdX).coerceIn(0f, 1f),
                        queueAlpha = (-offsetY.value / thresholdY).coerceIn(0f, 1f),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            if (top != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeckButton(Icons.Rounded.Close, "Skip", MaterialTheme.colorScheme.onSurfaceVariant) { commit(DeckVerdict.SKIP) }
                    DeckButton(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", MaterialTheme.colorScheme.tertiary) { commit(DeckVerdict.QUEUE) }
                    Surface(
                        shape = CircleShape,
                        color = glow.accent,
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(14.dp, CircleShape, ambientColor = glow.glow, spotColor = glow.glow)
                            .pressable(pressedScale = 0.9f, haptic = true) { onPlay(top, remaining) }
                            .semantics { contentDescription = "Play ${top.title}" },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = glow.onAccent, modifier = Modifier.size(34.dp))
                        }
                    }
                    DeckButton(Icons.Rounded.Favorite, "Like", Color(0xFFE5579B)) { commit(DeckVerdict.LIKE) }
                    Text(
                        "${index + 1}/${tracks.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                    )
                }
            }
        }
    }
    LaunchedEffect(deckKey) {
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }
}

private val CARD_HEIGHT = 340.dp

@Composable
private fun DeckCard(
    track: Track,
    modifier: Modifier = Modifier,
    likeAlpha: Float = 0f,
    skipAlpha: Float = 0f,
    queueAlpha: Float = 0f,
) {
    val nowPlaying = LocalNowPlaying.current
    Box(
        modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .shadow(20.dp, RoundedCornerShape(Radii.xl))
            .clip(RoundedCornerShape(Radii.xl)),
    ) {
        ArtworkImage(
            url = track.albumArtUrl,
            contentDescription = null,
            placeholderKey = track.id,
            shape = RoundedCornerShape(Radii.xl),
            modifier = Modifier.fillMaxSize(),
        )
        BottomScrim(alpha = 0.85f)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.xl),
        ) {
            if (nowPlaying.trackId == track.id) {
                EqualizerBars(playing = nowPlaying.isPlaying, color = Color.White)
                Spacer(Modifier.height(Spacing.sm))
            }
            Text(
                track.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Stamp("LIKE", Icons.Rounded.Favorite, Color(0xFFE5579B), likeAlpha, Modifier.align(Alignment.TopStart).padding(Spacing.xl), -12f)
        Stamp("SKIP", Icons.Rounded.Close, Color(0xFFB0B0BC), skipAlpha, Modifier.align(Alignment.TopEnd).padding(Spacing.xl), 12f)
        Stamp("QUEUE", Icons.AutoMirrored.Rounded.QueueMusic, Color(0xFF7FE0C8), queueAlpha, Modifier.align(Alignment.Center), 0f)
    }
}

@Composable
private fun Stamp(label: String, icon: ImageVector, color: Color, alpha: Float, modifier: Modifier, rotation: Float) {
    if (alpha <= 0.01f) return
    Row(
        modifier
            .graphicsLayer {
                this.alpha = alpha
                rotationZ = rotation
                val scale = 0.8f + 0.25f * alpha
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(Radii.sm))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun DeckButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(52.dp)
            .pressable(pressedScale = 0.88f, haptic = true, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun DeckEmptyCard(onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(RoundedCornerShape(Radii.xl))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("You're all caught up", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Your likes shape the next batch of finds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.pressable(haptic = true, onClick = onRefresh),
        ) {
            Row(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(Spacing.sm))
                Text("Deal new cards", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
