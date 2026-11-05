package com.moodify.app.player

import android.net.Uri
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Resolves YouTube's `signatureCipher` and throttling `n` values on-device.
 *
 * Player-script parsing is delegated to the pinned upstream youtubei.js AST
 * analyzer bundled as an Android asset. Only the extracted transform code is
 * evaluated in QuickJS; the complete remote player script is never executed.
 */
class LocalPlayerScriptResolver(
    private val httpClient: OkHttpClient,
    private val playerRuntimeSource: () -> String?,
) {
    private val playerScripts = ConcurrentHashMap<String, String>()
    private val jsDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Moodify-PlayerScript").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val jsMutex = Mutex()
    private var quickJs: QuickJs? = null
    private var cachedRuntimeSource: String? = null
    private var currentPlayerScript: String? = null

    suspend fun resolve(
        playerScriptUrl: String?,
        directUrl: String?,
        signatureCipher: String?,
        cipher: String?,
    ): String? = withContext(Dispatchers.IO) {
        val encodedCipher = signatureCipher ?: cipher
        val values = encodedCipher?.let(::parseCipher).orEmpty()
        val baseUrl = values["url"]?.takeIf(String::isNotBlank)
            ?: directUrl?.takeIf(String::isNotBlank)
            ?: return@withContext null
        val signature = values["s"]?.takeIf(String::isNotBlank)
        val signatureParameter = values["sp"]?.takeIf(String::isNotBlank) ?: "sig"
        val sourceUrl = Uri.parse(baseUrl)
        val nValue = sourceUrl.getQueryParameter("n")?.takeIf(String::isNotBlank)

        if (signature == null && nValue == null) return@withContext baseUrl

        val scriptUrl = playerScriptUrl?.takeIf(String::isNotBlank)
            ?: throw IOException("Player script URL is missing for a ciphered stream")
        val playerId = PLAYER_ID_PATTERN.find(Uri.decode(scriptUrl))
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IOException("Could not read the player id from the player script URL")
        val script = loadPlayerScript(scriptUrl)
        val transformed = evaluateTransform(
            playerId = playerId,
            playerScript = script,
            signature = signature,
            signatureParameter = signatureParameter,
            nValue = nValue,
        )

        var resultUrl = baseUrl
        if (signature != null) {
            val transformedSignature = transformed.signature
                ?: throw IOException("The player script did not return a transformed signature")
            resultUrl = replaceQueryParameter(resultUrl, signatureParameter, transformedSignature)
        }
        if (nValue != null) {
            val transformedN = transformed.n
                ?: throw IOException("The player script did not return a transformed n value")
            resultUrl = replaceQueryParameter(resultUrl, "n", transformedN)
        }
        resultUrl
    }

    /** Read the player signature timestamp (STS) without evaluating the remote script. */
    suspend fun signatureTimestamp(playerScriptUrl: String?): Int? = withContext(Dispatchers.IO) {
        val url = playerScriptUrl?.takeIf(String::isNotBlank) ?: return@withContext null
        val source = loadPlayerScript(url)
        val values = SIGNATURE_TIMESTAMP_PATTERN.findAll(source)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it > 0 }
            .toSet()
        values.singleOrNull()
    }

    private suspend fun evaluateTransform(
        playerId: String,
        playerScript: String,
        signature: String?,
        signatureParameter: String,
        nValue: String?,
    ): PlayerTransformResult = withContext(jsDispatcher) {
        jsMutex.withLock {
            val runtime = quickJs ?: createRuntime().also { quickJs = it }
            currentPlayerScript = playerScript
            try {
                // Pass the multi-megabyte remote script through a QuickJS host
                // binding. Embedding it as a JavaScript string literal makes
                // QuickJS parse and retain an unnecessary second AST.
                val expression = buildString {
                    append("MoodifyYoutubeScriptResolver.transform(")
                    append(JSONObject.quote(playerId))
                    append(",MoodifyPlayerInput.script,")
                    append(signature?.let(JSONObject::quote) ?: "null")
                    append(',')
                    append(JSONObject.quote(signatureParameter))
                    append(',')
                    append(nValue?.let(JSONObject::quote) ?: "null")
                    append(')')
                }
                val encodedResult = runtime.evaluate<String>(expression, "moodify-player-transform.js")
                val result = JSONObject(encodedResult)
                if (!result.optBoolean("ok")) {
                    throw IOException(result.optString("error", "Player-script transform failed"))
                }
                PlayerTransformResult(
                    signature = result.optNullableString("signature"),
                    n = result.optNullableString("n"),
                )
            } catch (failure: Throwable) {
                val shouldDiscard = failure is QuickJsInterruptedException ||
                    (failure is QuickJsException &&
                        failure.message.orEmpty().contains("out of memory", ignoreCase = true))
                if (shouldDiscard && quickJs === runtime) {
                    quickJs = null
                    runCatching { runtime.close() }
                }
                throw failure
            } finally {
                currentPlayerScript = null
                if (!runtime.isClosed) runCatching { runtime.gc() }
            }
        }
    }

    private suspend fun createRuntime(): QuickJs {
        val source = cachedRuntimeSource ?: playerRuntimeSource()
            ?.takeIf(String::isNotBlank)
            ?.also { cachedRuntimeSource = it }
            ?: throw IOException("The bundled YouTube player runtime is unavailable")
        val runtime = QuickJs.create(jsDispatcher)
        try {
            runtime.memoryLimit = QUICK_JS_MEMORY_LIMIT_BYTES
            runtime.evaluationTimeoutMillis = QUICK_JS_TIMEOUT_MS
            runtime.evaluate<Unit>(source, "youtubei-player-runtime.js")
            runtime.define("MoodifyPlayerInput") {
                property<String>("script") {
                    getter { currentPlayerScript.orEmpty() }
                }
            }
            return runtime
        } catch (failure: Throwable) {
            runCatching { runtime.close() }
            throw failure
        }
    }

    private fun loadPlayerScript(url: String): String {
        playerScripts[url]?.let { return it }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("User-Agent", PLAYER_SCRIPT_USER_AGENT)
            .build()

        val script = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Player script HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Player script response was empty")
            if (body.contentLength() > MAX_SCRIPT_BYTES) {
                throw IOException("Player script exceeds the 8 MiB safety limit")
            }
            body.byteStream().use(::readBoundedUtf8)
        }
        if (script.isBlank()) throw IOException("Player script response was empty")

        if (playerScripts.size >= MAX_CACHED_PLAYER_SCRIPTS) {
            playerScripts.keys.firstOrNull()?.let(playerScripts::remove)
        }
        return playerScripts.putIfAbsent(url, script) ?: script
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > MAX_SCRIPT_BYTES) {
                throw IOException("Player script exceeds the 8 MiB safety limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun replaceQueryParameter(url: String, key: String, value: String): String {
        val parsed = Uri.parse(url)
        return parsed.buildUpon().clearQuery().apply {
            parsed.queryParameterNames
                .filterNot { it == key }
                .forEach { name ->
                    parsed.getQueryParameters(name).forEach { parameter ->
                        appendQueryParameter(name, parameter)
                    }
                }
            appendQueryParameter(key, value)
        }.build().toString()
    }

    private fun parseCipher(value: String): Map<String, String> = value.trimStart('?')
        .split('&')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            Uri.decode(part.substring(0, separator)) to Uri.decode(part.substring(separator + 1))
        }
        .toMap()

    private fun JSONObject.optNullableString(name: String): String? = optString(name)
        .takeIf { it.isNotBlank() && it != "null" }

    private data class PlayerTransformResult(
        val signature: String?,
        val n: String?,
    )

    private companion object {
        val PLAYER_ID_PATTERN = Regex("/s/player/([A-Za-z0-9_-]{1,80})/")
        val SIGNATURE_TIMESTAMP_PATTERN = Regex("signatureTimestamp\\s*:\\s*(\\d{5,})")
        const val MAX_SCRIPT_BYTES = 8 * 1024 * 1024
        const val MAX_CACHED_PLAYER_SCRIPTS = 2
        const val QUICK_JS_MEMORY_LIMIT_BYTES = 384L * 1024L * 1024L
        const val QUICK_JS_TIMEOUT_MS = 60_000L
        const val PLAYER_SCRIPT_USER_AGENT =
            "Mozilla/5.0 (Android 15; Mobile) AppleWebKit/537.36 Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}
