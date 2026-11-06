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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
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

/** Languages YouTube Music serves its catalogue in (code to native name). */
private val LANGUAGES = listOf(
    "en" to "English", "hi" to "हिन्दी", "bn" to "বাংলা", "ta" to "தமிழ்", "te" to "తెలుగు", "mr" to "मराठी",
    "gu" to "ગુજરાતી", "kn" to "ಕನ್ನಡ", "ml" to "മലയാളം", "pa" to "ਪੰਜਾਬੀ", "ur" to "اردو",
    "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português", "it" to "Italiano",
    "ja" to "日本語", "ko" to "한국어", "id" to "Bahasa Indonesia", "tr" to "Türkçe", "ar" to "العربية", "ru" to "Русский",
)

/** Space used on the device, for Settings › Storage. */
data class StorageUsage(
    val downloadBytes: Long = 0L,
    val audioCacheBytes: Long = 0L,
    val lyricsCount: Int = 0,
)

// Binary units, matching how the cache limit (150 MB = 150 × 1024²) is set.
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun countryName(code: String): String =
    Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }

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
    val onDataSaver: (Boolean) -> Unit = {},
    val onSkipSilence: (Boolean) -> Unit = {},
    val onAutoDownloadLiked: (Boolean) -> Unit = {},
    val onDownloadOnWifiOnly: (Boolean) -> Unit = {},
    val onPauseHistory: (Boolean) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onRefreshStorage: () -> Unit = {},
    val onClearAudioCache: () -> Unit = {},
    val onClearLyricsCache: () -> Unit = {},
    val onClearCatalogCache: () -> Unit = {},
    val onExportBackup: (android.net.Uri) -> Unit = {},
    val onImportBackup: (android.net.Uri) -> Unit = {},
)

/** The last crash report kept on the device, if any. */
private fun lastCrash(context: android.content.Context): String? = com.moodify.app.data.CrashLog.read(context)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    diagnostics: PlaybackDiagnostics,
    downloadCount: Int,
    bottomPadding: Dp,
    actions: SettingsActions,
    storage: StorageUsage = StorageUsage(),
) {
    val context = LocalContext.current
    var showNotices by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { actions.onRefreshStorage() }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(actions.onExportBackup) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(actions.onImportBackup) }

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
                Spacer(Modifier.height(Spacing.sm))
                PickerRow(
                    label = "Country",
                    value = buildString {
                        if (settings.regionIsAutomatic) append("Automatic · ")
                        append(flagOf(settings.region)).append(' ').append(countryName(settings.region))
                    },
                    onClick = { picker = "country" },
                )
                PickerRow(
                    label = "Language",
                    value = LANGUAGES.firstOrNull { it.first.equals(settings.language, ignoreCase = true) }?.second ?: settings.language,
                    onClick = { picker = "language" },
                )
            }
        }

        item(key = "playback") {
            SettingsGroup("Playback") {
                ToggleRow("Autoplay similar songs when the queue ends", settings.autoplay, actions.onAutoplay)
                ToggleRow("Even out volume between songs", settings.normalizeVolume, actions.onNormalizeVolume)
                ToggleRow(
                    "Data saver",
                    settings.dataSaver,
                    actions.onDataSaver,
                    description = "Streams at lower quality to use less mobile data. Downloads stay high quality.",
                )
                ToggleRow(
                    "Skip silence",
                    settings.skipSilence,
                    actions.onSkipSilence,
                    description = "Jumps over silent stretches, like long gaps between live tracks.",
                )
            }
        }

        item(key = "downloads") {
            SettingsGroup("Downloads") {
                ToggleRow(
                    "Download on Wi-Fi only",
                    settings.downloadOnWifiOnly,
                    actions.onDownloadOnWifiOnly,
                    description = "New downloads wait until you're on Wi-Fi.",
                )
                ToggleRow(
                    "Download liked songs",
                    settings.autoDownloadLiked,
                    actions.onAutoDownloadLiked,
                    description = "Songs you like download automatically on Wi-Fi, lyrics included.",
                )
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
            SettingsGroup("Privacy") {
                ToggleRow(
                    "Pause listening history",
                    settings.pauseHistory,
                    actions.onPauseHistory,
                    description = "Songs you play aren't added to History, and For you stops learning from them.",
                )
                ActionRow("Listening history", actionLabel = "Clear") { confirmClearHistory = true }
                ActionRow("Recent searches", actionLabel = "Clear", enabled = settings.recentSearches.isNotEmpty(), onClick = actions.onClearRecentSearches)
            }
        }

        item(key = "backup") {
            SettingsGroup("Back up & restore") {
                Text(
                    "Save your playlists, liked songs and saved albums to a file, then restore them on another phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow("Back up library", actionLabel = "Save file") {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("moodify-library-$day.json")
                }
                ActionRow("Restore from a backup", actionLabel = "Choose file") {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            }
        }

        item(key = "storage") {
            SettingsGroup("Storage") {
                InfoRow("Downloads", "${countLabel(downloadCount)} · ${formatBytes(storage.downloadBytes)}")
                ActionRow("Song cache", value = formatBytes(storage.audioCacheBytes), actionLabel = "Clear", enabled = storage.audioCacheBytes > 0, onClick = actions.onClearAudioCache)
                ActionRow("Saved lyrics", value = if (storage.lyricsCount == 1) "1 song" else "${storage.lyricsCount} songs", actionLabel = "Clear", enabled = storage.lyricsCount > 0, onClick = actions.onClearLyricsCache)
                ActionRow("Offline catalogue", value = "Home, charts and moods", actionLabel = "Clear", onClick = actions.onClearCatalogCache)
                Text(
                    "The song cache makes replays instant and is trimmed automatically at 150 MB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                var crash by remember { mutableStateOf(lastCrash(context)) }
                crash?.let { report ->
                    Spacer(Modifier.height(Spacing.md))
                    Text("Last crash", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        report.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                            context.startActivity(Intent.createChooser(send, "Share crash report"))
                        }) { Text("Share report") }
                        TextButton(onClick = {
                            com.moodify.app.data.CrashLog.clear(context)
                            crash = null
                        }) { Text("Clear") }
                    }
                    Text(
                        "Kept only on this phone. Nothing is sent unless you share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    when (picker) {
        "country" -> OptionPickerSheet(
            title = "Country",
            options = remember {
                listOf(SettingsStore.AUTOMATIC_REGION to "📍 Automatic (use where you are)") +
                    Locale.getISOCountries().map { it to "${flagOf(it)}  ${countryName(it)}" }.sortedBy { it.second.drop(4) }
            },
            selected = if (settings.regionIsAutomatic) SettingsStore.AUTOMATIC_REGION else settings.region,
            onSelect = { code -> actions.onLocale(settings.language, code); picker = null },
            onDismiss = { picker = null },
        )
        "language" -> OptionPickerSheet(
            title = "Language",
            options = LANGUAGES,
            selected = settings.language,
            onSelect = { code ->
                val regionChoice = if (settings.regionIsAutomatic) SettingsStore.AUTOMATIC_REGION else settings.region
                actions.onLocale(code, regionChoice)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear listening history?") },
            text = { Text("History and the play counts behind For you will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { actions.onClearHistory(); confirmClearHistory = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } },
        )
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(pressedScale = 0.99f, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.md)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A setting that shows its value and opens a picker. */
@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(pressedScale = 0.99f, onClick = onClick)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionRow(label: String, value: String? = null, actionLabel: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick, enabled = enabled) { Text(actionLabel) }
    }
}

/** Searchable single-choice list in a bottom sheet (countries, languages). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OptionPickerSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(options, query) {
        // The current choice sits near the top so it's easy to see and keep.
        if (query.isBlank()) {
            val current = options.filter { it.first.equals(selected, ignoreCase = true) }
            val first = options.take(1).filterNot { it in current }
            if (options.firstOrNull() in current) options else first + current + (options - current.toSet() - first.toSet())
        } else options.filter { (code, name) ->
            name.contains(query.trim(), ignoreCase = true) || code.equals(query.trim(), ignoreCase = true)
        }
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                singleLine = true,
                shape = RoundedCornerShape(Radii.md),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(shown.size) { index ->
                val (code, name) = shown[index]
                val isSelected = code.equals(selected, ignoreCase = true)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .pressable(pressedScale = 0.99f) { onSelect(code) }
                        .semantics { this.selected = isSelected; role = Role.RadioButton }
                        .padding(horizontal = Spacing.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
            item { Spacer(Modifier.height(Spacing.xl)) }
        }
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
