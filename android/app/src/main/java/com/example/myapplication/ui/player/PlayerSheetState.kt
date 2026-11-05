package com.moodify.app.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Drives the mini ↔ full player transition. [progress] is 0 when collapsed
 * and 1 when expanded; drags move it directly and release settles it with a
 * spring that respects fling velocity.
 */
@Stable
class PlayerSheetState {
    var progress by mutableFloatStateOf(0f)
        private set

    /** Pixel distance that maps to a full 0→1 transition; set from layout. */
    var dragRangePx: Float = 1f

    val isExpanded: Boolean get() = progress > 0.5f
    val isVisible: Boolean get() = progress > 0.001f

    /** [deltaPx] is a vertical drag delta; dragging up (negative) expands. */
    fun dragBy(deltaPx: Float) {
        progress = (progress - deltaPx / dragRangePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** Settles after a drag. [velocityPx] is vertical; negative means flinging up. */
    suspend fun settle(velocityPx: Float) {
        val target = when {
            velocityPx < -FLING_THRESHOLD -> 1f
            velocityPx > FLING_THRESHOLD -> 0f
            else -> if (progress > 0.5f) 1f else 0f
        }
        animateTo(target, -velocityPx / dragRangePx.coerceAtLeast(1f))
    }

    suspend fun expand() = animateTo(1f)
    suspend fun collapse() = animateTo(0f)

    private suspend fun animateTo(target: Float, initialVelocity: Float = 0f) {
        animate(
            initialValue = progress,
            targetValue = target,
            initialVelocity = initialVelocity,
            animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
        ) { value, _ -> progress = value.coerceIn(0f, 1f) }
    }

    private companion object {
        const val FLING_THRESHOLD = 900f
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState = remember { PlayerSheetState() }
