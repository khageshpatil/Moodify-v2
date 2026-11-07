package com.moodify.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val language: String = "en",
    /** Effective catalogue country: the user's pick, or the detected one when [regionIsAutomatic]. */
    val region: String = "US",
    /** True when the user has not picked a country and [region] follows [RegionDetector]. */
    val regionIsAutomatic: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val recentSearches: List<String> = emptyList(),
    /** Keep playing similar songs when the queue ends. */
    val autoplay: Boolean = true,
    /** Even out loudness between tracks using YouTube's loudness data. */
    val normalizeVolume: Boolean = true,
    /** Ids of moods picked during first-run setup; empty until setup is done. */
    val favoriteMoods: List<String> = emptyList(),
    val onboardingDone: Boolean = false,
    val lyricsSyncOffsetMs: Int = 0,
    val showLyricsControls: Boolean = true,
    val showLyricsTranslation: Boolean = false,
    val showLyricsRomanization: Boolean = true,
    /** Stream the smallest audio format (downloads stay full quality). */
    val dataSaver: Boolean = false,
    /** Downloads wait for Wi-Fi (an unmetered network). */
    val downloadOnWifiOnly: Boolean = false,
    /** Plays aren't recorded in History, and "For you" stops learning. */
    val pauseHistory: Boolean = false,
    /** Skip silent stretches (long intros/outros, gaps in live recordings). */
    val skipSilence: Boolean = false,
    /** Liked songs download automatically on Wi-Fi. */
    val autoDownloadLiked: Boolean = false,
    /** Friends can see what you're playing (off = you look offline to them). */
    val shareListening: Boolean = true,
)

private val Context.moodifySettingsDataStore by preferencesDataStore(name = "moodify_settings")

/** Durable app configuration boundary; catalog clients read this rather than UI preferences. */
class SettingsStore(private val context: Context) {
    /** Country the device is in; read once per process (network/SIM rarely change mid-session). */
    val detectedRegion: String by lazy { RegionDetector.detect(context) }

    val settings: Flow<AppSettings> = context.moodifySettingsDataStore.data.map { values ->
        val pickedRegion = values[REGION]?.trim()?.uppercase()?.takeIf { it.length == 2 }
        AppSettings(
            language = values[LANGUAGE].orEmpty().ifBlank { "en" },
            region = pickedRegion ?: detectedRegion,
            regionIsAutomatic = pickedRegion == null,
            // Older builds stored a dark-mode boolean; honour it until the
            // user picks a mode in the new three-way setting.
            themeMode = values[THEME_MODE]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: when (values[DARK_THEME]?.toBooleanStrictOrNull()) {
                    false -> ThemeMode.LIGHT
                    else -> ThemeMode.DARK
                },
            recentSearches = values[RECENT_SEARCHES].orEmpty().split('\n').filter(String::isNotBlank),
            autoplay = values[AUTOPLAY]?.toBooleanStrictOrNull() ?: true,
            normalizeVolume = values[NORMALIZE_VOLUME]?.toBooleanStrictOrNull() ?: true,
            favoriteMoods = values[FAVORITE_MOODS].orEmpty().split(',').filter(String::isNotBlank),
            // Existing users (who already have history in settings) skip onboarding.
            onboardingDone = values[ONBOARDING_DONE]?.toBooleanStrictOrNull()
                ?: (values[THEME_MODE] != null || values[DARK_THEME] != null || values[RECENT_SEARCHES] != null),
            lyricsSyncOffsetMs = values[LYRICS_SYNC_OFFSET]?.toIntOrNull()?.coerceIn(-5000, 5000) ?: 0,
            showLyricsControls = values[LYRICS_CONTROLS]?.toBooleanStrictOrNull() ?: true,
            showLyricsTranslation = values[LYRICS_TRANSLATION]?.toBooleanStrictOrNull() ?: false,
            showLyricsRomanization = values[LYRICS_ROMANIZATION]?.toBooleanStrictOrNull() ?: true,
            dataSaver = values[DATA_SAVER]?.toBooleanStrictOrNull() ?: false,
            downloadOnWifiOnly = values[WIFI_ONLY_DOWNLOADS]?.toBooleanStrictOrNull() ?: false,
            pauseHistory = values[PAUSE_HISTORY]?.toBooleanStrictOrNull() ?: false,
            skipSilence = values[SKIP_SILENCE]?.toBooleanStrictOrNull() ?: false,
            autoDownloadLiked = values[AUTO_DOWNLOAD_LIKED]?.toBooleanStrictOrNull() ?: false,
            shareListening = values[SHARE_LISTENING]?.toBooleanStrictOrNull() ?: true,
        )
    }

    /** [region] blank (or [AUTOMATIC_REGION]) means "follow the device's country". */
    suspend fun setLocale(language: String, region: String) {
        context.moodifySettingsDataStore.edit { values ->
            values[LANGUAGE] = language.lowercase().take(8).ifBlank { "en" }
            putRegion(values, region)
        }
    }

    /**
     * One-time fix for installs made before automatic regions existed: the old
     * default (and the onboarding pre-selection) copied the phone's language
     * country, e.g. "US" on an English (United States) phone in India. Such a
     * value was almost never a deliberate choice, so hand it back to detection.
     */
    suspend fun migrateRegionToAutomatic() {
        context.moodifySettingsDataStore.edit { values ->
            if (values[REGION_AUTO_MIGRATED] == "true") return@edit
            val stored = values[REGION]
            if (stored == null || stored.equals(java.util.Locale.getDefault().country, ignoreCase = true)) {
                values.remove(REGION)
            }
            values[REGION_AUTO_MIGRATED] = "true"
        }
    }

    private fun putRegion(values: androidx.datastore.preferences.core.MutablePreferences, region: String) {
        val code = region.trim().uppercase()
        if (code.isBlank() || code == AUTOMATIC_REGION) values.remove(REGION) else values[REGION] = code.take(2)
    }

    suspend fun setAutoplay(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[AUTOPLAY] = enabled.toString() }
    }

    suspend fun setNormalizeVolume(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[NORMALIZE_VOLUME] = enabled.toString() }
    }

    suspend fun completeOnboarding(language: String, region: String, moods: List<String>) {
        context.moodifySettingsDataStore.edit { values ->
            values[LANGUAGE] = language.lowercase().take(8).ifBlank { "en" }
            putRegion(values, region)
            values[FAVORITE_MOODS] = moods.joinToString(",")
            values[ONBOARDING_DONE] = "true"
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.moodifySettingsDataStore.edit { values -> values[THEME_MODE] = mode.name }
    }

    /** Moves [query] to the front of recent searches, keeping at most [MAX_RECENT_SEARCHES]. */
    suspend fun addRecentSearch(query: String) {
        val normalized = query.trim().replace('\n', ' ').take(120)
        if (normalized.isBlank()) return
        context.moodifySettingsDataStore.edit { values ->
            val existing = values[RECENT_SEARCHES].orEmpty().split('\n').filter(String::isNotBlank)
            val updated = (listOf(normalized) + existing.filterNot { it.equals(normalized, ignoreCase = true) })
                .take(MAX_RECENT_SEARCHES)
            values[RECENT_SEARCHES] = updated.joinToString("\n")
        }
    }

    suspend fun removeRecentSearch(query: String) {
        context.moodifySettingsDataStore.edit { values ->
            val updated = values[RECENT_SEARCHES].orEmpty().split('\n')
                .filter { it.isNotBlank() && it != query }
            values[RECENT_SEARCHES] = updated.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() {
        context.moodifySettingsDataStore.edit { values -> values.remove(RECENT_SEARCHES) }
    }

    suspend fun setLyricsSyncOffset(offsetMs: Int) {
        context.moodifySettingsDataStore.edit { values -> values[LYRICS_SYNC_OFFSET] = offsetMs.coerceIn(-5000, 5000).toString() }
    }

    suspend fun setShowLyricsControls(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[LYRICS_CONTROLS] = enabled.toString() }
    }

    suspend fun setShowLyricsTranslation(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[LYRICS_TRANSLATION] = enabled.toString() }
    }

    suspend fun setShowLyricsRomanization(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[LYRICS_ROMANIZATION] = enabled.toString() }
    }

    suspend fun setDataSaver(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[DATA_SAVER] = enabled.toString() }
    }

    suspend fun setDownloadOnWifiOnly(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[WIFI_ONLY_DOWNLOADS] = enabled.toString() }
    }

    suspend fun setPauseHistory(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[PAUSE_HISTORY] = enabled.toString() }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[SKIP_SILENCE] = enabled.toString() }
    }

    suspend fun setAutoDownloadLiked(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[AUTO_DOWNLOAD_LIKED] = enabled.toString() }
    }

    suspend fun setShareListening(enabled: Boolean) {
        context.moodifySettingsDataStore.edit { values -> values[SHARE_LISTENING] = enabled.toString() }
    }

    suspend fun getVisitorData(): String? = context.moodifySettingsDataStore.data
        .first()[VISITOR_DATA]
        ?.trim()
        ?.takeIf(String::isNotBlank)

    suspend fun setVisitorData(visitorData: String) {
        val normalized = visitorData.trim()
        if (normalized.isBlank()) return
        context.moodifySettingsDataStore.edit { values -> values[VISITOR_DATA] = normalized }
    }

    companion object {
        /** Pass as the region to follow the device's country instead of a fixed one. */
        const val AUTOMATIC_REGION = "AUTO"
        private val LANGUAGE = stringPreferencesKey("language")
        private val REGION = stringPreferencesKey("region")
        private val REGION_AUTO_MIGRATED = stringPreferencesKey("region_auto_migrated")
        val DARK_THEME = stringPreferencesKey("dark_theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        const val MAX_RECENT_SEARCHES = 10
        val AUTOPLAY = stringPreferencesKey("autoplay")
        val NORMALIZE_VOLUME = stringPreferencesKey("normalize_volume")
        val FAVORITE_MOODS = stringPreferencesKey("favorite_moods")
        val ONBOARDING_DONE = stringPreferencesKey("onboarding_done")
        val VISITOR_DATA = stringPreferencesKey("youtube_visitor_data")
        val LYRICS_SYNC_OFFSET = stringPreferencesKey("lyrics_sync_offset_ms")
        val LYRICS_CONTROLS = stringPreferencesKey("lyrics_show_controls")
        val LYRICS_TRANSLATION = stringPreferencesKey("lyrics_show_translation")
        val LYRICS_ROMANIZATION = stringPreferencesKey("lyrics_show_romanization")
        private val DATA_SAVER = stringPreferencesKey("data_saver")
        private val WIFI_ONLY_DOWNLOADS = stringPreferencesKey("download_wifi_only")
        private val PAUSE_HISTORY = stringPreferencesKey("pause_history")
        private val SKIP_SILENCE = stringPreferencesKey("skip_silence")
        private val AUTO_DOWNLOAD_LIKED = stringPreferencesKey("auto_download_liked")
        private val SHARE_LISTENING = stringPreferencesKey("share_listening")
    }
}
