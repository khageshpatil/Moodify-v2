package com.moodify.app.ui.theme

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Colours derived from the current track's artwork.
 *
 * [accent]/[onAccent] suit the app's own theme (light or dark) and are used
 * for the mini player and progress. [glow] tints ambient gradients behind
 * headers. The `player*` colours are always dark and immersive, because the
 * full player is a lean-back surface in both themes.
 */
@Immutable
data class ArtworkColors(
    val accent: Color,
    val onAccent: Color,
    val glow: Color,
    val playerTop: Color,
    val playerBottom: Color,
    val playerAccent: Color,
    val onPlayerAccent: Color,
) {
    companion object {
        fun fallback(dark: Boolean): ArtworkColors = ArtworkColors(
            accent = if (dark) AuraDark.primary else AuraLight.primary,
            onAccent = if (dark) AuraDark.onPrimary else AuraLight.onPrimary,
            glow = Color(0xFF7C5CFF),
            playerTop = Color(0xFF2A1F52),
            playerBottom = Color(0xFF0B0B10),
            playerAccent = AuraDark.primary,
            onPlayerAccent = AuraDark.onPrimary,
        )

        /** Builds a harmonious set from a seed colour, clamping lightness so text stays readable. */
        fun fromSeed(seed: Int, deep: Int?, dark: Boolean): ArtworkColors {
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(seed, it) }
            val hue = hsl[0]
            // Desaturated, near-grey art still deserves a little colour.
            val sat = hsl[1].coerceIn(0.28f, 0.82f)

            fun hslColor(s: Float, l: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(hue, s, l)))

            val accent = if (dark) hslColor(sat, 0.72f) else hslColor(sat.coerceAtLeast(0.45f), 0.40f)
            val onAccent = readableOn(accent)
            val playerAccent = hslColor(sat.coerceAtLeast(0.4f), 0.74f)

            val deepHsl = FloatArray(3).also { ColorUtils.colorToHSL(deep ?: seed, it) }
            val playerTop = Color(
                ColorUtils.HSLToColor(
                    floatArrayOf(deepHsl[0], deepHsl[1].coerceIn(0.25f, 0.65f), 0.22f),
                ),
            )
            val playerBottom = Color(
                ColorUtils.HSLToColor(
                    floatArrayOf(deepHsl[0], deepHsl[1].coerceIn(0.15f, 0.4f), 0.06f),
                ),
            )
            return ArtworkColors(
                accent = accent,
                onAccent = onAccent,
                glow = hslColor(sat.coerceAtLeast(0.45f), 0.55f),
                playerTop = playerTop,
                playerBottom = playerBottom,
                playerAccent = playerAccent,
                onPlayerAccent = readableOn(playerAccent),
            )
        }

        private fun readableOn(background: Color): Color {
            val bg = background.toArgb()
            val dark = Color(0xFF111015).toArgb()
            return if (ColorUtils.calculateContrast(dark, bg) >= 4.5) Color(0xFF111015) else Color.White
        }

        fun fromPalette(palette: Palette, dark: Boolean): ArtworkColors? {
            val seedSwatch = palette.vibrantSwatch
                ?: palette.lightVibrantSwatch
                ?: palette.darkVibrantSwatch
                ?: palette.dominantSwatch
                ?: return null
            val deep = (palette.darkMutedSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch)?.rgb
            return fromSeed(seedSwatch.rgb, deep, dark)
        }
    }
}

/**
 * Dynamic (not static) so that only composables which read it recompose while
 * the colours animate between tracks.
 */
val LocalArtworkColors = compositionLocalOf { ArtworkColors.fallback(dark = true) }

/** Extracts and smoothly animates [ArtworkColors] for [artworkUrl]. */
@Composable
fun rememberArtworkColors(artworkUrl: String?, dark: Boolean): ArtworkColors {
    val context = LocalContext.current
    var target by remember(dark) { mutableStateOf(ArtworkColors.fallback(dark)) }

    LaunchedEffect(artworkUrl, dark) {
        if (artworkUrl.isNullOrBlank()) {
            target = ArtworkColors.fallback(dark)
            return@LaunchedEffect
        }
        val bitmap = loadSmallBitmap(context, artworkUrl) ?: return@LaunchedEffect
        val colors = withContext(Dispatchers.Default) {
            ArtworkColors.fromPalette(Palette.from(bitmap).maximumColorCount(16).generate(), dark)
        }
        if (colors != null) target = colors
    }

    val spec = tween<Color>(durationMillis = Motion.AMBIENT, easing = Motion.Standard)
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "onAccent")
    val glow by animateColorAsState(target.glow, spec, label = "glow")
    val playerTop by animateColorAsState(target.playerTop, spec, label = "playerTop")
    val playerBottom by animateColorAsState(target.playerBottom, spec, label = "playerBottom")
    val playerAccent by animateColorAsState(target.playerAccent, spec, label = "playerAccent")
    val onPlayerAccent by animateColorAsState(target.onPlayerAccent, spec, label = "onPlayerAccent")
    return ArtworkColors(accent, onAccent, glow, playerTop, playerBottom, playerAccent, onPlayerAccent)
}

private suspend fun loadSmallBitmap(context: android.content.Context, url: String): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(160)
        .allowHardware(false) // Palette needs to read pixels.
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    return (result.drawable as? BitmapDrawable)?.bitmap
}
