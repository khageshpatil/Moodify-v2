package com.moodify.app.player

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LocalPlayerScriptResolverTest {
    @Test
    fun currentYoutubePlayerScriptTransformsNWhenLiveCheckIsEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("moodifyLiveYouTube") == "true")

        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val userAgent = "Mozilla/5.0 (Android 15; Mobile) AppleWebKit/537.36 Chrome/141.0.0.0 Mobile Safari/537.36"
        val iframeApi = Request.Builder()
            .url("https://www.youtube.com/iframe_api")
            .header("User-Agent", userAgent)
            .build()
        val iframeSource = client.newCall(iframeApi).execute().use { response ->
            assertTrue("YouTube iframe API HTTP ${response.code}", response.isSuccessful)
            response.body?.string().orEmpty()
        }.replace("\\/", "/")
        val playerId = Regex("/s/player/([A-Za-z0-9_-]{1,80})/")
            .find(iframeSource)
            ?.groupValues
            ?.get(1)
        assertNotNull("Could not find the current YouTube player id", playerId)

        val scriptRequest = Request.Builder()
            .url("https://www.youtube.com/s/player/$playerId/player_es6.vflset/en_US/base.js")
            .header("User-Agent", userAgent)
            .build()
        val playerScript = client.newCall(scriptRequest).execute().use { response ->
            assertTrue("YouTube player script HTTP ${response.code}", response.isSuccessful)
            response.body?.string().orEmpty()
        }
        assertFalse("Current player script is empty", playerScript.isBlank())
        println("Live YouTube player script characters=${playerScript.length} playerId=$playerId")
        assertTrue("Current player script exceeds 8 MiB", playerScript.length < 8 * 1024 * 1024)

        val context = instrumentation.targetContext
        val runtimeSource = context.assets.open("player/youtubei-player-runtime.js")
            .bufferedReader()
            .use { it.readText() }
        val sampleN = "MoodifyLiveN123456"
        val sampleSignature = "MoodifyLiveSignature123"
        val cipher = "url=${Uri.encode("https://cdn.example/audio?keep=1&n=$sampleN")}" +
            "&s=${Uri.encode(sampleSignature)}&sp=sig"
        val resolver = LocalPlayerScriptResolver(
            httpClient = scriptClient(playerScript),
            playerRuntimeSource = { runtimeSource },
        )
        val signatureTimestamp = resolver.signatureTimestamp(
            "https://www.youtube.com/s/player/$playerId/player_es6.vflset/en_US/base.js",
        )
        assertNotNull("Current YouTube player script did not expose a unique signature timestamp", signatureTimestamp)
        val resolvedUrl = resolver.resolve(
            playerScriptUrl = "https://www.youtube.com/s/player/$playerId/player_es6.vflset/en_US/base.js",
            directUrl = null,
            signatureCipher = cipher,
            cipher = null,
        )
        val resolved = Uri.parse(requireNotNull(resolvedUrl))
        val transformedN = resolved.getQueryParameter("n")
        val transformedSignature = resolved.getQueryParameter("sig")
        println("Live production resolver passed: scriptChars=${playerScript.length} playerId=$playerId signatureTimestamp=$signatureTimestamp")
        assertTrue("Current script returned an empty n transform", !transformedN.isNullOrBlank())
        assertFalse("Current script returned the untransformed n value", transformedN == sampleN)
        assertTrue("Current script returned an empty signature transform", !transformedSignature.isNullOrBlank())
        assertFalse("Current script returned the untransformed signature", transformedSignature == sampleSignature)
    }

    @Test
    fun bundledUpstreamAnalyzerTransformsSignatureAndNInsideQuickJs() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = context.assets.open("player/youtubei-player-runtime.js")
            .bufferedReader()
            .use { it.readText() }
        val playerScript = playerScriptFixture()
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val runtime = QuickJs.create(executor)
        try {
            runtime.memoryLimit = 192L * 1024L * 1024L
            runtime.evaluationTimeoutMillis = 10_000L
            runtime.evaluate<Unit>(source, "youtubei-player-runtime.js")
            val resultJson = runtime.evaluate<String>(
                "MoodifyYoutubeScriptResolver.transform(" +
                    "\"fixture-player-id\"," +
                    JSONObjectQuote.quote(playerScript) + "," +
                    "\"abcdef\",\"signature\",\"n123\")",
                "player-transform-test.js",
            )
            val result = org.json.JSONObject(resultJson)

            assertEquals("fedcba", result.getString("signature"))
            assertEquals("321n", result.getString("n"))
        } finally {
            runtime.close()
            executor.close()
        }
    }

    @Test
    fun currentYoutubeScriptTransformsInSystemV8WhenLiveCheckIsEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("moodifyLiveWebView") == "true")

        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val userAgent = "Mozilla/5.0 (Android 15; Mobile) AppleWebKit/537.36 Chrome/141.0.0.0 Mobile Safari/537.36"
        val apiSource = client.newCall(
            Request.Builder()
                .url("https://www.youtube.com/iframe_api")
                .header("User-Agent", userAgent)
                .build(),
        ).execute().use { response ->
            assertTrue("YouTube iframe API HTTP ${response.code}", response.isSuccessful)
            response.body?.string().orEmpty().replace("\\/", "/")
        }
        val playerId = Regex("/s/player/([A-Za-z0-9_-]{1,80})/")
            .find(apiSource)?.groupValues?.get(1)
        assertNotNull("Could not find the current YouTube player id", playerId)
        val source = client.newCall(
            Request.Builder()
                .url("https://www.youtube.com/s/player/$playerId/player_es6.vflset/en_US/base.js")
                .header("User-Agent", userAgent)
                .build(),
        ).execute().use { response ->
            assertTrue("YouTube player script HTTP ${response.code}", response.isSuccessful)
            response.body?.string().orEmpty()
        }
        assertTrue("Current player script exceeds 8 MiB", source.length < 8 * 1024 * 1024)

        val runtimeSource = instrumentation.targetContext.assets
            .open("player/youtubei-player-runtime.js")
            .bufferedReader()
            .use { it.readText() }
        val webViewRef = AtomicReference<WebView>()
        val pageLoaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webViewRef.set(WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded.countDown()
                    }
                }
                loadDataWithBaseURL(
                    "https://www.youtube.com/",
                    "<html><head></head><body></body></html>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            })
        }
        val webView = requireNotNull(webViewRef.get())
        try {
            assertTrue("System WebView page did not load", pageLoaded.await(20, TimeUnit.SECONDS))

            val runtimeLoaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(runtimeSource) { runtimeLoaded.countDown() }
            }
            assertTrue("System WebView runtime did not load", runtimeLoaded.await(20, TimeUnit.SECONDS))

            val sampleN = "MoodifyV8LiveN123456"
            val sampleSignature = "MoodifyV8LiveSignature123"
            val expression = "MoodifyYoutubeScriptResolver.transform(" +
                JSONObjectQuote.quote(requireNotNull(playerId)) + "," +
                JSONObjectQuote.quote(source) + "," +
                JSONObjectQuote.quote(sampleSignature) + ",\"sig\"," +
                JSONObjectQuote.quote(sampleN) + ")"
            val transformedJson = AtomicReference<String>()
            val transformComplete = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(expression) { result ->
                    transformedJson.set(result)
                    transformComplete.countDown()
                }
            }
            assertTrue("System WebView transform timed out", transformComplete.await(60, TimeUnit.SECONDS))
            val returnedJson = org.json.JSONTokener(requireNotNull(transformedJson.get())).nextValue() as String
            val result = org.json.JSONObject(returnedJson)
            assertNotEquals(sampleN, result.optString("n"))
            assertNotEquals(sampleSignature, result.optString("signature"))
            println("System WebView V8 transform passed for live scriptChars=${source.length} playerId=$playerId")
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    @Test
    fun resolvesSignatureCipherAndNParameterLocally() = runBlocking {
        val scriptUrl = "https://www.youtube.com/s/player/test-player/base.js"
        val resolver = resolverWithScript(playerScriptFixture())

        val resolved = resolver.resolve(
            playerScriptUrl = scriptUrl,
            directUrl = null,
            signatureCipher = "url=https%3A%2F%2Fcdn.example%2Faudio%3Ffoo%3Dbar%26n%3Dnabc" +
                "&s=abcdef&sp=signature",
            cipher = null,
        )

        assertNotNull(resolved)
        val resolvedUri = Uri.parse(resolved)
        assertEquals("https", resolvedUri.scheme)
        assertEquals("cdn.example", resolvedUri.host)
        assertEquals("bar", resolvedUri.getQueryParameter("foo"))
        assertEquals("cban", resolvedUri.getQueryParameter("n"))
        assertEquals("fedcba", resolvedUri.getQueryParameter("signature"))
    }

    @Test
    fun extractsUniqueSignatureTimestampFromPlayerScript() = runBlocking {
        val scriptUrl = "https://www.youtube.com/s/player/test-player/base.js"
        val resolver = resolverWithScript(playerScriptFixture())

        assertEquals(123456, resolver.signatureTimestamp(scriptUrl))
    }

    @Test
    fun transformsNOnDirectUrlAndLeavesOrdinarySignedUrlAlone() = runBlocking {
        val resolver = resolverWithScript(playerScriptFixture())

        assertEquals(
            "https://cdn.example/audio?keep=1&n=cba",
            resolver.resolve(
                playerScriptUrl = "https://www.youtube.com/s/player/test-player/base.js",
                directUrl = "https://cdn.example/audio?keep=1&n=abc",
                signatureCipher = null,
                cipher = null,
            ),
        )
        assertEquals(
            "https://cdn.example/audio?keep=1",
            resolver.resolve(
                playerScriptUrl = null,
                directUrl = "https://cdn.example/audio?keep=1",
                signatureCipher = null,
                cipher = null,
            ),
        )
    }

    private fun scriptClient(script: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(script.toResponseBody("application/javascript".toMediaType()))
                .build()
        }
        .build()

    private fun resolverWithScript(script: String) = LocalPlayerScriptResolver(
        httpClient = scriptClient(script),
        playerRuntimeSource = {
            InstrumentationRegistry.getInstrumentation().targetContext.assets
                .open("player/youtubei-player-runtime.js")
                .bufferedReader()
                .use { it.readText() }
        },
    )

    private fun playerScriptFixture(): String = """
        (function(root) {
          var decipher = function(a, b = "", c = "") {
            a = new window.URL(a);
            a.searchParams.set("alr", "yes");
            if (c) a.searchParams.set(b, c.split("").reverse().join(""));
            const n = a.searchParams.get("n");
            if (n) a.searchParams.set("n", n.split("").reverse().join(""));
            return Object.create({
              get: function(name) { return a.searchParams.get(name); },
              set: function(name, value) { return a.searchParams.set(name, value); },
              append: function() {},
            });
          };
          var playerConfig = function() { return { signatureTimestamp: 123456 }; };
        })(window);
    """.trimIndent()

    private object JSONObjectQuote {
        fun quote(value: String): String = org.json.JSONObject.quote(value)
    }
}
