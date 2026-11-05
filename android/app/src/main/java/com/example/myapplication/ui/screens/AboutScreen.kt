package com.moodify.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.BuildConfig
import com.moodify.app.data.AppSettings
import com.moodify.app.data.SettingsStore
import com.moodify.app.data.ThemeMode
import com.moodify.app.player.PlaybackDiagnostics
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val APK_DOWNLOAD_URL =
    "https://github.com/khageshpatil/Moodify-v2/releases/latest/download/Moodify.apk"

private val REGIONS = listOf("US", "GB", "IN", "CA", "AU", "DE", "FR", "ES", "BR", "MX", "JP", "KR")
private val LANGUAGES = listOf("en" to "English", "hi" to "हिन्दी", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português", "ja" to "日本語", "ko" to "한국어")

class SettingsActions(
    val onBack: () -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onLocale: (language: String, region: String) -> Unit,
    val onShowLyricsControls: (Boolean) -> Unit,
    val onShowLyricsTranslation: (Boolean) -> Unit,
    val onShowLyricsRomanization: (Boolean) -> Unit,
    val onLyricsOffset: (Int) -> Unit,
    val onClearRecentSearches: () -> Unit,
    val onAutoplay: (Boolean) -> Unit = {},
    val onNormalizeVolume: (Boolean) -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    diagnostics: PlaybackDiagnostics,
    downloadCount: Int,
    bottomPadding: Dp,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    var showNotices by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var customLocale by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item(key = "header") {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            }
        }

        item(key = "appearance") {
            SettingsGroup("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ThemeOption("System", Icons.Rounded.BrightnessAuto, settings.themeMode == ThemeMode.SYSTEM, Modifier.weight(1f)) { actions.onThemeMode(ThemeMode.SYSTEM) }
                    ThemeOption("Light", Icons.Rounded.LightMode, settings.themeMode == ThemeMode.LIGHT, Modifier.weight(1f)) { actions.onThemeMode(ThemeMode.LIGHT) }
                    ThemeOption("Dark", Icons.Rounded.DarkMode, settings.themeMode == ThemeMode.DARK, Modifier.weight(1f)) { actions.onThemeMode(ThemeMode.DARK) }
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Colours throughout the app follow the artwork of what's playing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "region") {
            SettingsGroup("Music region") {
                Text(
                    "Home, charts and search are tailored to this country and language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                Text("Country", style = MaterialTheme.typography.labelLarge)
                // Keep "automatic" when only the language changes.
                val regionChoice = if (settings.regionIsAutomatic) SettingsStore.AUTOMATIC_REGION else settings.region
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = settings.regionIsAutomatic,
                        onClick = { actions.onLocale(settings.language, SettingsStore.AUTOMATIC_REGION) },
                        label = {
                            Text(if (settings.regionIsAutomatic) "📍 Automatic · ${flagOf(settings.region)} ${settings.region}" else "📍 Automatic")
                        },
                        shape = CircleShape,
                    )
                    REGIONS.forEach { code ->
                        FilterChip(
                            selected = !settings.regionIsAutomatic && settings.region.equals(code, ignoreCase = true),
                            onClick = { actions.onLocale(settings.language, code) },
                            label = { Text("${flagOf(code)} $code") },
                            shape = CircleShape,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Text("Language", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    LANGUAGES.forEach { (code, name) ->
                        FilterChip(
                            selected = settings.language.equals(code, ignoreCase = true),
                            onClick = { actions.onLocale(code, regionChoice) },
                            label = { Text(name) },
                            shape = CircleShape,
                        )
                    }
                }
                TextButton(onClick = { customLocale = !customLocale }) { Text(if (customLocale) "Hide custom" else "Other country or language…") }
                AnimatedVisibility(customLocale) {
                    var language by remember(settings.language) { mutableStateOf(settings.language) }
                    var region by remember(settings.region) { mutableStateOf(settings.region) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(language, { language = it.take(8) }, label = { Text("Language") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(region, { region = it.take(2) }, label = { Text("Country") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actions.onLocale(language, region) }) { Text("Apply") }
                    }
                }
            }
        }

        item(key = "playback") {
            SettingsGroup("Playback") {
                ToggleRow("Autoplay similar songs when the queue ends", settings.autoplay, actions.onAutoplay)
                ToggleRow("Even out volume between songs", settings.normalizeVolume, actions.onNormalizeVolume)
            }
        }

        item(key = "lyrics") {
            SettingsGroup("Lyrics") {
                ToggleRow("Show player controls on lyrics", settings.showLyricsControls, actions.onShowLyricsControls)
                ToggleRow("Show translations", settings.showLyricsTranslation, actions.onShowLyricsTranslation)
                ToggleRow("Show romanization", settings.showLyricsRomanization, actions.onShowLyricsRomanization)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync offset", style = MaterialTheme.typography.bodyLarge)
                        Text("${settings.lyricsSyncOffsetMs} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { actions.onLyricsOffset(settings.lyricsSyncOffsetMs - 250) }) { Text("−250") }
                    TextButton(onClick = { actions.onLyricsOffset(settings.lyricsSyncOffsetMs + 250) }) { Text("+250") }
                    if (settings.lyricsSyncOffsetMs != 0) {
                        TextButton(onClick = { actions.onLyricsOffset(0) }) { Text("Reset") }
                    }
                }
            }
        }

        item(key = "privacy") {
            SettingsGroup("Storage & privacy") {
                InfoRow("Downloaded songs", countLabel(downloadCount))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onClearRecentSearches, enabled = settings.recentSearches.isNotEmpty()) { Text("Clear") }
                }
            }
        }

        item(key = "about") {
            SettingsGroup("About") {
                InfoRow("Version", BuildConfig.VERSION_NAME)
                LinkRow("Get the latest Android build") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_DOWNLOAD_URL)))
                }
                LinkRow("Open-source notices") { showNotices = true }
            }
        }

        item(key = "diagnostics") {
            SettingsGroup("Advanced") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(pressedScale = 0.99f) { showDiagnostics = !showDiagnostics },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Playback diagnostics", style = MaterialTheme.typography.bodyLarge)
                        Text("Status: ${diagnostics.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (showDiagnostics) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
                AnimatedVisibility(showDiagnostics) { DiagnosticsDetails(diagnostics) }
            }
        }
    }

    if (showNotices) {
        val notices = remember {
            context.assets.open("licenses/third-party-notices.txt").bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { showNotices = false },
            title = { Text("Open-source notices") },
            text = {
                Text(
                    notices,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showNotices = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.gutter)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.sm))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.lg))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .animateContentSize()
                .padding(Spacing.lg),
            content = content,
        )
    }
}

@Composable
private fun ThemeOption(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(Radii.md))
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else colors.surfaceContainerHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(Radii.md))
            .pressable(pressedScale = 0.95f, haptic = true, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.primary else colors.onSurface)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(pressedScale = 0.99f, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(pressedScale = 0.99f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DiagnosticsDetails(diagnostics: PlaybackDiagnostics) {
    val expiry = diagnostics.expiresAtMs?.let { expiresAt ->
        val seconds = ((expiresAt - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)
        "${seconds / 60}m ${seconds % 60}s"
    }
    val format = diagnostics.mimeType?.let { mime ->
        buildString {
            append(mime)
            diagnostics.itag?.let { append(" · itag $it") }
            diagnostics.bitrateBps?.takeIf { it > 0 }?.let { append(" · ${it / 1_000} kbps") }
        }
    }
    val error = listOfNotNull(
        diagnostics.playerErrorCode?.let { "player $it" },
        diagnostics.httpStatusCode?.let { "HTTP $it" },
    ).joinToString(" · ")
    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        InfoRow("Route", diagnostics.route)
        InfoRow("YouTube client", diagnostics.clientProfile ?: "—")
        InfoRow("Format", format ?: "—")
        expiry?.let { InfoRow("Stream expiry", it) }
        if (error.isNotBlank()) InfoRow("Last error", error)
        diagnostics.recoveryAction?.let { InfoRow("Recovery", it) }
        if (diagnostics.events.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            Text("Recent events", style = MaterialTheme.typography.labelLarge)
            val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            diagnostics.events.take(8).forEach { event ->
                val details = listOfNotNull(
                    event.clientProfile,
                    event.itag?.let { "itag $it" },
                    event.httpStatusCode?.let { "HTTP $it" },
                    event.playerErrorCode?.let { "player $it" },
                ).joinToString(" · ")
                Text(
                    "${formatter.format(Date(event.timestampMs))}  ${event.event}" + if (details.isBlank()) "" else " · $details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Diagnostics stay on this device. Track IDs, stream URLs and tokens are never shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Regional-indicator emoji for a two-letter country code. */
private fun flagOf(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 'A'.code
    return code.uppercase().map { String(Character.toChars(base + it.code)) }.joinToString("")
}
