package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF9A3412),
    secondary = Color(0xFF5654C8),
    tertiary = Color(0xFF0F766E),
    background = Color(0xFFF7F5EF),
    surface = Color(0xFFFFFDF8),
    surfaceVariant = Color(0xFFEDE9DF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1F2933),
    onSurface = Color(0xFF1F2933),
    onSurfaceVariant = Color(0xFF52606D),
    outline = Color(0xFFB8B2A8)
)

private val MoodifyTypography = Typography().copy(
    bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.Serif)
)

@Composable
fun MoodifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = MoodifyTypography,
        content = content
    )
}
