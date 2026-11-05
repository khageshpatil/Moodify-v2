package com.moodify.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moodify.app.data.CatalogLocale
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MwebMedia3LiveTest {
    @Test
    fun startsAndAdvancesResolvedMwebAudioInMedia3WhenLiveCheckIsEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("moodifyLivePoTokenPlayback") == "true")

        val context = instrumentation.targetContext
        val resolver = InnerTubeStreamResolver(
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
        val mwebOffset = requireNotNull(resolver.nextProfileOffset("ANDROID_TESTSUITE"))
        val stream = resolver.resolve("YALvuUpY_b0", profileOffset = mwebOffset)
        assertEquals("Expected the PO-token-required MWEB profile", "MWEB", stream.profileName)
        resolver.validate(stream)

        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        val positionMs = AtomicLong(0L)
        val playerRef = AtomicReference<ExoPlayer>()

        instrumentation.runOnMainSync {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
            val requestProperties = buildMap {
                put("Accept", "*/*")
                put("Accept-Encoding", "identity")
                putAll(stream.requestHeaders)
            }
            val source = DefaultDataSource.Factory(
                context,
                OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(requestProperties),
            )
            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(source))
                .build()
            playerRef.set(player)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playing.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    ready.countDown()
                    playing.countDown()
                }
            })
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(stream.url)
                    .setMimeType(stream.mimeType)
                    .build(),
            )
            player.playWhenReady = true
            player.prepare()
        }

        try {
            assertTrue("Media3 did not become ready: ${failure.get()}", ready.await(60, TimeUnit.SECONDS))
            assertNotNull("Media3 failed to open the validated MWEB stream", playerRef.get())
            assertTrue("Media3 reported an error: ${failure.get()}", failure.get() == null)
            assertTrue("Media3 did not start playback: ${failure.get()}", playing.await(30, TimeUnit.SECONDS))
            Thread.sleep(3_000)
            instrumentation.runOnMainSync { positionMs.set(playerRef.get().currentPosition) }
            assertTrue("Media3 did not advance playback position: ${positionMs.get()} ms", positionMs.get() > 0L)
            println(
                "Live MWEB Media3 playback passed: format=${stream.mimeType}/${stream.itag} " +
                    "positionMs=${positionMs.get()}",
            )
        } finally {
            instrumentation.runOnMainSync { playerRef.getAndSet(null)?.release() }
        }
    }

    @Test
    fun deciphersLiveWebRemixAudioAndPlaysItInMedia3WhenLiveCheckIsEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("moodifyLiveCipheredPlayback") == "true")

        val context = instrumentation.targetContext
        val resolver = InnerTubeStreamResolver(
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
        val webRemixOffset = requireNotNull(resolver.profileOffset("WEB_REMIX"))
        // Prime the shared local WebPO minter through the known-good MWEB
        // contract first. WEB_REMIX requires the same per-video token and a
        // cold YouTube attestation-page request can be rate-limited during a
        // live test run.
        val mwebOffset = requireNotNull(resolver.nextProfileOffset("ANDROID_TESTSUITE"))
        val primedStream = resolver.resolve("YALvuUpY_b0", profileOffset = mwebOffset)
        assertEquals("Expected the known-good MWEB token bootstrap", "MWEB", primedStream.profileName)
        val stream = resolver.resolve("YALvuUpY_b0", profileOffset = webRemixOffset)
        assertEquals("Expected the ciphered WEB_REMIX player response", "WEB_REMIX", stream.profileName)
        assertTrue("Player response did not select a signatureCipher/cipher format", stream.sourceWasCiphered)
        resolver.validate(stream)

        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        val positionMs = AtomicLong(0L)
        val playerRef = AtomicReference<ExoPlayer>()

        instrumentation.runOnMainSync {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
            val requestProperties = buildMap {
                put("Accept", "*/*")
                put("Accept-Encoding", "identity")
                putAll(stream.requestHeaders)
            }
            val source = DefaultDataSource.Factory(
                context,
                OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(requestProperties),
            )
            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(source))
                .build()
            playerRef.set(player)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playing.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    ready.countDown()
                    playing.countDown()
                }
            })
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(stream.url)
                    .setMimeType(stream.mimeType)
                    .build(),
            )
            player.playWhenReady = true
            player.prepare()
        }

        try {
            assertTrue("Media3 did not become ready: ${failure.get()}", ready.await(60, TimeUnit.SECONDS))
            assertNotNull("Media3 player was not created", playerRef.get())
            assertTrue("Media3 reported an error: ${failure.get()}", failure.get() == null)
            assertTrue("Media3 did not start ciphered playback: ${failure.get()}", playing.await(30, TimeUnit.SECONDS))
            Thread.sleep(3_000)
            instrumentation.runOnMainSync { positionMs.set(playerRef.get().currentPosition) }
            assertTrue("Media3 did not advance ciphered playback: ${positionMs.get()} ms", positionMs.get() > 0L)
            println(
                "Live ciphered WEB_REMIX Media3 playback passed: format=${stream.mimeType}/${stream.itag} " +
                    "positionMs=${positionMs.get()}",
            )
        } finally {
            instrumentation.runOnMainSync { playerRef.getAndSet(null)?.release() }
        }
    }
}
