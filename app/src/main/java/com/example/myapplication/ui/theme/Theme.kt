package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    secondary = PurpleGradientEnd,
    tertiary = NeonEmerald,
    background = DarkBackground,
    surface = SurfaceDark,
    surfaceVariant = SurfaceCard,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

private val MoodifyTypography = Typography().copy(
    bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.Serif)
)

@Composable
fun MoodifyTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MoodifyTypography,
        content = content
    )
}
