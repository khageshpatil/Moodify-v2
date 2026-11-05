package com.moodify.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One radius per role. */
object Radii {
    val xs = 6.dp      // chips inside cards, tiny badges
    val sm = 10.dp     // row artwork
    val md = 16.dp     // cards, tiles
    val lg = 24.dp     // hero art, sheets
    val xl = 32.dp     // player cover, large surfaces
}

val MoodifyShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.xs),
    small = RoundedCornerShape(Radii.sm),
    medium = RoundedCornerShape(Radii.md),
    large = RoundedCornerShape(Radii.lg),
    extraLarge = RoundedCornerShape(Radii.xl),
)

/** 4dp grid. Screens use [gutter] at the edges and [section] between sections. */
object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val gutter: Dp = 20.dp
    val section: Dp = 32.dp
}

/** Shared motion language: quick and springy for touch, slow and soft for ambience. */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT = 160
    const val MEDIUM = 320
    const val LONG = 520
    const val AMBIENT = 900

    fun <T> press() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow)
    fun <T> emphasized(durationMillis: Int = MEDIUM) = tween<T>(durationMillis, easing = Emphasized)
}
