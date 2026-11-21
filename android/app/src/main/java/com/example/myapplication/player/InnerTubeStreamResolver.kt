package com.moodify.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.moodify.app.data.AudioQuality
import com.moodify.app.data.CatalogLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube video id to a short-lived audio CDN URL using InnerTube.
 *
 * Calls YouTube's /youtubei/v1/player endpoint directly from the device so that
 * requests arrive from the user's real IP — not from Render's shared datacenter
 * egress which is routinely bot-challenged by YouTube.
 *
 * Architecture notes:
 * - Visitor identity is bootstrapped from YouTube Music's sw.js_data endpoint
 *   and persisted before profile fallback begins; player responses can refresh it.
 * - Fully materialized URLs stay on-device. Ciphered formats are transformed
 *   locally with the current YouTube player script; Moodify's server is not
 *   part of stream resolution.
 * - Resolved streams are cached with expiry awareness; callers should check
 *   [ResolvedStream.isExpired] before handing a cached stream to ExoPlayer.
 * - Preview-length clips (< 90 % of declared video duration) are rejected so
 *   that YouTube's 90-second preview streams are never given to ExoPlayer.
 */
class InnerTubeStreamResolver(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val localeProvider: () -> CatalogLocale = { CatalogLocale.fromDefault() },
    private val loadVisitorDataFromStore: suspend () -> String? = { null },
    private val saveVisitorDataToStore: suspend (String) -> Unit = {},
    private val playerRuntimeSource: () -> String? = { null },
    poTokenRuntimeSource: () -> String? = { null },
    context: Context? = null,
) {
    private val playerScriptResolver = LocalPlayerScriptResolver(httpClient, playerRuntimeSource)
    private val poTokenProvider = context?.let {
        PlaybackPoTokenProvider(it, httpClient, localeProvider, poTokenRuntimeSource)
    }

    @Volatile
    private var visitorData: String? = null

    private val visitorDataMutex = Mutex()

    @Volatile
    private var visitorDataRetryAfterMs = 0L

    @Volatile
    private var cachedPlayerScriptUrl: String? = null

    @Volatile
    private var cachedPlayerScriptUrlExpiresAtMs: Long = 0L

    /**
     * How long finding a stream may take: [budgetMs] in all, [perClientMs] per client, and
     * after [hedgeAfterMs] without an answer the next client starts alongside (see [firstSuccess]).
     */
    data class Timing(val hedgeAfterMs: Long = 2_500, val perClientMs: Long = 5_000, val budgetMs: Long = 10_000)

    @Volatile
    private var timing = Timing()

    /** The order clients are asked in; the social server can change it (see [applyPlaybackConfig]). */
    @Volatile
    private var clientOrder: List<String> = DEFAULT_CLIENT_ORDER

    /** The client that last produced a working stream: asked first for the rest of the session. */
    @Volatile
    private var preferredClient: String? = null

    /**
     * Applies a client order and timing from the social server, so a YouTube change can be
     * answered without a new APK. Unknown client names are ignored; timings are kept sane.
     * Anything left out goes back to the default, so a server that drops its config undoes it.
     */
    fun applyPlaybackConfig(order: List<String>?, newTiming: Timing?) {
        clientOrder = order?.filter { name -> CLIENT_PROFILES.any { it.name == name } }?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_CLIENT_ORDER
        timing = newTiming?.let {
            Timing(
                hedgeAfterMs = it.hedgeAfterMs.coerceIn(300L, 5_000L),
                perClientMs = it.perClientMs.coerceIn(2_000L, 20_000L),
                budgetMs = it.budgetMs.coerceIn(5_000L, 30_000L),
            )
        } ?: Timing()
    }

    /** Clients in the order to ask them: the one that last worked, then the configured order. */
    private fun sessionOrder(): List<ClientProfile> {
        val order = clientOrder
        val names = preferredClient?.takeIf { it in order }?.let { listOf(it) + (order - it) } ?: order
        return names.mapNotNull { name -> CLIENT_PROFILES.firstOrNull { it.name == name } }
    }

    /**
     * Clients from [offset] on in the session order. An offset never wraps: a rejected
     * client must not come back later in the same fallback pass.
     */
    private fun orderedProfiles(offset: Int): List<ClientProfile> =
        sessionOrder().let { it.drop(offset.coerceIn(0, it.size)) }

    // A resolved URL is useful only until its CDN expiry. Keep the cache
    // bounded by video/profile key and invalidate it on transport failures.
    private val resolvedCache = java.util.concurrent.ConcurrentHashMap<String, ResolvedStream>()
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-instance in-flight dedup map: videoId/profile-attempt → running result
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ResolvedStream>>()

    /**
     * [quality] picks the stream size (Settings › Playback, per network; see [formatRank]).
     * [keepItag] is the format of a partly saved copy: when offered, it wins over [quality]
     * so the saved bytes stay usable. Downloads always resolve at the default, the best available.
     */
    suspend fun resolve(
        videoId: String,
        profileOffset: Int = 0,
        quality: AudioQuality = AudioQuality.HIGH,
        keepItag: Int? = null,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        // A changed offset intentionally selects the next InnerTube client
        // after a CDN 403 instead of returning the same signed URL again.
        val requestKey = "$videoId:$profileOffset:${quality.name}:${keepItag ?: ""}"
        resolvedCache[requestKey]?.takeUnless { it.isExpired }?.let { return@withContext it }
        val deferred = inFlight.getOrPut(requestKey) {
            resolutionScope.async {
                try {
                    resolveUncached(videoId, profileOffset, quality, keepItag).also { resolvedCache[requestKey] = it }
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
        poTokenProvider?.invalidate(videoId)
    }

    fun invalidateAll() {
        resolvedCache.clear()
        inFlight.clear()
        poTokenProvider?.invalidateAll()
    }

    /** Number of distinct InnerTube client contracts in the fallback chain. */
    fun profileCount(): Int = sessionOrder().size

    /** Return the index of a named profile in the resolver's stable preference order. */
    fun profileOffset(profileName: String): Int? {
        val index = orderedProfiles(0).indexOfFirst { it.name == profileName }
        return index.takeIf { it >= 0 }
    }

    /** Return the first profile index after the client that produced a rejected CDN contract. */
    fun nextProfileOffset(profileName: String): Int? {
        val index = orderedProfiles(0).indexOfFirst { it.name == profileName }
        return index.takeIf { it >= 0 }?.plus(1)
    }

    /** Resolve ahead of playback without making the player wait for it. */
    suspend fun preload(videoId: String): ResolvedStream? =
        runCatching { resolve(videoId) }
            .onFailure { Log.d(TAG, "Preload skipped (${it::class.simpleName})") }
            .getOrNull()

    /** Establish a visitor/session identity before querying playback clients. */
    private suspend fun ensureVisitorData(): String? = visitorDataMutex.withLock {
        visitorData
            ?.takeIf { VISITOR_DATA_PATTERN.matches(it) }
            ?.let { return@withLock it }

        val persisted = try {
            loadVisitorDataFromStore()
        } catch (failure: Exception) {
            Log.w(TAG, "Could not read persisted visitor context (${failure::class.simpleName})")
            null
        }?.trim()?.takeIf { VISITOR_DATA_PATTERN.matches(it) }
        if (persisted != null) {
            visitorData = persisted
            Log.d(TAG, "Loaded persisted YouTube visitor context")
            return@withLock persisted
        }

        if (System.currentTimeMillis() < visitorDataRetryAfterMs) return@withLock null

        val fetched = try {
            fetchVisitorData()
        } catch (failure: Exception) {
            visitorDataRetryAfterMs = System.currentTimeMillis() + VISITOR_DATA_RETRY_MS
            Log.w(TAG, "Could not bootstrap YouTube visitor context (${failure::class.simpleName})")
            null
        }
        if (fetched != null) {
            visitorData = fetched
            visitorDataRetryAfterMs = 0L
            try {
                saveVisitorDataToStore(fetched)
            } catch (failure: Exception) {
                Log.w(TAG, "Could not persist YouTube visitor context (${failure::class.simpleName})")
            }
            Log.d(TAG, "Bootstrapped YouTube visitor context")
        }
        fetched
    }

    private suspend fun fetchVisitorData(): String = withContext(Dispatchers.IO) {
        val locale = localeProvider().normalized()
        val request = Request.Builder()
            .url(YOUTUBE_MUSIC_SW_DATA_URL)
            .header("Accept", "*/*")
            .header("Accept-Language", locale.acceptLanguage)
            .header("User-Agent", SW_DATA_USER_AGENT)
            .header("Referer", "https://music.youtube.com/")
            .get()
            .build()

        val (code, body) = httpClient.fetch(request) { it.code to it.body?.string().orEmpty() }
        if (code !in 200..299) throw IOException("sw.js_data HTTP $code")
        parseVisitorDataFromSwJsData(body)
            ?: throw ResolveException("sw.js_data contained no visitorData")
    }

    private suspend fun rememberVisitorData(value: String?) {
        val normalized = value?.trim()?.takeIf { VISITOR_DATA_PATTERN.matches(it) } ?: return
        visitorDataMutex.withLock {
            if (visitorData == normalized) return@withLock
            visitorData = normalized
            try {
                saveVisitorDataToStore(normalized)
            } catch (failure: Exception) {
                Log.w(TAG, "Could not persist refreshed YouTube visitor context (${failure::class.simpleName})")
            }
        }
    }

    /**
     * Validate the resolved CDN contract before ExoPlayer opens a long-lived
     * media source.  YouTube signs the URL and its request context together;
     * probing with the same headers and a tiny range catches a 403 while the
     * resolver can still rotate to another client/format.
     *
     * Each probe asks for a large range, as the player will: the CDN may serve a few
     * KB and still refuse the whole file (seen 21 Nov: a 16 KB probe passed, playback
     * got 403). Only the status is read; the probes run at once, and over HTTP/2 the
     * connection stays open in the pool the player shares.
     */
    suspend fun validate(stream: ResolvedStream): Unit = coroutineScope {
        if (stream.isExpired) throw ResolveException("Resolved stream expired before validation")

        val positions = buildList {
            add(0L)
            stream.contentLength
                ?.takeIf { it > VALIDATION_RANGE_LENGTH }
                ?.let { add(minOf(VALIDATION_RANGE_PROBE_POSITION, it - VALIDATION_RANGE_LENGTH)) }
        }.distinct()

        positions.map { position -> async { probe(stream, position) } }.awaitAll()
    }

    private suspend fun probe(stream: ResolvedStream, position: Long) {
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

        // The status is all that's needed; the body is closed unread.
        val code = httpClient.fetch(request) { response -> response.code }
        when {
            code == 403 ->
                throw ResolveException(
                    "${stream.profileName} CDN validation HTTP 403 at byte $position"
                )
            code in 200..299 || code == 416 -> Unit
            else -> throw ResolveException(
                "${stream.profileName} CDN validation HTTP $code at byte $position"
            )
        }
        Log.d(
            TAG,
            "Validated ${stream.profileName} itag=${stream.itag} " +
                "position=$position status=$code"
        )
    }

    private suspend fun resolveUncached(
        videoId: String,
        profileOffset: Int,
        quality: AudioQuality = AudioQuality.HIGH,
        keepItag: Int? = null,
    ): ResolvedStream {
        val timing = timing
        val startedAt = System.currentTimeMillis()
        // A saved visitor identity is instant; fetching a new one mustn't use up the budget.
        withTimeoutOrNull(timing.perClientMs) { ensureVisitorData() }

        val profiles = orderedProfiles(profileOffset)
        if (profiles.isEmpty()) throw ResolveException("No playback clients left to try")
        val stream = try {
            withTimeoutOrNull(timing.budgetMs) {
                firstSuccess(
                    candidates = profiles,
                    hedgeAfterMs = timing.hedgeAfterMs,
                    perTryMs = timing.perClientMs,
                    scope = resolutionScope,
                    onFailure = { profile, failure ->
                        Log.w(TAG, "${profile.name} failed (${failure::class.simpleName}: ${failure.message.orEmpty().take(120)})")
                    },
                ) { profile -> attempt(profile, videoId, quality, keepItag) }
            }
        } catch (failure: Exception) {
            // Only a real cancellation of this lookup stays a cancellation; anything else
            // (including a client's own timeout) is a failure the player can act on.
            currentCoroutineContext().ensureActive()
            if (failure is TimeoutCancellationException) {
                null // every client ran out of its own time
            } else {
                throw ResolveException("InnerTube could not resolve playback with the available clients.", failure)
            }
        } ?: throw ResolveException("No playable stream within ${timing.budgetMs / 1_000} s")

        // Ask the client that worked first for the rest of the session.
        preferredClient = stream.profileName
        Log.d(TAG, "Stream ready via ${stream.profileName} in ${System.currentTimeMillis() - startedAt} ms")
        return stream
    }

    /** One client's go: a player request, then a check that the CDN accepts the URL. */
    private suspend fun attempt(
        profile: ClientProfile,
        videoId: String,
        quality: AudioQuality,
        keepItag: Int?,
    ): ResolvedStream {
        val poToken = if (profile.requiresPlaybackPoToken) {
            val provider = poTokenProvider
                ?: throw ResolveException("${profile.name}: local PO-token provider is unavailable")
            val token = provider.tokenFor(videoId, visitorData)
            rememberVisitorData(token.visitorData)
            token.value
        } else {
            null
        }
        val signatureTimestamp = if (profile.useSignatureTimestamp) {
            val scriptUrl = discoverPlayerScriptUrl(videoId)
            if (scriptUrl == null) {
                null
            } else {
                try {
                    playerScriptResolver.signatureTimestamp(scriptUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(TAG, "${profile.name}: could not read player signature timestamp")
                    null
                }
            }
        } else {
            null
        }
        if (profile.useSignatureTimestamp && signatureTimestamp == null) {
            Log.w(TAG, "${profile.name}: player signature timestamp unavailable")
        }
        Log.d(
            TAG,
            "${profile.name} player request: clientVersion=${profile.version}, " +
                "poToken=${poToken != null}, signatureTimestamp=${signatureTimestamp ?: "none"}, " +
                "visitorData=${!visitorData.isNullOrBlank()}",
        )
        val resolved = requestPlayer(profile, videoId, poToken, signatureTimestamp, quality, keepItag)
        Log.d(
            TAG,
            "Resolved stream via ${profile.name}: mime=${resolved.mimeType} " +
                "itag=${resolved.itag} len=${resolved.contentLength}"
        )
        validate(resolved)
        return resolved
    }

    private suspend fun requestPlayer(
        profile: ClientProfile,
        videoId: String,
        poToken: String?,
        signatureTimestamp: Int?,
        quality: AudioQuality = AudioQuality.HIGH,
        keepItag: Int? = null,
    ): ResolvedStream {
        val locale = localeProvider().normalized()
        val request = Request.Builder()
            .url(
                "https://youtubei.googleapis.com/youtubei/v1/player" +
                    "?key=${profile.apiKey}&prettyPrint=false&alt=json"
            )
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "*/*")
                    .add("Accept-Language", locale.acceptLanguage)
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
            .post(buildPlayerBody(profile, videoId, poToken, signatureTimestamp, locale).toString().toRequestBody(JSON))
            .build()

        // Cancellable: a client that runs out of time stops waiting on the network.
        val (responseCode, responseBody) = httpClient.fetch(request) { it.code to it.body?.string().orEmpty() }
        if (responseCode !in 200..299) {
            throw ResolveException(
                "${profile.name} HTTP $responseCode" +
                    responseBody.takeIf { it.isNotBlank() }?.let { ": ${shorten(it)}" }.orEmpty()
            )
        }

        val root = runCatching { JSONObject(responseBody) }.getOrElse {
            throw ResolveException("${profile.name} returned invalid JSON", it)
        }

        root.optJSONObject("responseContext")
            ?.optString("visitorData")
            ?.takeIf { it.isNotBlank() }
            ?.let { rememberVisitorData(it) }

        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty()
        if (status != "OK") {
            Log.w(TAG, "${profile.name} playability status=$status")
            throw ResolveException("${profile.name}: playability status $status")
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: throw ResolveException("${profile.name}: no streamingData")
        val playerScriptUrl = root.optJSONObject("assets")
            ?.optString("js")
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
            ?: discoverPlayerScriptUrl(videoId)
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
            compareByDescending<AudioFormat> { formatRank(it.itag, it.mimeType, quality, keepItag) }
                .thenBy { if (quality == AudioQuality.LOW) it.bitrate else -it.bitrate }
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
                // Direct URLs stay on the device. Ciphered formats are
                // transformed locally in a sandboxed player-script
                // runtime; they never go to a Moodify endpoint.
                val playableUrl = playerScriptResolver.resolve(
                    playerScriptUrl = playerScriptUrl,
                    directUrl = candidateUrl,
                    signatureCipher = candidate.signatureCipher,
                    cipher = candidate.cipher,
                )
                if (!playableUrl.isNullOrBlank()) {
                    selected = candidate
                    resolvedUrl = playableUrl
                    break
                }
            } catch (failure: Exception) {
                lastDecipherFailure = failure
                Log.w(TAG, "${profile.name}: unable to decipher itag=${candidate.itag} (${failure::class.simpleName})")
            }
        }

        val selectedFormat = selected ?: throw ResolveException(
            "${profile.name}: no playable audio URL",
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

        // YouTube reports how loud this track is relative to its reference
        // level; Moodify uses it to even out volume between songs.
        val loudnessDb = root.optJSONObject("playerConfig")
            ?.optJSONObject("audioConfig")
            ?.let { config ->
                config.optDouble("loudnessDb", Double.NaN).takeIf { it.isFinite() }
                    ?: config.optDouble("perceptualLoudnessDb", Double.NaN).takeIf { it.isFinite() }
            }

        return ResolvedStream(
            loudnessDb = loudnessDb,
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
            sourceWasCiphered = !selectedFormat.signatureCipher.isNullOrBlank() ||
                !selectedFormat.cipher.isNullOrBlank(),
            // Refresh before the CDN URL reaches its actual expiry.
            expiresAtMs = System.currentTimeMillis() +
                expiresInSeconds * 1_000L - URL_EXPIRY_SAFETY_MS
        )
    }

    private fun buildPlayerBody(
        profile: ClientProfile,
        videoId: String,
        poToken: String?,
        signatureTimestamp: Int?,
        locale: CatalogLocale,
    ): JSONObject {
        val client = JSONObject()
            .put("clientName", profile.name)
            .put("clientVersion", profile.version)
            .put("hl", locale.language)
            .put("gl", locale.region)
            .put("osName", profile.osName)
            .put("osVersion", profile.osVersion)
            .put("platform", profile.platform)
            .put("clientFormFactor", "SMALL_FORM_FACTOR")
            .put("userAgent", profile.userAgent)
            .put("originalUrl", "https://www.youtube.com/watch?v=$videoId")
            .put("timeZone", java.util.TimeZone.getDefault().id)
        visitorData?.takeIf { it.isNotBlank() }?.let { client.put("visitorData", it) }

        profile.androidSdkVersion?.let { client.put("androidSdkVersion", it) }
        profile.deviceMake?.let { client.put("deviceMake", it) }
        profile.deviceModel?.let { client.put("deviceModel", it) }

        val context = JSONObject().put("client", client)

        val contentPlaybackContext = JSONObject()
            .put("vis", 0)
            .put("splay", false)
            .put("lactMilliseconds", "-1")
            .apply { signatureTimestamp?.takeIf { it > 0 }?.let { put("signatureTimestamp", it) } }

        return JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", contentPlaybackContext))
            .apply {
                if (!poToken.isNullOrBlank()) {
                    put("serviceIntegrityDimensions", JSONObject().put("poToken", poToken))
                }
            }
    }

    private suspend fun discoverPlayerScriptUrl(videoId: String): String? {
        val now = System.currentTimeMillis()
        cachedPlayerScriptUrl
            ?.takeIf { cachedPlayerScriptUrlExpiresAtMs > now }
            ?.let { return it }

        val playerIdPattern = Regex("/s/player/([A-Za-z0-9_-]{1,80})/")
        val userAgent = "Mozilla/5.0 (Android 15; Mobile) AppleWebKit/537.36 Chrome/141.0.0.0 Mobile Safari/537.36"
        suspend fun fromIframeApi(): String {
            val iframeRequest = Request.Builder()
                .url("https://www.youtube.com/iframe_api")
                .header("Accept", "*/*")
                .header("User-Agent", userAgent)
                .build()
            val (code, body) = httpClient.fetch(iframeRequest) { it.code to it.body?.string().orEmpty() }
            if (code !in 200..299) throw IOException("iframe_api HTTP $code")
            val playerId = playerIdPattern.find(body.replace("\\/", "/"))?.groupValues?.getOrNull(1)
                ?: throw ResolveException("Could not identify the current YouTube player script")
            return "https://www.youtube.com/s/player/$playerId/player_es6.vflset/en_US/base.js"
        }
        suspend fun fromWatchPage(): String {
            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&hl=en")
                .header("Accept", "text/html")
                .header("User-Agent", userAgent)
                .build()
            val (code, html) = httpClient.fetch(request) { it.code to it.body?.string().orEmpty() }
            if (code !in 200..299) throw IOException("watch page HTTP $code")
            val raw = listOf(
                Regex("\\\"jsUrl\\\":\\\"([^\\\"]+)\\\""),
                Regex("\\\"PLAYER_JS_URL\\\":\\\"([^\\\"]+)\\\""),
            ).asSequence()
                .mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
                .firstOrNull()
                ?: throw ResolveException("YouTube watch page did not contain a player script URL")
            return raw.replace("\\u0026", "&")
                .replace("\\/", "/")
                .let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
        }
        val discovered = try {
            try {
                fromIframeApi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (iframeFailed: Exception) {
                fromWatchPage()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.d(TAG, "Player script discovery skipped (${failure::class.simpleName})")
            null
        }
        return discovered
            ?.also { discovered ->
                cachedPlayerScriptUrl = discovered
                cachedPlayerScriptUrlExpiresAtMs = now + PLAYER_SCRIPT_URL_TTL_MS
            }
    }

    data class ResolvedStream(
        /** Track loudness relative to YouTube's target (positive = louder), if known. */
        val loudnessDb: Double? = null,
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
        val sourceWasCiphered: Boolean = false,
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

    companion object {
        private const val TAG = "InnerTubeResolver"
        private const val URL_EXPIRY_SAFETY_MS = 30_000L
        private const val VISITOR_DATA_RETRY_MS = 60_000L
        private const val PLAYER_SCRIPT_URL_TTL_MS = 6L * 60L * 60L * 1_000L
        private const val VALIDATION_RANGE_LENGTH = 8L * 1024L * 1024L
        private const val VALIDATION_RANGE_PROBE_POSITION = 8L * 1024L * 1024L
        private const val INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val YOUTUBE_MUSIC_SW_DATA_URL = "https://music.youtube.com/sw.js_data"
        private const val SW_DATA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        private val VISITOR_DATA_PATTERN = Regex("^Cg[ts][A-Za-z0-9_=-]{20,}$")
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Parse YouTube's XSSI-prefixed service-worker data without logging the identity value. */
        internal fun parseVisitorDataFromSwJsData(payload: String): String? {
            val jsonStart = payload.indexOfFirst { it == '[' || it == '{' }
            if (jsonStart < 0) return null
            val root = runCatching {
                when (payload[jsonStart]) {
                    '[' -> JSONArray(payload.substring(jsonStart))
                    '{' -> JSONObject(payload.substring(jsonStart))
                    else -> return null
                }
            }.getOrNull() ?: return null
            return findVisitorData(root)
        }

        private fun findVisitorData(value: Any?): String? {
            when (value) {
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        findVisitorData(value.opt(index))?.let { return it }
                    }
                }
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        findVisitorData(value.opt(keys.next()))?.let { return it }
                    }
                }
                is String -> value.trim().takeIf { VISITOR_DATA_PATTERN.matches(it) }?.let { return it }
            }
            return null
        }

        /**
         * How much a format suits the chosen quality (higher is better). YouTube's audio
         * itags: 249 Opus ~50 kbps, 250 Opus ~70 kbps, 251 Opus ~130-160 kbps, 774 Opus
         * ~256 kbps (rarely offered), 139 AAC ~48 kbps, 140 AAC 128 kbps. Opus comes first
         * at each level: the CDN sometimes rejects 140 while the Opus stream still plays.
         * Every format stays in the list, so a song plays even when the ideal one is missing.
         * [keepItag], the format of a partly saved copy, outranks them all.
         */
        internal fun formatRank(itag: Int, mimeType: String, quality: AudioQuality, keepItag: Int? = null): Int {
            if (itag == keepItag) return 2000
            val order = when (quality) {
                AudioQuality.LOW -> listOf(249, 139, 250, 251, 140, 774)
                AudioQuality.NORMAL -> listOf(250, 251, 140, 249, 139, 774)
                AudioQuality.HIGH -> listOf(774, 251, 140, 250, 249, 139)
            }
            val index = order.indexOf(itag)
            return when {
                index >= 0 -> 1000 - index * 50
                mimeType == "audio/webm" -> 300
                mimeType == "audio/mp4" -> 200
                else -> 100
            }
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
                requiresPlaybackPoToken = true,
                useSignatureTimestamp = true
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

        /** The usual order to ask clients in, best first. */
        internal val DEFAULT_CLIENT_ORDER = listOf(
            // Live emulator/device evidence shows this client returns a
            // directly playable stream without a token, avoiding several
            // known-dead profile attempts on the common path.
            "VISIONOS",
            "ANDROID_VR",
            "ANDROID_MUSIC",
            "ANDROID",
            "IOS",
            "ANDROID_TESTSUITE",
            "MWEB",
            "WEB_REMIX",
            "WEB",
            "WEB_CREATOR",
            "TVHTML5_SIMPLY",
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        )

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
