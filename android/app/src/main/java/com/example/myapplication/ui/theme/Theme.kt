package com.moodify.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = AuraDark.primary,
    onPrimary = AuraDark.onPrimary,
    primaryContainer = AuraDark.primaryContainer,
    onPrimaryContainer = AuraDark.onPrimaryContainer,
    secondary = AuraDark.secondary,
    onSecondary = AuraDark.onSecondary,
    secondaryContainer = AuraDark.secondaryContainer,
    onSecondaryContainer = AuraDark.onSecondaryContainer,
    tertiary = AuraDark.tertiary,
    onTertiary = AuraDark.onTertiary,
    background = AuraDark.background,
    onBackground = AuraDark.onBackground,
    surface = AuraDark.surface,
    onSurface = AuraDark.onBackground,
    surfaceVariant = AuraDark.surfaceHigh,
    onSurfaceVariant = AuraDark.onSurfaceVariant,
    surfaceContainerLowest = AuraDark.background,
    surfaceContainerLow = AuraDark.surfaceLow,
    surfaceContainer = AuraDark.surfaceContainer,
    surfaceContainerHigh = AuraDark.surfaceHigh,
    surfaceContainerHighest = AuraDark.surfaceHighest,
    surfaceBright = AuraDark.surfaceHighest,
    surfaceDim = AuraDark.background,
    inverseSurface = AuraDark.onBackground,
    inverseOnSurface = AuraDark.background,
    outline = AuraDark.outline,
    outlineVariant = AuraDark.outlineVariant,
    error = AuraDark.error,
    onError = AuraDark.onError,
    scrim = Color.Black,
)

private val LightScheme = lightColorScheme(
    primary = AuraLight.primary,
    onPrimary = AuraLight.onPrimary,
    primaryContainer = AuraLight.primaryContainer,
    onPrimaryContainer = AuraLight.onPrimaryContainer,
    secondary = AuraLight.secondary,
    onSecondary = AuraLight.onSecondary,
    secondaryContainer = AuraLight.secondaryContainer,
    onSecondaryContainer = AuraLight.onSecondaryContainer,
    tertiary = AuraLight.tertiary,
    onTertiary = AuraLight.onTertiary,
    background = AuraLight.background,
    onBackground = AuraLight.onBackground,
    surface = AuraLight.surface,
    onSurface = AuraLight.onBackground,
    surfaceVariant = AuraLight.surfaceHigh,
    onSurfaceVariant = AuraLight.onSurfaceVariant,
    surfaceContainerLowest = AuraLight.surface,
    surfaceContainerLow = AuraLight.surfaceLow,
    surfaceContainer = AuraLight.surfaceContainer,
    surfaceContainerHigh = AuraLight.surfaceHigh,
    surfaceContainerHighest = AuraLight.surfaceHighest,
    surfaceBright = AuraLight.surface,
    surfaceDim = AuraLight.surfaceHigh,
    inverseSurface = AuraLight.onBackground,
    inverseOnSurface = AuraLight.background,
    outline = AuraLight.outline,
    outlineVariant = AuraLight.outlineVariant,
    error = AuraLight.error,
    onError = AuraLight.onError,
    scrim = Color.Black,
)

/** Extra semantic colours Material's scheme does not cover. */
@Immutable
data class MoodifyExtraColors(
    val muted: Color,
    val isDark: Boolean,
)

val LocalMoodifyColors = staticCompositionLocalOf { MoodifyExtraColors(AuraDark.muted, isDark = true) }

/**
 * Root theme. [artwork] carries the palette of the current track; screens read
 * it through [LocalArtworkColors] to tint ambient glows, the mini player and
 * the full player.
 */
@Composable
fun MoodifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    artwork: ArtworkColors = ArtworkColors.fallback(darkTheme),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMoodifyColors provides MoodifyExtraColors(
            muted = if (darkTheme) AuraDark.muted else AuraLight.muted,
            isDark = darkTheme,
        ),
        LocalArtworkColors provides artwork,
    ) {
        val scheme = if (darkTheme) DarkScheme else LightScheme
        MaterialTheme(
            colorScheme = scheme,
            typography = MoodifyTypography,
            shapes = MoodifyShapes,
        ) {
            // Text and icons without an explicit colour should read on the page background.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

/**
 * The full player is a lean-back, always-dark surface. Its primary colour
 * follows the artwork so sliders, chips and sheets opened from it match.
 */
@Composable
fun PlayerTheme(artwork: ArtworkColors, content: @Composable () -> Unit) {
    val playerScheme = DarkScheme.copy(
            primary = artwork.playerAccent,
            onPrimary = artwork.onPlayerAccent,
            surface = Color(0xFF15141A),
            surfaceContainerLow = Color(0xFF19181F),
            surfaceContainer = Color(0xFF1E1D25),
            surfaceContainerHigh = Color(0xFF26252E),
        )
    MaterialTheme(colorScheme = playerScheme, typography = MoodifyTypography, shapes = MoodifyShapes) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White, content = content)
    }
}

object MoodifyThemeExtras {
    val colors: MoodifyExtraColors
        @Composable get() = LocalMoodifyColors.current
    val artwork: ArtworkColors
        @Composable get() = LocalArtworkColors.current
}
