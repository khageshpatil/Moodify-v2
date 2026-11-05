package com.moodify.app.ui.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.moodify.app.player.PlaybackProgress
import com.moodify.app.ui.theme.Motion
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Seek bar whose played portion ripples like a sound wave while music plays
 * and settles into a straight line when paused. Drag or tap anywhere to seek.
 * Collects [progress] itself so position ticks only redraw this component.
 */
@Composable
fun WavySeekBar(
    progress: StateFlow<PlaybackProgress>,
    isPlaying: Boolean,
    accent: Color,
    trackColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = trackColor,
) {
    val current by progress.collectAsState()
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val latestDuration by rememberUpdatedState(current.durationMs)
    val latestOnSeek by rememberUpdatedState(onSeek)

    val fraction = if (dragging) dragFraction else current.fraction
    val amplitude by animateFloatAsState(
        targetValue = if (isPlaying && !dragging) 1f else 0f,
        animationSpec = tween(Motion.LONG),
        label = "waveAmplitude",
    )
    val thumbRadius by animateDpAsState(if (dragging) 9.dp else 6.dp, label = "thumb")
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase",
    )

    val shownPositionMs = (fraction * current.durationMs).toLong()
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics {
                    contentDescription = "Seek"
                    stateDescription = "${formatDuration(shownPositionMs)} of ${formatDuration(current.durationMs)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        latestOnSeek((target.coerceIn(0f, 1f) * latestDuration).toLong()); true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        latestOnSeek((f * latestDuration).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragEnd = {
                            latestOnSeek((dragFraction * latestDuration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            dragFraction = (dragFraction + delta / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val w = size.width
            val cy = size.height / 2f
            val stroke = 4.dp.toPx()
            val playedX = w * fraction
            val bufferedX = w * current.bufferedFraction.coerceAtLeast(fraction)

            // Unplayed + buffered tracks.
            drawLine(trackColor.copy(alpha = 0.18f), Offset(playedX, cy), Offset(w, cy), stroke, StrokeCap.Round)
            if (bufferedX > playedX) {
                drawLine(trackColor.copy(alpha = 0.38f), Offset(playedX, cy), Offset(bufferedX, cy), stroke, StrokeCap.Round)
            }

            // Played portion as a travelling sine wave.
            val amp = 3.5.dp.toPx() * amplitude
            val wavelength = 28.dp.toPx()
            if (playedX > 0f) {
                val path = Path().apply {
                    moveTo(0f, cy + amp * sin(phase))
                    var x = 0f
                    val step = 2f
                    while (x <= playedX) {
                        lineTo(x, cy + amp * sin((x / wavelength) * 2f * PI.toFloat() - phase))
                        x += step
                    }
                }
                drawPath(path, accent, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            drawCircle(accent, radius = thumbRadius.toPx(), center = Offset(playedX, cy))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(shownPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(current.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * Play/pause that morphs between a circle (paused) and a soft squircle
 * (playing), with a busy ring while a stream is being found or buffered.
 */
@Composable
fun MorphingPlayButton(
    isPlaying: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val corner by animateDpAsState(
        targetValue = if (isPlaying) size * 0.32f else size / 2,
        animationSpec = Motion.bouncy(),
        label = "playCorner",
    )
    val haptics = LocalHapticFeedback.current
    Box(modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = container,
                strokeWidth = 2.5.dp,
                trackColor = container.copy(alpha = 0.15f),
            )
        }
        Box(
            Modifier
                .size(size)
                .shadow(16.dp, RoundedCornerShape(corner), ambientColor = container, spotColor = container)
                .clip(RoundedCornerShape(corner))
                .background(container)
                .pressable(
                    pressedScale = 0.9f,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                )
                .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn(tween(Motion.SHORT)) + scaleIn(initialScale = 0.6f))
                        .togetherWith(fadeOut(tween(Motion.SHORT)) + scaleOut(targetScale = 0.6f))
                },
                label = "playIcon",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
    }
}

/** Thin circular progress ring drawn around mini-player artwork. */
@Composable
fun ProgressRing(
    progress: StateFlow<PlaybackProgress>,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.5.dp,
) {
    val current by progress.collectAsState()
    Canvas(modifier.padding(1.dp)) {
        val stroke = strokeWidth.toPx()
        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(stroke))
        drawArc(color, -90f, 360f * current.fraction, false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * A slim, quiet slider (4dp track, small thumb) for secondary controls like
 * volume, with a full 48dp touch target.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accent: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {
    val latest by rememberUpdatedState(onValueChange)
    var dragging by remember { mutableStateOf(false) }
    val thumb by animateDpAsState(if (dragging) 8.dp else 6.dp, label = "thinThumb")
    Canvas(
        modifier
            .height(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { target -> latest(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> latest((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = true; latest((offset.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        latest((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        val cy = size.height / 2
        val stroke = 4.dp.toPx()
        val x = size.width * value.coerceIn(0f, 1f)
        drawLine(trackColor.copy(alpha = 0.25f), Offset(0f, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
        drawLine(accent, Offset(0f, cy), Offset(x, cy), stroke, StrokeCap.Round)
        drawCircle(accent, radius = thumb.toPx(), center = Offset(x, cy))
    }
}