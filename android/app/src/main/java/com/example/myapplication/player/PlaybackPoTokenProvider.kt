package com.moodify.app.player

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import android.view.View
import com.moodify.app.data.CatalogLocale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A short-lived, video-bound InnerTube playback proof minted on this device. */
data class PlaybackPoToken(
    val value: String,
    val videoId: String,
    val visitorData: String?,
    val expiresAtMs: Long,
)

/**
 * Produces WebPO playback tokens locally using Android's System WebView as the
 * BotGuard JavaScript environment. The WebView contains no YouTube player or
 * audio element; it hosts a blank youtube.com-origin document for attestation.
 * All challenge, interpreter, and integrity-token HTTP traffic stays on device.
 */
class PlaybackPoTokenProvider(
    context: Context,
    private val httpClient: OkHttpClient,
    private val localeProvider: () -> CatalogLocale,
    private val runtimeSource: () -> String?,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val nextCallId = AtomicLong()
    private val pendingCalls = ConcurrentHashMap<String, CancellableContinuation<String>>()
    private val tokenCache = ConcurrentHashMap<String, PlaybackPoToken>()

    @Volatile
    private var webView: WebView? = null

    private var pageReady: CompletableDeferred<Unit>? = null
    private var runtimeLoaded = false
    private var minterExpiresAtMs = 0L
    private var minterVisitorData: String? = null

    @Volatile
    private var webViewUserAgent = BOTGUARD_USER_AGENT

    /** Returns a valid per-video token, minting a new WebPO minter when needed. */
    suspend fun tokenFor(videoId: String, knownVisitorData: String?): PlaybackPoToken =
        withContext(Dispatchers.IO) {
            require(videoId.matches(VIDEO_ID_PATTERN)) { "Invalid YouTube video id" }
            mutex.withLock {
                val cacheKey = cacheKey(
                    videoId,
                    knownVisitorData?.takeIf(String::isNotBlank) ?: minterVisitorData,
                )
                tokenCache[cacheKey]
                    ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
                    ?.let { return@withLock it }

                if (minterExpiresAtMs <= System.currentTimeMillis() ||
                    (knownVisitorData != null && minterVisitorData != null && knownVisitorData != minterVisitorData)
                ) {
                    resetRuntimeSession()
                    createMinter(knownVisitorData)
                }

                val token = decodeJsString(
                    invokeRuntime("MoodifyPoTokenRuntime.mint(${JSONObject.quote(videoId)})"),
                ).trim()
                if (token.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH || !TOKEN_PATTERN.matches(token)) {
                    throw IOException("BotGuard returned a malformed playback token")
                }

                val resolvedVisitorData = knownVisitorData?.takeIf(String::isNotBlank) ?: minterVisitorData
                val result = PlaybackPoToken(
                    value = token,
                    videoId = videoId,
                    visitorData = resolvedVisitorData,
                    expiresAtMs = minterExpiresAtMs,
                )
                tokenCache[cacheKey(videoId, resolvedVisitorData)] = result
                trimTokenCache()
                result
            }
        }

    /** Drop a video token after a CDN rejection so the next attempt can remint it. */
    fun invalidate(videoId: String) {
        tokenCache.keys.removeIf { it.endsWith(":$videoId") }
    }

    /** Invalidate every token and force a new device attestation on the next call. */
    fun invalidateAll() {
        tokenCache.clear()
        minterExpiresAtMs = 0L
    }

    /** Release the hidden JavaScript host when its owning resolver is discarded. */
    suspend fun release() = mutex.withLock {
        if (runtimeLoaded && webView != null) {
            runCatching { decodeJsString(invokeRuntime("MoodifyPoTokenRuntime.shutdown()")) }
        }
        invalidateAll()
        destroyWebView()
    }

    private suspend fun createMinter(knownVisitorData: String?) {
        ensureRuntime()
        val locale = localeProvider().normalized()
        val pageHtml = fetchBounded(
            Request.Builder()
                .url("https://www.youtube.com/?hl=${locale.language}-${locale.region}")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", locale.acceptLanguage)
                .header("User-Agent", webViewUserAgent)
                .build(),
            MAX_HOME_BYTES,
            "YouTube attestation page",
        )

        val parsedPage = JSONObject(
            decodeJsString(
                invokeRuntime("MoodifyPoTokenRuntime.parsePage(${JSONObject.quote(pageHtml)})"),
            ),
        )
        val challenge = parsedPage.getJSONObject("challenge")
        val interpreterUrl = challenge.getString("interpreterUrl")
        val absoluteInterpreterUrl = when {
            interpreterUrl.startsWith("https://") -> interpreterUrl
            interpreterUrl.startsWith("//") -> "https:$interpreterUrl"
            else -> throw IOException("BotGuard returned an invalid interpreter URL")
        }
        val interpreterJavascript = fetchBounded(
            Request.Builder()
                .url(absoluteInterpreterUrl)
                .header("Accept", "*/*")
                .header("User-Agent", webViewUserAgent)
                .build(),
            MAX_INTERPRETER_BYTES,
            "BotGuard interpreter",
        )
        if (interpreterJavascript.isBlank()) throw IOException("BotGuard interpreter response was empty")

        val challengePayload = JSONObject()
            .put("ytcfg", parsedPage.getJSONObject("ytcfg"))
            .put("challenge", challenge)
            .put("visitorData", parsedPage.optString("visitorData").takeIf(String::isNotBlank))
            .toString()
        val snapshot = JSONObject(
            decodeJsString(
                invokeRuntime(
                    "MoodifyPoTokenRuntime.initialize(" +
                        "${JSONObject.quote(challengePayload)}," +
                        "${JSONObject.quote(interpreterJavascript)})",
                ),
            ),
        )
        val botguardResponse = snapshot.optString("botguardResponse")
            .takeIf(String::isNotBlank)
            ?: throw IOException("BotGuard did not return an attestation response")
        Log.d(
            TAG,
            "BotGuard snapshot: webPoReady=${snapshot.optBoolean("webPoSignalOutputReady")}, " +
                "signals=${snapshot.optInt("webPoSignalOutputCount")}, " +
                    "clientErrors=${snapshot.optInt("clientErrorCount")}, " +
                    "snapshotFn=${snapshot.optBoolean("snapshotFunctionReady")}, " +
                    "syncSnapshotFn=${snapshot.optBoolean("syncSnapshotFunctionReady")}, " +
                    "origin=${snapshot.optString("pageOrigin")}, visible=${snapshot.optString("pageVisible")}, " +
                    "eventId=${snapshot.optBoolean("eventIdPresent")}, " +
                    "viewport=${snapshot.optInt("viewportWidth")}x${snapshot.optInt("viewportHeight")}, " +
                    "canvas=${snapshot.optBoolean("canvasAvailable")}",
        )
        if (!snapshot.optBoolean("webPoSignalOutputReady")) {
            throw IOException(
                "BotGuard did not provide a WebPO minter " +
                    "(signals=${snapshot.optInt("webPoSignalOutputCount")}, " +
                    "clientErrors=${snapshot.optInt("clientErrorCount")})",
            )
        }

        val integrityResponse = requestIntegrityToken(botguardResponse)
        val ttlSeconds = integrityResponse.optLong("estimatedTtlSecs")
            .coerceIn(MIN_TOKEN_TTL_SECONDS, MAX_TOKEN_TTL_SECONDS)
        val expiresAt = System.currentTimeMillis() + ttlSeconds * 1_000L - TOKEN_EXPIRY_SAFETY_MS
        if (expiresAt <= System.currentTimeMillis()) throw IOException("BotGuard integrity token expired immediately")

        decodeJsString(
            invokeRuntime(
                "MoodifyPoTokenRuntime.createMinter(${JSONObject.quote(integrityResponse.toString())})",
            ),
        )
        minterExpiresAtMs = expiresAt
        minterVisitorData = knownVisitorData?.takeIf(String::isNotBlank)
            ?: snapshot.optNullableString("visitorData")
            ?: parsedPage.optNullableString("visitorData")
        Log.i(TAG, "Created local WebPO minter (ttl=${ttlSeconds}s, visitorData=${minterVisitorData != null})")
    }

    private suspend fun requestIntegrityToken(botguardResponse: String): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONArray()
            .put(REQUEST_KEY)
            .put(botguardResponse)
            .toString()
            .toRequestBody(JSON_PROTOBUF)
        val request = Request.Builder()
            .url("https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/GenerateIT")
            .header("Content-Type", "application/json+protobuf")
            .header("x-goog-api-key", GOOGLE_API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .header("Origin", YOUTUBE_ORIGIN)
            .header("Referer", "$YOUTUBE_ORIGIN/")
            .header("User-Agent", webViewUserAgent)
            .post(payload)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("BotGuard integrity request HTTP ${response.code}")
            val raw = response.body?.string().orEmpty()
            if (raw.length > MAX_INTEGRITY_RESPONSE_CHARS) {
                throw IOException("BotGuard integrity response exceeded the size limit")
            }
            val values = JSONArray(raw)
            val token = values.optString(0).takeIf(String::isNotBlank)
                ?: throw IOException("BotGuard returned an empty integrity token")
            JSONObject()
                .put("integrityToken", token)
                .put("estimatedTtlSecs", values.optLong(1, 0L))
                .put("mintRefreshThreshold", values.optLong(2, 0L))
                .put("websafeFallbackToken", values.optString(3).takeIf(String::isNotBlank))
        }
    }

    private suspend fun ensureRuntime() {
        ensureWebView()
        if (runtimeLoaded) return
        val source = runtimeSource()?.takeIf(String::isNotBlank)
            ?: throw IOException("The bundled BotGuard runtime is unavailable")
        val installExpression = "(function(){(new Function(${JSONObject.quote(source)}))();" +
            "return typeof MoodifyPoTokenRuntime === 'object' ? 'true' : 'false';})()"
        val installed = decodeJsString(invokeRuntime(installExpression))
        if (installed != "true") throw IOException("The bundled BotGuard runtime did not initialize")
        runtimeLoaded = true
    }

    private suspend fun ensureWebView() {
        if (webView != null && pageReady?.isCompleted == true) return
        withContext(Dispatchers.Main.immediate) {
            if (webView != null && pageReady?.isCompleted == true) return@withContext
            val ready = CompletableDeferred<Unit>()
            val created = WebView(appContext)
            pageReady = ready
            created.settings.javaScriptEnabled = true
            created.settings.domStorageEnabled = true
            created.settings.javaScriptCanOpenWindowsAutomatically = false
            created.settings.blockNetworkLoads = true
            webViewUserAgent = BOTGUARD_USER_AGENT
            created.settings.userAgentString = webViewUserAgent
            created.setBackgroundColor(Color.TRANSPARENT)
            created.addJavascriptInterface(Bridge(), BRIDGE_NAME)
            created.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready.complete(Unit)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = true
            }
            webView = created
            val displayMetrics = appContext.resources.displayMetrics
            val viewportWidth = displayMetrics.widthPixels.coerceAtLeast(MIN_VIEWPORT_WIDTH_PX)
            val viewportHeight = displayMetrics.heightPixels.coerceAtLeast(MIN_VIEWPORT_HEIGHT_PX)
            created.measure(
                View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
            )
            created.layout(0, 0, viewportWidth, viewportHeight)
            created.loadDataWithBaseURL(
                "$YOUTUBE_ORIGIN/",
                "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body></body></html>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            withTimeout(WEBVIEW_LOAD_TIMEOUT_MS) { requireNotNull(pageReady).await() }
        } catch (failure: TimeoutCancellationException) {
            destroyWebView()
            throw IOException("System WebView did not initialize for BotGuard", failure)
        }
    }

    private suspend fun invokeRuntime(expression: String): String = withTimeout(JS_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val id = "moodify_${nextCallId.incrementAndGet()}"
            pendingCalls[id] = continuation
            continuation.invokeOnCancellation { pendingCalls.remove(id) }

            val quotedId = JSONObject.quote(id)
            val script = "Promise.resolve().then(()=>($expression)).then(" +
                "value=>window.$BRIDGE_NAME.onSuccess($quotedId,JSON.stringify(value))," +
                "error=>window.$BRIDGE_NAME.onError($quotedId,String(error&&error.stack||error)))"
            mainHandler.post {
                val current = webView
                if (current == null) {
                    pendingCalls.remove(id)?.resumeWith(Result.failure(IOException("System WebView was destroyed")))
                } else {
                    try {
                        current.evaluateJavascript(script, null)
                    } catch (failure: Throwable) {
                        pendingCalls.remove(id)?.resumeWith(Result.failure(IOException("Could not evaluate BotGuard JavaScript", failure)))
                    }
                }
            }
        }
    }

    private fun decodeJsString(encoded: String): String =
        (JSONTokener(encoded).nextValue() as? String)
            ?: throw IOException("BotGuard JavaScript returned an invalid result")

    private suspend fun resetRuntimeSession() {
        if (runtimeLoaded && webView != null) {
            runCatching {
                decodeJsString(invokeRuntime("MoodifyPoTokenRuntime.shutdown()"))
            }
        }
        tokenCache.clear()
        minterExpiresAtMs = 0L
        minterVisitorData = null
        runtimeLoaded = webView != null
    }

    private suspend fun destroyWebView() {
        val old = webView
        webView = null
        pageReady = null
        runtimeLoaded = false
        withContext(Dispatchers.Main.immediate) {
            old?.apply {
                removeJavascriptInterface(BRIDGE_NAME)
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
        }
    }

    private fun fetchBounded(request: Request, limit: Int, label: String): String =
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$label HTTP ${response.code}")
            val body = response.body ?: throw IOException("$label response was empty")
            if (body.contentLength() > limit) throw IOException("$label exceeded the size limit")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw IOException("$label exceeded the size limit")
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        }

    private fun cacheKey(videoId: String, visitorData: String?): String =
        "${visitorData.orEmpty()}:$videoId"

    private fun trimTokenCache() {
        if (tokenCache.size <= MAX_CACHED_VIDEO_TOKENS) return
        tokenCache.keys.firstOrNull()?.let(tokenCache::remove)
    }

    private fun JSONObject.optNullableString(name: String): String? = optString(name)
        .takeIf { it.isNotBlank() && it != "null" }

    private inner class Bridge {
        @JavascriptInterface
        fun onSuccess(id: String, value: String) {
            pendingCalls.remove(id)?.resumeWith(Result.success(value))
        }

        @JavascriptInterface
        fun onError(id: String, message: String) {
            val detail = message.substringBefore('\n').take(240)
            pendingCalls.remove(id)?.resumeWith(Result.failure(IOException("BotGuard JavaScript failed: $detail")))
        }
    }

    private companion object {
        const val TAG = "MoodifyPoToken"
        const val BRIDGE_NAME = "MoodifyBridge"
        const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val BOTGUARD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        const val MAX_HOME_BYTES = 8 * 1024 * 1024
        const val MAX_INTERPRETER_BYTES = 8 * 1024 * 1024
        const val MAX_INTEGRITY_RESPONSE_CHARS = 1_000_000
        const val MIN_TOKEN_TTL_SECONDS = 60L
        const val MAX_TOKEN_TTL_SECONDS = 3_600L
        const val TOKEN_EXPIRY_SAFETY_MS = 90_000L
        const val MIN_TOKEN_LENGTH = 40
        const val MAX_TOKEN_LENGTH = 2_000
        const val MAX_CACHED_VIDEO_TOKENS = 128
        const val MIN_VIEWPORT_WIDTH_PX = 360
        const val MIN_VIEWPORT_HEIGHT_PX = 640
        const val WEBVIEW_LOAD_TIMEOUT_MS = 20_000L
        const val JS_CALL_TIMEOUT_MS = 60_000L
        val JSON_PROTOBUF = "application/json+protobuf".toMediaType()
        val VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9_=.\\-]+$")
    }
}
