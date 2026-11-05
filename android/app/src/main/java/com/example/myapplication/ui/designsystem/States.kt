package com.moodify.app.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.ui.theme.LocalArtworkColors
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slowly drifting colour glow behind the top of a screen, tinted by the
 * current track's artwork. It is what makes the app feel alive and "of the
 * song" rather than a static template.
 */
@Composable
fun BoxScope.AuraGlow(
    modifier: Modifier = Modifier,
    height: Dp = 420.dp,
    intensity: Float = 1f,
    color: Color = LocalArtworkColors.current.glow,
) {
    val transition = rememberInfiniteTransition(label = "aura")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "auraPhase",
    )
    val background = MaterialTheme.colorScheme.background
    val isDark = LocalMoodifyIsDark.current
    val alpha = (if (isDark) 0.42f else 0.26f) * intensity
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .align(Alignment.TopCenter),
    ) {
        val w = size.width
        val h = size.height
        val c1 = Offset(w * (0.25f + 0.12f * cos(t)), h * (0.18f + 0.08f * sin(t * 1.3f)))
        val c2 = Offset(w * (0.82f + 0.10f * sin(t * 0.8f)), h * (0.10f + 0.10f * cos(t * 1.1f)))
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = c1, radius = w * 0.75f),
            radius = w * 0.75f,
            center = c1,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = alpha * 0.7f), Color.Transparent), center = c2, radius = w * 0.6f),
            radius = w * 0.6f,
            center = c2,
        )
        // Fade into the page background so content below reads cleanly.
        drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to background))
    }
}

/** Whether the app is currently in dark theme; provided at the root. */
val LocalMoodifyIsDark = androidx.compose.runtime.compositionLocalOf { true }

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Something went quiet",
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(Spacing.md))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = onRetry) { Text("Try again") }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}

/** Slim banner shown above cached content when the network is unavailable. */
@Composable
fun OfflineBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.md))
            Text(
                "You're offline — showing saved music",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("Retry", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

// ── Skeletons ───────────────────────────────────────────────────────────────

@Composable
fun SkeletonShelf(modifier: Modifier = Modifier, cardWidth: Dp = 152.dp) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(horizontal = Spacing.gutter)
                .size(width = 160.dp, height = 22.dp)
                .shimmer(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(Spacing.md))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            userScrollEnabled = false,
        ) {
            items(4) {
                Column(Modifier.width(cardWidth)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).shimmer(RoundedCornerShape(Radii.md)))
                    Spacer(Modifier.height(Spacing.sm))
                    Box(Modifier.fillMaxWidth(0.8f).height(14.dp).shimmer(RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.5f).height(12.dp).shimmer(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
fun SkeletonRows(count: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        repeat(count) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).shimmer(RoundedCornerShape(Radii.sm)))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(14.dp).shimmer(RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).shimmer(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/** Fills a Box with a soft scrim gradient so text on artwork stays legible. */
@Composable
fun BoxScope.BottomScrim(alpha: Float = 0.75f) {
    Box(
        Modifier
            .matchParentSize()
            .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = alpha))),
    )
}

/** Snackbars shown inside a bottom sheet (sheets cover the app-level host). */
@Composable
fun SheetSnackbarHost(modifier: Modifier = Modifier) {
    val state = LocalAppSnackbar.current ?: return
    androidx.compose.material3.SnackbarHost(state, modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) { data ->
        androidx.compose.material3.Snackbar(
            snackbarData = data,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.primary,
        )
    }
}
