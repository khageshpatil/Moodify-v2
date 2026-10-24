package com.example.myapplication.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal InnerTube client that resolves YouTube audio stream URLs directly
 * from the Android device, bypassing the backend server entirely.
 *
 * This is the same architecture used by ViMusic and InnerTune: calling
 * the InnerTube /youtubei/v1/player endpoint directly from the device
 * avoids YouTube's bot-detection, which blocks requests from shared
 * datacenter IPs (e.g. Render, AWS, GCP).
 *
 * The IOS client returns pre-signed direct CDN URLs — no cipher decoding
 * is needed. The ANDROID_MUSIC client is the fallback.
 */
object InnerTubeClient {

    private const val TAG = "InnerTubeClient"

    // Internal YouTube API key (same key used by youtubei.js for IOS client)
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    private const val PLAYER_ENDPOINT =
        "https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false"

    private data class ClientConfig(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientNameHeaderId: String,
        val extraContextFields: String = "",
    )

    // IOS 20.11.6 — matches the version youtubei.js uses internally.
    // Returns direct CDN URLs (no signature cipher needed).
    private val IOS_CLIENT = ClientConfig(
        clientName = "IOS",
        clientVersion = "20.11.6",
        userAgent = "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X)",
        clientNameHeaderId = "5",
        extraContextFields = """,
            "deviceMake": "Apple",
            "deviceModel": "iPhone10,4",
            "osName": "iPhone",
            "osVersion": "16.7.7.20H330"
        """,
    )

    // ANDROID_MUSIC — fallback. Returns direct URLs on real Android device IPs.
    private val ANDROID_MUSIC_CLIENT = ClientConfig(
        clientName = "ANDROID_MUSIC",
        clientVersion = "7.27.52",
        userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip",
        clientNameHeaderId = "21",
        extraContextFields = """,
            "androidSdkVersion": 30
        """,
    )

    /**
     * Fetches a direct audio stream URL for [videoId] by calling InnerTube
     * directly from this device.
     *
     * Returns null if all client attempts fail (caller should fall back to the
     * server relay or show an error).
     */
    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        for (client in listOf(IOS_CLIENT, ANDROID_MUSIC_CLIENT)) {
            val url = tryClient(videoId, client)
            if (url != null) {
                android.util.Log.d(TAG, "Resolved stream for $videoId via ${client.clientName}")
                return@withContext url
            }
        }
        android.util.Log.w(TAG, "All InnerTube clients failed for $videoId")
        null
    }

    private fun tryClient(videoId: String, config: ClientConfig): String? {
        return try {
            val body = buildRequestBody(videoId, config)
            val connection = (URL(PLAYER_ENDPOINT).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", config.userAgent)
                setRequestProperty("X-Youtube-Client-Name", config.clientNameHeaderId)
                setRequestProperty("X-Youtube-Client-Version", config.clientVersion)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }

            if (connection.responseCode != 200) {
                android.util.Log.w(TAG, "${config.clientName}: HTTP ${connection.responseCode}")
                return null
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            parseAudioUrl(responseText, config.clientName)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "${config.clientName} error: ${e.message}")
            null
        }
    }

    private fun buildRequestBody(videoId: String, config: ClientConfig): String {
        return """{"context":{"client":{"clientName":"${config.clientName}","clientVersion":"${config.clientVersion}","hl":"en","gl":"US"${config.extraContextFields}}},"videoId":"$videoId"}"""
    }

    private fun parseAudioUrl(json: String, clientName: String): String? {
        return try {
            val root = JSONObject(json)

            val status = root.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") {
                val reason = root.optJSONObject("playabilityStatus")?.optString("reason") ?: ""
                android.util.Log.w(TAG, "$clientName: playability=$status reason=$reason")
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: run {
                android.util.Log.w(TAG, "$clientName: no streamingData")
                return null
            }

            // Collect audio formats that have a direct URL (no cipher)
            data class AudioFmt(val url: String, val isM4a: Boolean, val bitrate: Int)
            val candidates = mutableListOf<AudioFmt>()

            fun collectFrom(arr: JSONArray?) {
                arr ?: return
                for (i in 0 until arr.length()) {
                    val fmt = arr.getJSONObject(i)
                    val mimeType = fmt.optString("mimeType", "")
                    val url = fmt.optString("url", "")
                    // Skip: video formats, cipher-only formats, non-audio
                    if (url.isEmpty() || !mimeType.startsWith("audio/")) continue
                    val isM4a = mimeType.contains("mp4") || mimeType.contains("m4a")
                    candidates.add(AudioFmt(url, isM4a, fmt.optInt("bitrate", 0)))
                }
            }

            collectFrom(streamingData.optJSONArray("adaptiveFormats"))
            collectFrom(streamingData.optJSONArray("formats"))

            if (candidates.isEmpty()) {
                android.util.Log.w(TAG, "$clientName: no direct audio URLs (cipher formats only?)")
                return null
            }

            // Prefer M4A/AAC (widest ExoPlayer compat), then highest bitrate
            candidates.sortWith(
                compareByDescending<AudioFmt> { if (it.isM4a) 1 else 0 }
                    .thenByDescending { it.bitrate }
            )

            val best = candidates.first()
            android.util.Log.d(TAG, "$clientName: picked bitrate=${best.bitrate} isM4a=${best.isM4a}")
            best.url
        } catch (e: Exception) {
            android.util.Log.e(TAG, "$clientName: JSON parse error: ${e.message}")
            null
        }
    }
}

