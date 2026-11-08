package com.moodify.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.moodify.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A Moodify build on GitHub Releases. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    /** A few lines of what changed, or empty. */
    val notes: List<String>,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val release: AppRelease?, val message: String) : UpdateState
}

/**
 * Tells people about new versions, since Moodify is installed from GitHub
 * rather than a store: checks the latest release at most once a day, then
 * downloads the APK and hands it to Android's installer. Same signing key,
 * so it installs over the old version and keeps everything.
 */
class AppUpdates(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("moodify_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var download: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The update to ask about now, unless you said "later" to this version recently. */
    private val _prompt = MutableStateFlow<AppRelease?>(null)
    val prompt: StateFlow<AppRelease?> = _prompt.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Looks for a newer release. Automatic checks run at most daily and never
     * on local builds (version 1); [force] (Settings › Check) always does.
     */
    fun check(force: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val now = System.currentTimeMillis()
        if (!force && (BuildConfig.VERSION_CODE <= 1 || now - prefs.getLong(KEY_CHECKED_AT, 0) < DAY_MS)) return
        _state.value = UpdateState.Checking
        scope.launch {
            val release = runCatching { fetchLatest() }.getOrElse {
                _state.value = if (force) UpdateState.Failed(null, "Couldn't check for updates. Try again later.") else UpdateState.Idle
                return@launch
            }
            prefs.edit().putLong(KEY_CHECKED_AT, now).apply()
            if (release == null || release.versionCode <= BuildConfig.VERSION_CODE) {
                _state.value = UpdateState.UpToDate
                return@launch
            }
            _state.value = UpdateState.Available(release)
            val snoozedCode = prefs.getInt(KEY_SNOOZED_CODE, -1)
            val snoozedUntil = prefs.getLong(KEY_SNOOZED_UNTIL, 0)
            if (force || snoozedCode != release.versionCode || now > snoozedUntil) _prompt.value = release
        }
    }

    /** "Later": don't ask about this version again for a few days. */
    fun snooze(release: AppRelease) {
        prefs.edit().putInt(KEY_SNOOZED_CODE, release.versionCode).putLong(KEY_SNOOZED_UNTIL, System.currentTimeMillis() + SNOOZE_MS).apply()
        _prompt.value = null
    }

    fun download(release: AppRelease) {
        if (download?.isActive == true) return
        _prompt.value = null
        _state.value = UpdateState.Downloading(release, 0f)
        download = scope.launch {
            val file = runCatching { fetchApk(release) }.getOrElse {
                _state.value = UpdateState.Failed(release, "The download didn't finish. Check your connection and try again.")
                return@launch
            }
            _state.value = UpdateState.ReadyToInstall(release, file)
            install()
        }
    }

    /**
     * Opens Android's installer. The first time, Android needs you to allow
     * Moodify to install apps; we open that switch and you tap Install again.
     * True when the installer opened.
     */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready.file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    fun dismiss() {
        _prompt.value = null
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    private suspend fun fetchLatest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Moodify-Android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub ${response.code}")
            parseRelease(JSONObject(response.body?.string().orEmpty()))
        }
    }

    private suspend fun fetchApk(release: AppRelease): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever keep the one being installed
        val target = File(dir, "Moodify-${release.versionName}.apk")
        val partial = File(dir, "${target.name}.part")
        http.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", "Moodify-Android").build()).execute().use { response ->
            if (!response.isSuccessful) error("download ${response.code}")
            val body = response.body ?: error("empty download")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = 0f
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val progress = total?.let { done.toFloat() / it } ?: 0f
                        if (progress - lastReported >= 0.01f) {
                            lastReported = progress
                            _state.value = UpdateState.Downloading(release, progress)
                        }
                    }
                }
            }
        }
        if (!partial.renameTo(target)) error("couldn't save the download")
        target
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/khageshpatil/Moodify-v2/releases/latest"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_SNOOZED_CODE = "snoozed_code"
        private const val KEY_SNOOZED_UNTIL = "snoozed_until"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val SNOOZE_MS = 3 * DAY_MS
        private val TAG = Regex("""android-v(\d+)\.(\d+)\.(\d+)""")

        /** A GitHub release as an update, or null when it isn't a Moodify Android build. */
        fun parseRelease(json: JSONObject): AppRelease? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val match = TAG.matchEntire(json.optString("tag_name")) ?: return null
            val (major, minor, build) = match.destructured
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return null
            return AppRelease(
                versionCode = build.toInt(),
                versionName = "$major.$minor.$build",
                apkUrl = apk,
                notes = notesFrom(json.optString("body")),
            )
        }

        /** Up to three readable lines from GitHub's generated notes ("* Title by @x in https://…"). */
        fun notesFrom(body: String): List<String> = body.lines()
            .map { it.trim() }
            .filter { it.startsWith("* ") || it.startsWith("- ") }
            .map { it.drop(2).replace(Regex("""\s+by @\S+.*$"""), "").replace(Regex("""\s+in https://\S+$"""), "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("@") && !it.contains("Full Changelog") }
            .take(3)
    }
}
