package com.moodify.app.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moodify.app.data.CatalogLocale
import com.moodify.app.player.InnerTubeStreamResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackPoTokenProviderLiveTest {
    @Test
    fun mintsVideoBoundPlaybackTokenInSystemWebViewWhenLiveCheckIsEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("moodifyLivePoToken") == "true")

        val context = instrumentation.targetContext
        val provider = PlaybackPoTokenProvider(
            context = context,
            httpClient = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build(),
            localeProvider = { CatalogLocale("en", "US") },
            runtimeSource = {
                context.assets.open("player/bgutils-po-token-runtime.js")
                    .bufferedReader()
                    .use { it.readText() }
            },
        )

        try {
            val token = provider.tokenFor(VIDEO_ID, knownVisitorData = null)
            assertEquals(VIDEO_ID, token.videoId)
            assertTrue("Token is too short", token.value.length >= 40)
            assertTrue("Token expired before it was returned", token.expiresAtMs > System.currentTimeMillis())
            println(
                "Live WebPO mint passed: binding=${token.videoId} tokenChars=${token.value.length} " +
                    "visitorDataPresent=${!token.visitorData.isNullOrBlank()}",
            )
        } finally {
            provider.release()
        }
    }

    @Test
    fun resolvesAndValidatesMwebAudioWithLocalPoTokenWhenLiveCheckIsEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("moodifyLivePoTokenPlayback") == "true")

        val context = instrumentation.targetContext
        val resolver = createResolver(context)
        val mwebOffset = requireNotNull(resolver.nextProfileOffset("ANDROID_TESTSUITE"))
        assertEquals("MWEB must follow the anonymous mobile clients", 6, mwebOffset)

        val stream = resolver.resolve(VIDEO_ID, profileOffset = mwebOffset)
        assertEquals("Expected the PO-token-required MWEB profile", "MWEB", stream.profileName)
        resolver.validate(stream)
        println(
            "Live MWEB playback resolution passed: profile=${stream.profileName} " +
                "mime=${stream.mimeType} itag=${stream.itag} bytes=${stream.contentLength}",
        )
    }

    @Test
    fun resolvesAndValidatesMwebAudioForKnownPublicVideoWhenLiveCheckIsEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("moodifyLivePoTokenPlayback") == "true")

        val resolver = createResolver(instrumentation.targetContext)
        val mwebOffset = requireNotNull(resolver.nextProfileOffset("ANDROID_TESTSUITE"))
        val stream = resolver.resolve(PUBLIC_VIDEO_ID, profileOffset = mwebOffset)
        assertEquals("Expected the PO-token-required MWEB profile", "MWEB", stream.profileName)
        resolver.validate(stream)
        println(
            "Live MWEB resolution passed for a public video: profile=${stream.profileName} " +
                "mime=${stream.mimeType} itag=${stream.itag} bytes=${stream.contentLength}",
        )
    }

    private fun createResolver(context: android.content.Context) = InnerTubeStreamResolver(
        httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build(),
        localeProvider = { CatalogLocale("en", "US") },
        playerRuntimeSource = {
            context.assets.open("player/youtubei-player-runtime.js")
                .bufferedReader()
                .use { it.readText() }
        },
        poTokenRuntimeSource = {
            context.assets.open("player/bgutils-po-token-runtime.js")
                .bufferedReader()
                .use { it.readText() }
        },
        context = context,
    )

    private companion object {
        const val VIDEO_ID = "YALvuUpY_b0"
        const val PUBLIC_VIDEO_ID = "dQw4w9WgXcQ"
    }
}
