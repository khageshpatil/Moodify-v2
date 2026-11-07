// Generated from design-tokens/moodify.tokens.json by scripts/build-tokens.mjs. Do not edit.
package com.moodify.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object AuraDark {
    val background = Color(0xFF0B0B0F)
    val surface = Color(0xFF111116)
    val surfaceLow = Color(0xFF15151B)
    val surfaceContainer = Color(0xFF1A1A21)
    val surfaceHigh = Color(0xFF22222A)
    val surfaceHighest = Color(0xFF2B2B35)
    val outline = Color(0xFF3A3A46)
    val outlineVariant = Color(0xFF26262F)
    val onBackground = Color(0xFFF4F3F8)
    val onSurfaceVariant = Color(0xFFA9A8B6)
    val muted = Color(0xFF6E6D7B)
    val primary = Color(0xFFB79CFF)
    val onPrimary = Color(0xFF1C0B4A)
    val primaryContainer = Color(0xFF3A2A73)
    val onPrimaryContainer = Color(0xFFE6DCFF)
    val secondary = Color(0xFFFFB38A)
    val onSecondary = Color(0xFF3B1A06)
    val secondaryContainer = Color(0xFF2E2650)
    val onSecondaryContainer = Color(0xFFE6DCFF)
    val tertiary = Color(0xFF7FE0C8)
    val onTertiary = Color(0xFF00382D)
    val error = Color(0xFFFF8A8A)
    val onError = Color(0xFF4A0707)
}

internal object AuraLight {
    val background = Color(0xFFF8F7FB)
    val surface = Color(0xFFFFFFFF)
    val surfaceLow = Color(0xFFF3F2F8)
    val surfaceContainer = Color(0xFFEEEDF4)
    val surfaceHigh = Color(0xFFE7E5EF)
    val surfaceHighest = Color(0xFFDFDDE9)
    val outline = Color(0xFFC4C2D0)
    val outlineVariant = Color(0xFFE2E0EA)
    val onBackground = Color(0xFF15141B)
    val onSurfaceVariant = Color(0xFF55536A)
    val muted = Color(0xFF8C8A9C)
    val primary = Color(0xFF6A43F0)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFE7DEFF)
    val onPrimaryContainer = Color(0xFF22095E)
    val secondary = Color(0xFFC2541D)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFEAE3FF)
    val onSecondaryContainer = Color(0xFF22095E)
    val tertiary = Color(0xFF0F7B66)
    val onTertiary = Color(0xFFFFFFFF)
    val error = Color(0xFFC42B2B)
    val onError = Color(0xFFFFFFFF)
}

/** Seed hues for mood tiles and placeholders, chosen to read well on both themes. */
internal val MoodHues = listOf(
    Color(0xFF8B5CF6), // violet
    Color(0xFFF97366), // coral
    Color(0xFF22B8A6), // teal
    Color(0xFFF2A93B), // amber
    Color(0xFF4F8DF7), // blue
    Color(0xFFE5579B), // pink
    Color(0xFF6BBF59), // green
    Color(0xFFB0753E), // bronze
)

/** One radius per role. */
object Radii {
    val xs: Dp = 6.dp
    val sm: Dp = 10.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

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

/** Size / line height (sp), weight and tracking (em) for each type role. */
internal class TypeToken(val size: Int, val line: Int, val weight: FontWeight, val tracking: Double)

internal object TypeScale {
    val displayLarge = TypeToken(52, 56, FontWeight.ExtraBold, -0.035)
    val displayMedium = TypeToken(42, 46, FontWeight.ExtraBold, -0.03)
    val displaySmall = TypeToken(34, 38, FontWeight.ExtraBold, -0.025)
    val headlineLarge = TypeToken(30, 36, FontWeight.Bold, -0.02)
    val headlineMedium = TypeToken(26, 32, FontWeight.Bold, -0.02)
    val headlineSmall = TypeToken(22, 28, FontWeight.Bold, -0.015)
    val titleLarge = TypeToken(20, 26, FontWeight.Bold, -0.01)
    val titleMedium = TypeToken(16, 22, FontWeight.SemiBold, -0.005)
    val titleSmall = TypeToken(14, 20, FontWeight.SemiBold, 0.0)
    val bodyLarge = TypeToken(16, 24, FontWeight.Normal, 0.0)
    val bodyMedium = TypeToken(14, 20, FontWeight.Normal, 0.0)
    val bodySmall = TypeToken(12, 16, FontWeight.Medium, 0.005)
    val labelLarge = TypeToken(14, 20, FontWeight.SemiBold, 0.005)
    val labelMedium = TypeToken(12, 16, FontWeight.SemiBold, 0.01)
    val labelSmall = TypeToken(11, 14, FontWeight.Bold, 0.06)
}
