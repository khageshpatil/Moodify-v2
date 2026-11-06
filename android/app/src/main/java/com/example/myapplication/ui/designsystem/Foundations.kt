package com.moodify.app.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.moodify.app.ui.theme.MoodHues
import com.moodify.app.ui.theme.Motion
import kotlin.math.absoluteValue

/**
 * Click with a springy press-scale and optional haptic tick. Every tappable
 * card and tile in Moodify uses this, so touch feels consistent.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    haptic: Boolean = false,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = Motion.press(),
        label = "pressScale",
    )
    val haptics = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick?.let { longClick ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                }
            },
            onClick = {
                if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        )
}

/** A soft travelling highlight used by loading skeletons. */
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    this
        .clip(shape)
        .background(base)
        .drawWithShimmer(x, base, highlight)
}

private fun Modifier.drawWithShimmer(x: Float, base: Color, highlight: Color): Modifier =
    this.then(
        Modifier.background(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(x * 600f - 300f, 0f),
                end = Offset(x * 600f + 300f, 300f),
            ),
        ),
    )

/**
 * Colour for a mood/genre tile: the catalog's accent when it is vivid enough,
 * otherwise a deterministic hue for [key]. Several catalog accents are grey,
 * which made tiles look disabled.
 */
fun vividTileColor(catalogAccent: Long?, key: String): Color {
    val accent = catalogAccent?.let { Color(it.toInt()) } ?: return placeholderHue(key)
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(accent.toArgb(), hsl)
    return if (hsl[1] < 0.28f || hsl[2] < 0.18f) placeholderHue(key) else accent
}

/** Deterministic hue for a string, so placeholders for the same item always match. */
fun placeholderHue(key: String): Color = MoodHues[(key.hashCode().absoluteValue) % MoodHues.size]

/**
 * Artwork with a tinted gradient placeholder, crossfade, and an icon fallback.
 * The Coil interceptor in MoodifyApplication requests a size-appropriate URL.
 */
@Composable
fun ArtworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    placeholderKey: String = url.orEmpty(),
    contentScale: ContentScale = ContentScale.Crop,
) {
    val hue = placeholderHue(placeholderKey)
    val placeholder: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(hue.copy(alpha = 0.55f), hue.copy(alpha = 0.18f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
    Box(modifier.clip(shape)) {
        if (url.isNullOrBlank()) {
            placeholder()
        } else {
            // YouTube video thumbnails are 4:3 frames with black bars around
            // 16:9 video; zoom just enough to crop the bars away.
            val letterboxed = url.contains("ytimg.com/vi")
            SubcomposeAsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (letterboxed) {
                            scaleX = 1.34f
                            scaleY = 1.34f
                        }
                    },
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}

/** Three animated bars shown on whatever is currently playing. Freezes when paused. */
@Composable
fun EqualizerBars(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barWidth: Dp = 3.dp,
) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val heights = listOf(520, 380, 640).mapIndexed { index, duration ->
        transition.animateFloat(
            initialValue = 0.25f + index * 0.1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$index",
        )
    }
    Row(
        modifier = modifier.size(width = barWidth * 3 + 4.dp, height = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { height ->
            val fraction = if (playing) height.value else 0.3f
            Box(
                Modifier
                    .width(barWidth)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

/**
 * Scrolls [state] back to the top each time [key] increases (tapping the tab
 * you're already on). Jumps most of the way first so long lists don't crawl.
 */
@Composable
fun ScrollToTopEffect(key: Int, state: androidx.compose.foundation.lazy.LazyListState) {
    androidx.compose.runtime.LaunchedEffect(key) {
        if (key <= 0) return@LaunchedEffect
        if (state.firstVisibleItemIndex > 6) state.scrollToItem(6)
        state.animateScrollToItem(0)
    }
}
