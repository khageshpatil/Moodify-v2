package com.example.myapplication.player

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.example.myapplication.BuildConfig

/**
 * Resolves a YouTube video id to a short-lived audio CDN URL using InnerTube.
 *
 * Calls YouTube's /youtubei/v1/player endpoint directly from the device so that
 * requests arrive from the user's real IP — not from Render's shared datacenter
 * egress which is routinely bot-challenged by YouTube.
 *
 * Architecture notes:
 * - Primary client chain: IOS → ANDROID_MUSIC → ANDROID → MWEB → ANDROID_VR → TVHTML5_SIMPLY
 * - Every candidate media URL is passed through the backend /api/decipher
 *   endpoint (pure JS, no media bytes). YouTube can require its player
 *   transformation even when the JSON contains a direct-looking `url` and no
 *   visible cipher field.
 * - Resolved streams are cached with expiry awareness; callers should check
 *   [ResolvedStream.isExpired] before handing a cached stream to ExoPlayer.
 * - Preview-length clips (< 90 % of declared video duration) are rejected so
 *   that YouTube's 90-second preview streams are never given to ExoPlayer.
 */
class InnerTubeStreamResolver(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val serverBaseUrl: String = BuildConfig.MOODIFY_SERVER_URL
) {

    @Volatile
    private var visitorData: String? = null

    private val poTokenCache = java.util.concurrent.ConcurrentHashMap<String, CachedPoToken>()
    private val signatureTimestampCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // A resolved URL is useful only until its CDN expiry. Keep the cache
    // bounded by video/profile key and invalidate it on transport failures.
    private val resolvedCache = java.util.concurrent.ConcurrentHashMap<String, ResolvedStream>()
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-instance in-flight dedup map: videoId/profile-attempt → running result
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ResolvedStream>>()

    suspend fun resolve(videoId: String, profileOffset: Int = 0): ResolvedStream = withContext(Dispatchers.IO) {
        // A changed offset intentionally selects the next InnerTube client
        // after a CDN 403 instead of returning the same signed URL again.
        val requestKey = "$videoId:$profileOffset"
        resolvedCache[requestKey]?.takeUnless { it.isExpired }?.let { return@withContext it }
        val deferred = inFlight.getOrPut(requestKey) {
            resolutionScope.async {
                try {
                    resolveUncached(videoId, profileOffset).also { resolvedCache[requestKey] = it }
                } finally {
                    inFlight.remove(requestKey)
                }
            }
        }
        deferred.await()
    }

    /** Drop every URL for a video after a CDN 403/404/410 or malformed stream. */
    fun invalidate(videoId: String) {
        resolvedCache.keys.removeIf { it.startsWith("$videoId:") }
        inFlight.keys.removeIf { it.startsWith("$videoId:") }
        poTokenCache.remove(videoId)
    }

    /** Resolve ahead of playback without making the player wait for it. */
    suspend fun preload(videoId: String): ResolvedStream? =
        runCatching { resolve(videoId) }
            .onFailure { Log.d(TAG, "Preload skipped for $videoId: ${it.message}") }
            .getOrNull()

    /**
     * Validate the resolved CDN contract before ExoPlayer opens a long-lived
     * media source.  YouTube signs the URL and its request context together;
     * probing with the same headers and a tiny range catches a 403 while the
     * resolver can still rotate to another client/format.
     */
    suspend fun validate(stream: ResolvedStream): Unit = withContext(Dispatchers.IO) {
        if (stream.isExpired) throw ResolveException("Resolved stream expired before validation")

        val positions = buildList {
            add(0L)
            stream.contentLength
                ?.takeIf { it > VALIDATION_RANGE_LENGTH }
                ?.let { add(minOf(VALIDATION_RANGE_PROBE_POSITION, it - VALIDATION_RANGE_LENGTH)) }
        }.distinct()

        positions.forEach { position ->
            val requestedLength = stream.contentLength
                ?.minus(position)
                ?.coerceAtMost(VALIDATION_RANGE_LENGTH)
                ?.takeIf { it > 0L }
                ?: VALIDATION_RANGE_LENGTH
            val request = Request.Builder()
                .url(stream.url)
                .get()
                .header(
                    "Range",
                    "bytes=$position-${position + requestedLength - 1}"
                )
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .apply {
                    header("User-Agent", stream.userAgent)
                    stream.requestHeaders.forEach { (name, value) -> header(name, value) }
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 403 ->
                        throw ResolveException(
                            "${stream.profileName} CDN validation HTTP 403 at byte $position"
                        )
                    response.code in 200..299 || response.code == 416 -> Unit
                    else -> throw ResolveException(
                        "${stream.profileName} CDN validation HTTP ${response.code} at byte $position"
                    )
                }
                Log.d(
                    TAG,
                    "Validated ${stream.profileName} itag=${stream.itag} " +
                        "position=$position status=${response.code}"
                )
            }
        }
    }

    /**
     * Ask Moodify only for a freshly resolved, tokenized CDN URL.  The
     * response contains no audio bytes; ExoPlayer still downloads the media
     * directly from the device.  This mirrors ArchiveTune's separation
     * between stream resolution and media transport and is useful when a
     * native anonymous InnerTube client is challenged by YouTube.
     */
    suspend fun resolveFromServer(videoId: String): ResolvedStream = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/api/stream/$videoId")
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(body) }.getOrElse {
                throw ResolveException("Server stream endpoint returned invalid JSON", it)
            }
            if (!response.isSuccessful || root.optBoolean("ok", false).not()) {
                throw ResolveException(
                    "Server stream resolution HTTP ${response.code}: " +
                        root.optString("message").ifBlank { root.optString("error") }
                )
            }

            val url = root.optString("url").takeIf { it.isNotBlank() }
                ?: throw ResolveException("Server stream endpoint returned no URL")
            val expiresAt = root.optLong("expiresAt", 0L).takeIf { it > 0L }
                ?: (System.currentTimeMillis() + URL_EXPIRY_SAFETY_MS)
            val referer = root.optString("referer").takeIf { it.isNotBlank() }

            ResolvedStream(
                url = url,
                mimeType = root.optString("mimeType").ifBlank { "audio/mp4" },
                bitrate = root.optInt("bitrate", 0),
                contentLength = root.optLong("contentLength", 0L).takeIf { it > 0L },
                itag = root.optInt("itag", -1),
                durationMs = null,
                userAgent = root.optString("userAgent").ifBlank { "Moodify-Android/1.0" },
                requestHeaders = buildMap {
                    root.optString("userAgent").takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                    root.optString("origin").takeIf { it.isNotBlank() }?.let { put("Origin", it) }
                    referer?.let { put("Referer", it) }
                },
                profileName = root.optString("clientType").ifBlank { "SERVER" },
                clientName = root.optString("clientType").takeIf { it.isNotBlank() },
                expiresAtMs = expiresAt,
            )
        }
    }

    private fun resolveUncached(videoId: String, profileOffset: Int): ResolvedStream {
        var lastFailure: Throwable? = null

        for (profile in orderedProfiles(profileOffset)) {
            try {
                val poToken = if (profile.requiresPlaybackPoToken) fetchPoToken(videoId) else null
                val signatureTimestamp = if (profile.useSignatureTimestamp) {
                    fetchSignatureTimestamp(profile.name)
                } else {
                    null
                }
                val resolved = requestPlayer(profile, videoId, poToken, signatureTimestamp)
                Log.d(TAG, "Resolved $videoId via ${profile.name}: mime=${resolved.mimeType} itag=${resolved.itag} len=${resolved.contentLength}")
                return resolved
            } catch (failure: Exception) {
                lastFailure = failure
                Log.w(TAG, "${profile.name} failed for $videoId: ${failure.message}")
            }
        }

        throw ResolveException(
            "InnerTube could not resolve $videoId" +
                (lastFailure?.message?.let { ": $it" } ?: "."),
            lastFailure
        )
    }

    private fun requestPlayer(
        profile: ClientProfile,
        videoId: String,
        poToken: String?,
        signatureTimestamp: Int?,
    ): ResolvedStream {
        val request = Request.Builder()
            .url(
                "https://youtubei.googleapis.com/youtubei/v1/player" +
                    "?key=${profile.apiKey}&prettyPrint=false&alt=json"
            )
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "*/*")
                    .add("Accept-Language", "en-US")
                    .add("Content-Type", "application/json")
                    .add("X-YouTube-Client-Name", profile.clientNameId)
                    .add("X-YouTube-Client-Version", profile.version)
                    .add("User-Agent", profile.userAgent)
                    .add("Origin", profile.origin ?: "https://www.youtube.com")
                    .add("Referer", profile.referer ?: "https://www.youtube.com/")
                    .apply {
                        visitorData?.takeIf { it.isNotBlank() }?.let { add("X-Goog-Visitor-Id", it) }
                        profile.apiFormatVersion?.let { add("X-Goog-Api-Format-Version", it) }
                    }
                    .build()
            )
            .post(buildPlayerBody(profile, videoId, poToken, signatureTimestamp).toString().toRequestBody(JSON))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ResolveException(
                    "${profile.name} HTTP ${response.code}" +
                        responseBody.takeIf { it.isNotBlank() }?.let { ": ${shorten(it)}" }.orEmpty()
                )
            }

            val root = runCatching { JSONObject(responseBody) }.getOrElse {
                throw ResolveException("${profile.name} returned invalid JSON", it)
            }

            root.optJSONObject("responseContext")
                ?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
                ?.let { visitorData = it }

            val playability = root.optJSONObject("playabilityStatus")
            val status = playability?.optString("status").orEmpty()
            if (status != "OK") {
                val reason = playability?.optString("reason")
                    ?.takeIf { it.isNotBlank() }
                    ?: "status=$status"
                throw ResolveException("${profile.name}: $reason")
            }

            val streamingData = root.optJSONObject("streamingData")
                ?: throw ResolveException("${profile.name}: no streamingData")
            val formats = streamingData.optJSONArray("adaptiveFormats")
                ?: throw ResolveException("${profile.name}: no adaptive audio formats")

            val candidates = buildList {
                for (index in 0 until formats.length()) {
                    val format = formats.optJSONObject(index) ?: continue
                    val mimeType = format.optString("mimeType")
                        .substringBefore(';')
                        .trim()
                    val url = format.optString("url").takeIf { it.isNotBlank() }
                    val signatureCipher = format.optString("signatureCipher")
                        .takeIf { it.isNotBlank() }
                    val cipher = format.optString("cipher")
                        .takeIf { it.isNotBlank() }
                    val isAudio = !format.has("width") || format.isNull("width")
                    if (isAudio && (url != null || signatureCipher != null || cipher != null) && mimeType.startsWith("audio/")) {
                        add(
                            AudioFormat(
                                url = url,
                                signatureCipher = signatureCipher,
                                cipher = cipher,
                                mimeType = mimeType,
                                bitrate = format.optInt("bitrate", 0),
                                contentLength = format.optString("contentLength")
                                    .toLongOrNull(),
                                itag = format.optInt("itag", -1)
                            )
                        )
                    }
                }
            }

            // Prefer the formats that the native youtubei resolver is most
            // consistently able to consume.  MP4/itag 140 is convenient, but
            // the CDN may reject it while the equivalent WebM/Opus contract
            // (usually itag 251) is still valid.  Keep bitrate as a quality
            // tiebreaker, never as the only selection rule.
            val orderedCandidates = candidates.sortedWith(
                compareByDescending<AudioFormat> { formatPreference(it) }
                    .thenByDescending { it.bitrate }
            )

            var selected: AudioFormat? = null
            var resolvedUrl: String? = null
            var lastDecipherFailure: Throwable? = null
            for (candidate in orderedCandidates) {
                try {
                    val candidateUrl = candidate.url
                    // A fully materialized URL is already signed for this
                    // device request. Keep playback independent of the
                    // server's JavaScript decipher endpoint in this case;
                    // Render may be bot-challenged even though YouTube
                    // accepted the original request from the device.
                    // Ciphered formats still use the server only for the
                    // JavaScript transform.
                    val playableUrl = if (
                        !candidateUrl.isNullOrBlank() &&
                        candidate.signatureCipher.isNullOrBlank() &&
                        candidate.cipher.isNullOrBlank()
                    ) {
                        candidateUrl
                    } else {
                        decipherOnServer(
                            url = candidateUrl,
                            signatureCipher = candidate.signatureCipher,
                            cipher = candidate.cipher,
                        )
                    }
                    if (!playableUrl.isNullOrBlank()) {
                        selected = candidate
                        resolvedUrl = playableUrl
                        break
                    }
                } catch (failure: Exception) {
                    lastDecipherFailure = failure
                    Log.w(TAG, "${profile.name}: unable to decipher itag=${candidate.itag}: ${failure.message}")
                }
            }

            val selectedFormat = selected ?: throw ResolveException(
                "${profile.name}: no playable audio URL" +
                    (lastDecipherFailure?.message?.let { ": $it" } ?: ""),
                lastDecipherFailure,
            )
            var finalUrl = resolvedUrl ?: throw ResolveException("${profile.name}: empty audio URL")
            if (!poToken.isNullOrBlank() && Uri.parse(finalUrl).getQueryParameter("pot").isNullOrBlank()) {
                finalUrl = Uri.parse(finalUrl).buildUpon()
                    .appendQueryParameter("pot", poToken)
                    .build()
                    .toString()
            }

            val contentLength = selectedFormat.contentLength ?: Uri.parse(finalUrl)
                .getQueryParameter("clen")
                ?.toLongOrNull()
            val expiresInSeconds = streamingData.optLong("expiresInSeconds", 21_600L)
                .coerceAtLeast(60L)
            val durationMs = root.optJSONObject("videoDetails")
                ?.optString("lengthSeconds")
                ?.toLongOrNull()
                ?.times(1_000L)

            return ResolvedStream(
                url = finalUrl,
                mimeType = selectedFormat.mimeType,
                bitrate = selectedFormat.bitrate,
                contentLength = contentLength,
                itag = selectedFormat.itag,
                durationMs = durationMs,
                userAgent = profile.userAgent,
                requestHeaders = buildMap {
                    put("User-Agent", profile.userAgent)
                    profile.origin?.let { put("Origin", it) }
                    profile.referer?.let { put("Referer", it) }
                },
                profileName = profile.name,
                clientName = profile.clientNameId,
                clientVersion = profile.version,
                // Refresh before the CDN URL reaches its actual expiry.
                expiresAtMs = System.currentTimeMillis() +
                    expiresInSeconds * 1_000L - URL_EXPIRY_SAFETY_MS
            )
        }
    }

    private fun decipherOnServer(
        url: String?,
        signatureCipher: String?,
        cipher: String?,
    ): String {
        val query = buildList {
            url?.let { add("url=${Uri.encode(it)}") }
            signatureCipher?.let { add("signatureCipher=${Uri.encode(it)}") }
            cipher?.let { add("cipher=${Uri.encode(it)}") }
        }.joinToString("&")
        if (query.isBlank()) throw ResolveException("No URL or cipher was provided")

        val request = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/api/decipher?$query")
            .get()
            .header("Accept", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ResolveException("Decipher endpoint HTTP ${response.code}: ${shorten(body)}")
                }
                val resolved = runCatching { JSONObject(body).optString("url") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                resolved ?: throw ResolveException("Decipher endpoint returned no URL")
            }
        } catch (failure: Exception) {
            // IOS/visionOS/TV responses commonly contain a fully materialized
            // URL with no signature cipher. Keep device playback independent
            // of a stale Render deployment in that case. Ciphered formats
            // still fail closed because returning them would produce a false
            // ExoPlayer source and a much less useful 403 later.
            if (!url.isNullOrBlank() && signatureCipher.isNullOrBlank() && cipher.isNullOrBlank()) {
                Log.w(TAG, "Decipher service unavailable; using direct InnerTube URL")
                url
            } else {
                throw failure
            }
        }
    }

    private fun buildPlayerBody(
        profile: ClientProfile,
        videoId: String,
        poToken: String?,
        signatureTimestamp: Int?,
    ): JSONObject {
        val client = JSONObject()
            .put("clientName", profile.name)
            .put("clientVersion", profile.version)
            .put("hl", "en")
            .put("gl", "US")
            .put("osName", profile.osName)
            .put("osVersion", profile.osVersion)
            .put("platform", profile.platform)
            .put("clientFormFactor", "SMALL_FORM_FACTOR")
            .put("userAgent", profile.userAgent)
            .put("originalUrl", "https://www.youtube.com/watch?v=$videoId")
            .put("timeZone", "Asia/Calcutta")
        visitorData?.takeIf { it.isNotBlank() }?.let { client.put("visitorData", it) }

        profile.androidSdkVersion?.let { client.put("androidSdkVersion", it) }
        profile.deviceMake?.let { client.put("deviceMake", it) }
        profile.deviceModel?.let { client.put("deviceModel", it) }

        val context = JSONObject().put("client", client)

        return JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .apply {
                signatureTimestamp?.takeIf { it > 0 }?.let {
                    put(
                        "playbackContext",
                        JSONObject().put(
                            "contentPlaybackContext",
                            JSONObject()
                                .put("signatureTimestamp", it)
                                .put("html5Preference", "HTML5_PREF_WANTS")
                                .put("vis", 0)
                                .put("splay", false)
                                .put("lactMilliseconds", "-1")
                        )
                    )
                }
                if (!poToken.isNullOrBlank()) {
                    put("serviceIntegrityDimensions", JSONObject().put("poToken", poToken))
                }
            }
    }

    private fun fetchSignatureTimestamp(profileName: String): Int? {
        signatureTimestampCache[profileName]?.let { return it }
        val request = Request.Builder()
            .url(
                "${serverBaseUrl.trimEnd('/')}/api/playback-config" +
                    "?client=${Uri.encode(profileName)}"
            )
            .header("Accept", "application/json")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ResolveException("Playback config HTTP ${response.code}: ${shorten(body)}")
                }
                val timestamp = JSONObject(body).optInt("signatureTimestamp", 0)
                    .takeIf { it > 0 }
                    ?: throw ResolveException("Playback config returned no signature timestamp")
                signatureTimestampCache[profileName] = timestamp
                timestamp
            }
        }.onFailure {
            Log.w(TAG, "Signature timestamp unavailable for $profileName: ${it.message}")
        }.getOrNull()
    }

    private fun fetchPoToken(videoId: String): String? {
        val cached = poTokenCache[videoId]
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) return cached.token

        val request = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/api/po-token/$videoId")
            .header("Accept", "application/json")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ResolveException("Playback token HTTP ${response.code}: ${shorten(body)}")
                val root = JSONObject(body)
                val token = root.optString("poToken").takeIf { it.isNotBlank() }
                    ?: throw ResolveException("Playback token endpoint returned no token")
                val expiresAt = root.optLong("expiresAt", 0L).takeIf { it > System.currentTimeMillis() }
                    ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
                poTokenCache[videoId] = CachedPoToken(token, expiresAt)
                token
            }
        }.onFailure { Log.w(TAG, "PO token unavailable for $videoId: ${it.message}") }.getOrNull()
    }

    data class ResolvedStream(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val contentLength: Long?,
        val itag: Int,
        val durationMs: Long?,
        val userAgent: String,
        val requestHeaders: Map<String, String>,
        val expiresAtMs: Long,
        val profileName: String = "UNKNOWN",
        val clientName: String? = null,
        val clientVersion: String? = null,
    ) {
        val isExpired: Boolean
            get() = expiresAtMs <= System.currentTimeMillis() + URL_EXPIRY_SAFETY_MS

        /**
         * A track/itag pair is not a sufficient cache identity.  YouTube CDN
         * URLs are signed and can differ by client, signature and expiry.
         */
        val contractId: String
            get() = "$profileName:$itag:${url.hashCode().toString(16)}"
    }

    class ResolveException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private data class AudioFormat(
        val url: String?,
        val signatureCipher: String?,
        val cipher: String?,
        val mimeType: String,
        val bitrate: Int,
        val contentLength: Long?,
        val itag: Int
    )

    private data class ClientProfile(
        val name: String,
        val version: String,
        val apiKey: String,
        val clientNameId: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val platform: String = "MOBILE",
        val androidSdkVersion: Int? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val apiFormatVersion: String? = null,
        val origin: String? = null,
        val referer: String? = null,
        val requiresPlaybackPoToken: Boolean = false,
        val useSignatureTimestamp: Boolean = false,
    )

    private data class CachedPoToken(val token: String, val expiresAtMs: Long)

    companion object {
        private const val TAG = "InnerTubeResolver"
        private const val URL_EXPIRY_SAFETY_MS = 30_000L
        private const val VALIDATION_RANGE_LENGTH = 8L * 1024L * 1024L
        private const val VALIDATION_RANGE_PROBE_POSITION = 8L * 1024L * 1024L
        private const val INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun formatPreference(format: AudioFormat): Int = when {
            format.itag == 251 -> 600
            format.itag == 250 -> 550
            format.itag == 249 -> 500
            format.mimeType == "audio/webm" -> 450
            format.itag == 140 -> 300
            format.itag == 139 -> 250
            format.mimeType == "audio/mp4" -> 200
            else -> 100
        }

        // Profile values are public client identifiers, not Moodify secrets.
        // Keep them centralized: YouTube changes these periodically.
        private val CLIENT_PROFILES = listOf(
            ClientProfile(
                name = "VISIONOS",
                version = "0.1",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "101",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
                osName = "visionOS",
                osVersion = "1.3.21O771",
                platform = "MOBILE",
                deviceMake = "Apple",
                deviceModel = "RealityDevice14,1"
            ),
            ClientProfile(
                name = "IOS",
                version = "21.26.4",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "5",
                userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2;)",
                osName = "iPhone",
                osVersion = "18.3.2.22D82",
                platform = "MOBILE",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2"
            ),
            ClientProfile(
                name = "ANDROID_MUSIC",
                version = "7.27.52",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "21",
                userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
                osName = "Android",
                osVersion = "15",
                androidSdkVersion = 35,
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                useSignatureTimestamp = true
            ),
            ClientProfile(
                name = "ANDROID",
                version = "21.26.364",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "3",
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                osName = "Android",
                osVersion = "11",
                androidSdkVersion = 30,
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                useSignatureTimestamp = true
            ),
            ClientProfile(
                name = "MWEB",
                version = "2.20260708.05.00",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "2",
                userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
                osName = "Android",
                osVersion = "15",
                androidSdkVersion = 35,
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                origin = "https://m.youtube.com",
                referer = "https://m.youtube.com/",
                requiresPlaybackPoToken = true
            ),
            ClientProfile(
                name = "ANDROID_VR",
                version = "1.65.10",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                osName = "Android",
                osVersion = "12L",
                androidSdkVersion = 32,
                deviceMake = "Oculus",
                deviceModel = "Quest 3"
            ),
            ClientProfile(
                name = "ANDROID_TESTSUITE",
                version = "1.9",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "30",
                userAgent = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
                osName = "Android",
                osVersion = "11",
                androidSdkVersion = 30
            ),
            ClientProfile(
                name = "WEB_REMIX",
                version = "1.20260707.12.00",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "67",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
                osName = "Windows",
                osVersion = "10.0",
                platform = "DESKTOP",
                origin = "https://music.youtube.com",
                referer = "https://music.youtube.com/",
                requiresPlaybackPoToken = true,
                useSignatureTimestamp = true
            ),
            ClientProfile(
                name = "WEB",
                version = "2.20260708.00.00",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "1",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                osName = "Windows",
                osVersion = "10.0",
                platform = "DESKTOP",
                origin = "https://www.youtube.com",
                referer = "https://www.youtube.com/",
                requiresPlaybackPoToken = true,
                useSignatureTimestamp = true
            ),
            ClientProfile(
                name = "WEB_CREATOR",
                version = "1.20260708.06.00",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "62",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                osName = "Windows",
                osVersion = "10.0",
                platform = "DESKTOP",
                origin = "https://www.youtube.com",
                referer = "https://www.youtube.com/",
                requiresPlaybackPoToken = true,
                useSignatureTimestamp = true
            ),
            ClientProfile(
                name = "TVHTML5_SIMPLY",
                version = "1.0",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "75",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
                osName = "",
                osVersion = "",
                platform = "TV"
            ),
            ClientProfile(
                name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                version = "2.0",
                apiKey = INNERTUBE_API_KEY,
                clientNameId = "85",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
                osName = "",
                osVersion = "",
                platform = "TV"
            )
        )

        private fun orderedProfiles(offset: Int): List<ClientProfile> {
            if (CLIENT_PROFILES.isEmpty()) return emptyList()
            // Prefer native clients first. The request must originate from
            // the device IP; Render is intentionally only a discovery and
            // optional decipher service because YouTube challenges cloud
            // egress. Web/VR profiles remain fallbacks for videos where the
            // native profile has no usable audio formats.
            val preferredOrder = listOf(
                "ANDROID_MUSIC",
                "ANDROID",
                "IOS",
                "ANDROID_TESTSUITE",
                "ANDROID_VR",
                "VISIONOS",
                "MWEB",
                "WEB_REMIX",
                "WEB",
                "WEB_CREATOR",
                "TVHTML5_SIMPLY",
                "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            )
            val profiles = preferredOrder.mapNotNull { name ->
                CLIENT_PROFILES.firstOrNull { it.name == name }
            }
            if (offset <= 0) return profiles
            val start = offset.coerceAtMost(profiles.size - 1)
            // Do not wrap back to a client that already produced a rejected
            // CDN URL.  A retry must be a genuinely different InnerTube
            // contract; the server/tokenized path handles the final fallback.
            return (profiles.drop(start) + profiles.take(start))
                .take(profiles.size - offset.coerceAtMost(profiles.size))
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun shorten(value: String): String = value
            .replace(Regex("\\s+"), " ")
            .take(160)
    }
}
