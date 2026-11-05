package com.moodify.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.DataSpec
import android.os.Looper
import kotlinx.coroutines.runBlocking
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.moodify.app.data.CatalogLocale
import com.moodify.app.data.SettingsStore
import com.moodify.app.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Singleton playback engine that resolves YouTube audio through native
 * InnerTube direct-CDN playback using Media3 ExoPlayer.
 *
 * Background playback is handled by [MoodifyPlaybackService] (foreground service).
 */
@UnstableApi
class PlaybackManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var _instance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return _instance ?: synchronized(this) {
                _instance ?: PlaybackManager(context.applicationContext).also { _instance = it }
            }
        }

        // Kept for access from ViewModel/Service that already have the instance
        val instance: PlaybackManager
            get() = _instance ?: error("PlaybackManager not yet initialized — call getInstance(context) first")

        private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        private const val DIRECT_PROFILE_RETRY_COUNT = 3
        private const val MIN_BUFFER_MS = 20_000
        private const val MAX_BUFFER_MS = 60_000
        private const val BUFFER_FOR_PLAYBACK_MS = 750
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_500
        private const val MAX_STREAM_CONTRACTS = 64
        private const val RADIO_LOOKAHEAD = 2
        private const val STREAM_SCHEME = "moodify-stream"
        private const val CONTRACT_REFRESH_MARGIN_MS = 60_000L
        private const val ITAG_METADATA_KEY = "moodify_itag"
        private const val MAX_BOOST_DB = 6.0
        private const val RADIO_BATCH_SIZE = 25
        private const val RADIO_PLAYLIST_PREFIX = "RDAMVM"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile
    private var playbackLocale = CatalogLocale.fromDefault()
    private val persistence = PlaybackPersistence(context)
    private val historyStore = ListeningHistoryStore(context)
    private val settingsStore = SettingsStore(context)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(PlaybackProgress())
    /** Position/duration/buffer, updated ~2x per second while playing. Collect only in leaf UI. */
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()
    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    /** Callback set by Activity so PlaybackManager can start the foreground service. */
    var serviceStarter: (() -> Unit)? = null
    private var serviceStarted = false
    private var loadGeneration = 0L
    private val refreshAttempts = mutableMapOf<String, Int>()
    private val streamResolver = InnerTubeStreamResolver(
        localeProvider = { playbackLocale },
        loadVisitorDataFromStore = { settingsStore.getVisitorData() },
        saveVisitorDataToStore = { settingsStore.setVisitorData(it) },
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
    private val downloadManager = MoodifyDownloadManager(context, streamResolver)
    private val playbackCache = PlaybackCache(context)
    private var preloadJob: Job? = null
    private var loadJob: Job? = null
    private var lastPersistAtMs = 0L
    private var historyRecordedForTrack: String? = null
    private enum class PlaybackRoute { NONE, LOCAL, DIRECT_CDN }
    @Volatile
    private var activePlaybackRoute = PlaybackRoute.NONE
    private val streamContracts = ConcurrentHashMap<String, StreamContract>()
    private val routeByCacheKey = ConcurrentHashMap<String, PlaybackRoute>()
    private val mediaHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url.host.endsWith("googlevideo.com")) {
                android.util.Log.d(
                    "MoodifyHttp",
                    "CDN status=${response.code} rangeRequested=${!request.header("Range").isNullOrBlank()} " +
                        "itag=${request.url.queryParameter("itag")}"
                )
            }
            response
        }
        .build()
    private val directHttpDataSourceFactory = OkHttpDataSource.Factory(mediaHttpClient)
        .setDefaultRequestProperties(
            mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "identity"
            )
        )
    /**
     * Cache first, then lazy resolution. Queue items carry a
     * `moodify-stream://track/<videoId>` placeholder and the stable cache key
     * `moodify:<videoId>`, so a fully cached song never touches the network
     * and a stream URL is only resolved when ExoPlayer actually needs bytes.
     */
    private val dataSourceFactory: DataSource.Factory = playbackCache.factory(
        ResolvingDataSource.Factory(DefaultDataSource.Factory(context, directHttpDataSourceFactory)) { dataSpec ->
            resolvePlaybackDataSpec(dataSpec)
        }
    )

    // ── ExoPlayer ────────────────────────────────────────────────────────────

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
        true
    ).setHandleAudioBecomingNoisy(true).build().apply {
        addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (_state.value.sleepAtEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    exoPlayer.pause()
                    _state.update { it.copy(sleepAtEndOfTrack = false) }
                }
                val mediaId = mediaItem?.mediaId ?: return
                val track = _state.value.queue.firstOrNull { it.id == mediaId } ?: return
                val cacheKey = mediaItem.localConfiguration?.customCacheKey ?: mediaId
                activePlaybackRoute = routeByCacheKey[cacheKey] ?: activePlaybackRoute
                val contract = streamContracts[cacheKey]
                recordDiagnostics(
                    event = "Queue item prepared",
                    status = "Preparing",
                    route = routeLabel(activePlaybackRoute),
                    clientProfile = contract?.profileName,
                    mimeType = contract?.mimeType,
                    itag = contract?.itag,
                    bitrateBps = contract?.bitrateBps,
                    expiresAtMs = contract?.expiresAtMs,
                    playerErrorCode = null,
                    httpStatusCode = null,
                    recoveryAction = null,
                )
                historyRecordedForTrack = null
                refreshAttempts.remove(track.id)
                val resolvedDurationMs = streamContracts[cacheKey]?.durationMs
                _state.update {
                    it.copy(
                        currentTrack = track,
                        currentIndex = it.queue.indexOfFirst { queued -> queued.id == track.id }
                            .coerceAtLeast(0),
                        isLoading = true,
                        isPlaying = exoPlayer.isPlaying,
                        positionMs = 0L,
                        durationMs = resolvedDurationMs
                            ?.takeIf { duration -> duration > 0L }
                            ?: (track.durationSeconds * 1000L),
                        errorMessage = null,
                        isResolving = false,
                        recoveryAttempt = 0,
                    )
                }
                publishProgress(positionMs = 0L)
                applyLoudnessNormalization()
                preloadNext()
                maybeExtendWithRadio()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                publishProgress()
                if (isPlaying) {
                    recordDiagnostics(event = "Playback started", status = "Playing", recoveryAction = null)
                } else if (exoPlayer.playbackState == Player.STATE_READY) {
                    recordDiagnostics(event = "Playback paused", status = "Paused")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        recordDiagnostics(event = "Buffering", status = "Buffering")
                        _state.update { it.copy(isLoading = true) }
                    }
                    Player.STATE_READY     -> {
                        recordDiagnostics(
                            event = "Media source ready",
                            status = if (exoPlayer.isPlaying) "Playing" else "Ready",
                            recoveryAction = null,
                        )
                        _state.update {
                            it.copy(
                                isLoading = false,
                                isResolving = false,
                                recoveryAttempt = 0,
                                errorMessage = null,
                                durationMs = exoPlayer.duration.takeIf { duration -> duration > 0L }
                                    ?: it.durationMs,
                                bufferedPositionMs = exoPlayer.bufferedPosition
                            )
                        }
                        publishProgress()
                        // History is written outside the state update so the
                        // database write never runs inside a CAS retry loop.
                        _state.value.currentTrack?.let { track ->
                            if (historyRecordedForTrack != track.id) {
                                historyRecordedForTrack = track.id
                                scope.launch { runCatching { historyStore.record(track) } }
                            }
                        }
                    }
                    Player.STATE_ENDED     -> {
                        _state.update { it.copy(isPlaying = false, isLoading = false) }
                        val current = _state.value
                        if (current.sleepAtEndOfTrack) {
                            _state.update { it.copy(sleepAtEndOfTrack = false) }
                            persistState()
                        } else if (current.isRepeat && current.currentTrack != null) {
                            loadAndPlay(current.currentTrack, ++loadGeneration)
                        } else if (current.isShuffle || current.currentIndex < current.queue.lastIndex) {
                            playNext()
                        } else if (autoplayEnabled) {
                            // End of the queue: keep the mood going with the song's radio.
                            maybeExtendWithRadio(force = true) { playNext() }
                        } else {
                            persistState()
                        }
                    }
                    Player.STATE_IDLE      -> _state.update { it.copy(isLoading = false) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = _state.value.currentTrack
                val attempts = current?.let { refreshAttempts[it.id] ?: 0 } ?: 0
                val currentMediaItem = exoPlayer.currentMediaItem
                val cacheKey = currentMediaItem?.localConfiguration?.customCacheKey
                    ?: currentMediaItem?.mediaId
                val rejectedContract = cacheKey?.let(streamContracts::get)
                val rejectedProfileName = rejectedContract?.profileName
                val rejectedHttpStatus = httpStatusCode(error)
                if (current != null) {
                    // Signed CDN URLs and their token context must never be
                    // retried unchanged after a transport failure.
                    streamResolver.invalidate(current.id)
                }
                if (current != null &&
                    activePlaybackRoute != PlaybackRoute.LOCAL &&
                    cacheKey != null &&
                    shouldDiscardPlaybackCache(error)
                ) {
                    // A partial CDN response can leave a valid-looking but
                    // truncated cache span. Remove it before resolving again.
                    playbackCache.remove(cacheKey)
                    streamContracts.remove(cacheKey)
                    routeByCacheKey.remove(cacheKey)
                }
                if (current != null &&
                    activePlaybackRoute == PlaybackRoute.DIRECT_CDN &&
                    attempts < DIRECT_PROFILE_RETRY_COUNT
                ) {
                    // Refresh the same profile once when the signed URL is
                    // expired/gone, then rotate clients if the refreshed
                    // contract is rejected too.
                    val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    refreshAttempts[current.id] = attempts + 1
                    val nextProfileOffset = PlaybackRetryPolicy.profileOffsetAfterFailure(
                        httpStatusCode = rejectedHttpStatus,
                        contractExpiresAtMs = rejectedContract?.expiresAtMs,
                        nowMs = System.currentTimeMillis(),
                        priorRetryCount = attempts,
                        currentProfileOffset = rejectedProfileName?.let(streamResolver::profileOffset),
                        nextProfileOffset = rejectedProfileName?.let(streamResolver::nextProfileOffset),
                        fallbackOffset = attempts + 1,
                    )
                    val retryingSameProfile = rejectedProfileName != null &&
                        streamResolver.profileOffset(rejectedProfileName) == nextProfileOffset
                    recordDiagnostics(
                        event = if (retryingSameProfile) "Refreshing rejected stream" else "Rotating playback client",
                        status = "Recovering",
                        clientProfile = rejectedProfileName,
                        expiresAtMs = rejectedContract?.expiresAtMs,
                        playerErrorCode = error.errorCode,
                        httpStatusCode = rejectedHttpStatus,
                        recoveryAction = if (retryingSameProfile) {
                            "Refresh stream with the same client"
                        } else {
                            "Retry with another client"
                        },
                    )
                    _state.update {
                        it.copy(
                            isLoading = true,
                            isPlaying = false,
                            positionMs = resumePositionMs,
                            errorMessage = null,
                            recoveryAttempt = attempts + 1,
                        )
                    }
                    loadAndPlay(
                        current,
                        ++loadGeneration,
                        resumePositionMs,
                        profileOffset = nextProfileOffset,
                    )
                    return
                }
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "No internet connection."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Audio stream unavailable; retrying another YouTube client may help."
                    PlaybackException.ERROR_CODE_TIMEOUT ->
                        "Connection timed out."
                    else -> "Playback failed. Please try again."
                }
                android.util.Log.e(
                    "MoodifyPlayer",
                    "ExoPlayer error code=${error.errorCode}, httpStatus=$rejectedHttpStatus"
                )
                recordDiagnostics(
                    event = "Playback failed",
                    status = "Error",
                    clientProfile = rejectedProfileName,
                    expiresAtMs = rejectedContract?.expiresAtMs,
                    playerErrorCode = error.errorCode,
                    httpStatusCode = rejectedHttpStatus,
                    recoveryAction = "No automatic retries remain",
                )
                _state.update { it.copy(isLoading = false, isPlaying = false, errorMessage = msg) }
                persistState()
            }
        })
    }

    // ── Progress timer ───────────────────────────────────────────────────────

    init {
        // Restore the last queue and download records off the main thread so
        // constructing the player never waits on SQLite. If the user starts
        // something before the restore lands, their choice wins.
        scope.launch {
            val restored = runCatching { persistence.restorePlaybackState() }.getOrNull()
            if (restored != null && _state.value.currentTrack == null) {
                _state.update { current -> restored.copy(downloadedTrackIds = current.downloadedTrackIds) }
                _progress.value = PlaybackProgress(
                    positionMs = restored.positionMs,
                    durationMs = restored.durationMs,
                )
                android.util.Log.i(
                    "MoodifyPlayer",
                    "Restored queue after process start: tracks=${restored.queue.size}, " +
                        "positionMs=${restored.positionMs}, durationMs=${restored.durationMs}"
                )
            }
        }
        scope.launch {
            val downloadedIds = runCatching { downloadManager.all().map { record -> record.id }.toSet() }
                .getOrDefault(emptySet())
            _state.update { it.copy(downloadedTrackIds = it.downloadedTrackIds + downloadedIds) }
        }
        // Poll ExoPlayer every 500 ms for position updates. Only the
        // lightweight [progress] flow changes on each tick; [state] is touched
        // only when the duration actually changes, so the app tree does not
        // recompose twice a second while music plays.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                if (_state.value.isPlaying) {
                    publishProgress()
                    val dur = exoPlayer.duration.takeIf { it > 0 }
                    if (dur != null && dur != _state.value.durationMs) {
                        _state.update { it.copy(durationMs = dur) }
                    }
                    if (System.currentTimeMillis() - lastPersistAtMs >= 5_000L) {
                        persistState()
                    }
                }
            }
        }
    }

    // ── Playback Controls ────────────────────────────────────────────────────

    fun playTrack(track: Track, newQueue: List<Track> = listOf(track)) {
        if (!YOUTUBE_VIDEO_ID_REGEX.matches(track.id)) {
            setError("This track cannot be played. Please choose another result.")
            return
        }

        val index = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        // A different set of songs is a new listening context: forget the old radio.
        val sameContext = newQueue.map { it.id } == _state.value.queue.map { it.id }
        if (!sameContext) resetRadio()
        _state.update {
            it.copy(
                currentTrack = track,
                queue = newQueue,
                autoplayFromIndex = if (sameContext) it.autoplayFromIndex else null,
                currentIndex = index,
                isLoading = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = track.durationSeconds * 1000L,
                errorMessage = null,
                recoveryAttempt = 0,
            )
        }
        publishProgress(positionMs = 0L, durationMs = track.durationSeconds * 1000L)

        // Ensure foreground service is running before we start (keeps process alive)
        ensureServiceStarted()

        refreshAttempts.remove(track.id)
        activePlaybackRoute = PlaybackRoute.NONE
        persistState()
        loadAndPlay(track, ++loadGeneration)
        maybeExtendWithRadio()
    }

    // ── Autoplay radio ───────────────────────────────────────────────────────

    private val catalogClient = com.moodify.app.data.InnerTubeCatalogClient(localeProvider = { playbackLocale })

    /** When true, the queue is topped up with the current song's radio before it runs out. */
    @Volatile
    var autoplayEnabled: Boolean = true

    private val loudnessByTrack = ConcurrentHashMap<String, Double>()
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    /**
     * Evens out volume between songs: louder-than-reference tracks are turned
     * down with the player volume, quieter ones get a gentle boost (max +6 dB)
     * through LoudnessEnhancer. Tracks without loudness data play unchanged.
     */
    private fun applyLoudnessNormalization() {
        val trackId = _state.value.currentTrack?.id
        val loudness = if (normalizeVolume) trackId?.let(loudnessByTrack::get) else null
        val adjustmentDb = loudness?.let { -it } ?: 0.0
        if (adjustmentDb <= 0.0) {
            exoPlayer.volume = Math.pow(10.0, adjustmentDb / 20.0).toFloat().coerceIn(0.1f, 1f)
            setEnhancerGainMb(0)
        } else {
            exoPlayer.volume = 1f
            setEnhancerGainMb((adjustmentDb.coerceAtMost(MAX_BOOST_DB) * 100).toInt())
        }
    }

    private fun setEnhancerGainMb(gainMb: Int) {
        runCatching {
            if (gainMb <= 0) {
                loudnessEnhancer?.enabled = false
                return
            }
            val sessionId = exoPlayer.audioSessionId
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
            val enhancer = loudnessEnhancer ?: android.media.audiofx.LoudnessEnhancer(sessionId).also { loudnessEnhancer = it }
            enhancer.setTargetGain(gainMb)
            enhancer.enabled = true
        }.onFailure {
            // Some devices do not support the effect; volume levelling still applies.
            loudnessEnhancer = null
        }
    }

    /** Applies YouTube loudness data so tracks play at a similar level. */
    @Volatile
    var normalizeVolume: Boolean = true
        set(value) {
            field = value
            scope.launch { applyLoudnessNormalization() }
        }

    private var radioJob: Job? = null
    private var radioContinuation: String? = null

    private fun resetRadio() {
        radioJob?.cancel()
        radioJob = null
        radioContinuation = null
    }

    /**
     * Plays [track] and fills the queue with its YouTube Music radio mix,
     * regardless of the autoplay setting.
     */
    fun startRadio(track: Track) {
        playTrack(track, listOf(track))
        maybeExtendWithRadio(force = true)
    }

    /**
     * Appends radio songs when the queue is close to its end. Continues the
     * same radio (via its continuation token) once started, so a session can
     * play on indefinitely. [onAppended] runs on the main thread after new
     * songs land (used to resume after the queue ended).
     */
    private fun maybeExtendWithRadio(force: Boolean = false, onAppended: (() -> Unit)? = null) {
        if (!autoplayEnabled && !force) return
        val snapshot = _state.value
        val seed = snapshot.currentTrack ?: return
        val remaining = snapshot.queue.size - 1 - snapshot.currentIndex
        if (!force && remaining > RADIO_LOOKAHEAD) return
        if (radioJob?.isActive == true) return
        radioJob = scope.launch {
            _state.update { it.copy(isLoadingRadio = true) }
            try {
                val continuation = radioContinuation
                val page = if (continuation != null) {
                    catalogClient.upNext(videoId = null, continuation = continuation)
                } else {
                    val first = catalogClient.upNext(videoId = seed.id, playlistId = RADIO_PLAYLIST_PREFIX + seed.id)
                    val automix = first.automixPlaylistId
                    if (first.tracks.size <= 1 && automix != null) {
                        catalogClient.upNext(videoId = seed.id, playlistId = automix)
                    } else first
                }
                radioContinuation = page.continuation
                val queued = _state.value.queue.mapTo(HashSet()) { it.id }
                val additions = page.tracks
                    .filter { it.id !in queued && YOUTUBE_VIDEO_ID_REGEX.matches(it.id) }
                    .take(RADIO_BATCH_SIZE)
                if (additions.isNotEmpty()) {
                    _state.update { current ->
                        current.copy(
                            queue = current.queue + additions,
                            autoplayFromIndex = current.autoplayFromIndex ?: current.queue.size,
                        )
                    }
                    persistState()
                    preloadNext()
                    onAppended?.invoke()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("MoodifyPlayer", "Radio fetch failed (${error::class.simpleName})")
            } finally {
                _state.update { it.copy(isLoadingRadio = false) }
            }
        }
    }

    fun togglePlayPause() {
        if (_state.value.currentTrack == null) return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            persistState()
        } else if (exoPlayer.currentMediaItem?.localConfiguration?.uri
                ?.takeUnless { it == Uri.EMPTY } != null
        ) {
            exoPlayer.play()
        } else {
            val track = _state.value.currentTrack ?: return
            ensureServiceStarted()
            loadAndPlay(track, ++loadGeneration, _state.value.positionMs)
        }
    }

    fun playNext() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        val nextIndex = if (current.isShuffle) {
            current.queue.indices.filter { it != current.currentIndex }.randomOrNull()
                ?: current.currentIndex
        } else {
            current.currentIndex + 1
        }
        val nextTrack = current.queue.getOrNull(nextIndex) ?: return
        val queuedNext = exoPlayer.nextMediaItemIndex
            .takeIf { it != C.INDEX_UNSET }
            ?.let(exoPlayer::getMediaItemAt)
        if (!current.isShuffle && queuedNext?.mediaId == nextTrack.id) {
            // The next item has already been resolved and prepared while the
            // current song was playing. Handoff through ExoPlayer's timeline
            // instead of resolving and preparing a new player item.
            exoPlayer.seekToNext()
            exoPlayer.play()
        } else {
            playTrack(nextTrack, current.queue)
        }
    }

    fun playPrevious() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        val prevIndex = if (current.currentIndex - 1 < 0) current.queue.size - 1
                        else current.currentIndex - 1
        current.queue.getOrNull(prevIndex)?.let { playTrack(it, current.queue) }
    }

    fun seekTo(positionMs: Long) {
        val safe = positionMs.coerceAtLeast(0L)
        val current = _state.value.currentTrack
        if (current == null) return

        android.util.Log.d("MoodifyPlayer", "Seeking to ${safe}ms")
        if ((activePlaybackRoute == PlaybackRoute.LOCAL ||
                activePlaybackRoute == PlaybackRoute.DIRECT_CDN) &&
            exoPlayer.currentMediaItem != null
        ) {
            // Direct CDN playback is a byte-range resource. Let Media3 issue
            // the correct range request for a native seek.
            exoPlayer.seekTo(safe)
            _state.update { it.copy(positionMs = safe, errorMessage = null) }
            publishProgress(positionMs = safe)
            persistState()
        } else {
            ensureServiceStarted()
            _state.update { it.copy(positionMs = safe, isLoading = true, errorMessage = null) }
            publishProgress(positionMs = safe)
            loadAndPlay(
                current,
                ++loadGeneration,
                safe
            )
        }
    }

    fun toggleShuffle() {
        _state.update { it.copy(isShuffle = !it.isShuffle) }
        persistState()
    }

    fun toggleRepeat() {
        _state.update { it.copy(isRepeat = !it.isRepeat) }
        persistState()
    }

    fun addToQueue(track: Track, playNext: Boolean = false) {
        _state.update { current ->
            if (current.queue.any { it.id == track.id }) return@update current
            val insertionIndex = if (playNext) {
                (current.currentIndex + 1).coerceIn(0, current.queue.size)
            } else {
                current.queue.size
            }
            current.copy(
                queue = current.queue.toMutableList().apply { add(insertionIndex, track) }
            )
        }
        persistState()
    }

    fun removeFromQueue(index: Int) {
        _state.update { current ->
            if (index !in current.queue.indices || index == current.currentIndex) return@update current
            val queue = current.queue.toMutableList().apply { removeAt(index) }
            val nextIndex = when {
                queue.isEmpty() -> -1
                index < current.currentIndex -> current.currentIndex - 1
                else -> current.currentIndex.coerceAtMost(queue.lastIndex)
            }
            current.copy(
                queue = queue,
                currentIndex = nextIndex,
                autoplayFromIndex = current.autoplayFromIndex?.let { from -> if (index < from) from - 1 else from },
            )
        }
        persistState()
    }

    /** Re-inserts [track] at [index] (used to undo a removal). No-op if it is already queued. */
    fun insertIntoQueue(track: Track, index: Int) {
        _state.update { current ->
            if (current.queue.any { it.id == track.id }) return@update current
            val safeIndex = index.coerceIn(0, current.queue.size)
            val queue = current.queue.toMutableList().apply { add(safeIndex, track) }
            current.copy(
                queue = queue,
                currentIndex = if (safeIndex <= current.currentIndex) current.currentIndex + 1 else current.currentIndex,
            )
        }
        persistState()
        preloadNext()
    }

    // ── Sleep timer ──────────────────────────────────────────────────────────

    private var sleepTimerJob: Job? = null

    /**
     * Pauses playback after [minutes]. `0` means "at the end of the current
     * track"; `null` cancels any running timer.
     */
    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (minutes == null) {
            _state.update { it.copy(sleepTimerEndsAtMs = null, sleepAtEndOfTrack = false) }
            return
        }
        if (minutes == 0) {
            _state.update { it.copy(sleepTimerEndsAtMs = null, sleepAtEndOfTrack = true) }
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepTimerEndsAtMs = endsAt, sleepAtEndOfTrack = false) }
        sleepTimerJob = scope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            exoPlayer.pause()
            persistState()
            _state.update { it.copy(sleepTimerEndsAtMs = null) }
        }
    }

    /** Downloaded tracks with their stored metadata, newest-first order not guaranteed. */
    suspend fun downloadedTracks(): List<Track> = downloadManager.all().map { record ->
        Track(
            id = record.id,
            title = record.title,
            artist = record.artist,
            album = record.album,
            albumArtUrl = record.albumArtUrl,
            durationSeconds = record.durationSeconds,
        )
    }

    /** Move a queued item without disturbing the currently playing item. */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        _state.update { current ->
            if (fromIndex !in current.queue.indices ||
                toIndex !in current.queue.indices ||
                fromIndex == toIndex ||
                fromIndex == current.currentIndex ||
                toIndex == current.currentIndex
            ) return@update current

            val queue = current.queue.toMutableList()
            val moved = queue.removeAt(fromIndex)
            queue.add(toIndex, moved)
            val nextCurrentIndex = when {
                fromIndex < current.currentIndex && toIndex >= current.currentIndex -> current.currentIndex - 1
                fromIndex > current.currentIndex && toIndex <= current.currentIndex -> current.currentIndex + 1
                else -> current.currentIndex
            }
            current.copy(queue = queue, currentIndex = nextCurrentIndex)
        }
        persistState()
        preloadNext()
    }

    /** Keep the current song, but remove every queued item after it. */
    fun clearQueue() {
        val current = _state.value.currentTrack ?: return
        _state.update { it.copy(queue = listOf(current), currentIndex = 0) }
        val currentMediaIndex = exoPlayer.currentMediaItemIndex
        if (currentMediaIndex != C.INDEX_UNSET && exoPlayer.mediaItemCount > currentMediaIndex + 1) {
            exoPlayer.removeMediaItems(currentMediaIndex + 1, exoPlayer.mediaItemCount)
        }
        persistState()
    }

    suspend fun downloadTrack(track: Track, onProgress: (Int) -> Unit = {}): DownloadedTrack {
        _state.update {
            it.copy(
                isDownloading = true,
                downloadProgress = null,
                errorMessage = null,
            )
        }
        return try {
            val stream = resolveValidatedDirectStream(track.id, initialProfileOffset = 0)
            val downloaded = downloadManager.download(track, stream) { progress ->
                onProgress(progress)
                _state.update { current ->
                    current.copy(
                        isDownloading = true,
                        downloadProgress = progress,
                    )
                }
            }
            _state.update {
                it.copy(
                    downloadedTrackIds = it.downloadedTrackIds + track.id,
                    isDownloading = false,
                    downloadProgress = 100,
                    errorMessage = null,
                )
            }
            downloaded
        } catch (error: Exception) {
            android.util.Log.e("MoodifyPlayer", "Download failed (${error::class.simpleName})")
            // Download failures are reported by the caller (a snackbar); they
            // must not put the *player* into an error state while music plays.
            _state.update {
                it.copy(
                    isDownloading = false,
                    downloadProgress = null,
                )
            }
            throw error
        }
    }

    fun deleteDownload(trackId: String) {
        // Publish the change only after the record is gone, so observers that
        // reload the download list (keyed on downloadedTrackIds) see the deletion.
        scope.launch {
            runCatching { downloadManager.delete(trackId) }
            _state.update {
                it.copy(
                    downloadedTrackIds = it.downloadedTrackIds - trackId,
                    errorMessage = null,
                )
            }
        }
    }

    suspend fun isDownloaded(trackId: String): Boolean = downloadManager.get(trackId) != null

    fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.5f, 2.0f)
        exoPlayer.setPlaybackParameters(PlaybackParameters(safe, _state.value.pitch))
        _state.update { it.copy(playbackSpeed = safe) }
        persistState()
    }

    fun setPitch(pitch: Float) {
        val safe = pitch.coerceIn(0.5f, 2.0f)
        exoPlayer.setPlaybackParameters(PlaybackParameters(_state.value.playbackSpeed, safe))
        _state.update { it.copy(pitch = safe) }
        persistState()
    }

    /** Most-recent-first listening history, re-emitted after every recorded play. */
    fun observeListeningHistory(): kotlinx.coroutines.flow.Flow<List<ListeningHistoryEntry>> =
        historyStore.observe()

    fun setLocale(language: String, region: String) {
        playbackLocale = CatalogLocale(language, region).normalized()
        streamResolver.invalidateAll()
    }

    suspend fun clearListeningHistory() = historyStore.clear()

    /** Most-played tracks (for personal recommendations). */
    suspend fun mostPlayedTracks(limit: Int): List<Track> = historyStore.mostPlayed(limit).map { it.first }

    suspend fun forgottenTracks(cutoffMs: Long, minPlays: Int, limit: Int): List<Track> =
        historyStore.forgotten(cutoffMs, minPlays, limit)

    fun retryCurrent() {
        val current = _state.value.currentTrack ?: return
        ensureServiceStarted()
        streamResolver.invalidate(current.id)
        refreshAttempts.remove(current.id)
        activePlaybackRoute = PlaybackRoute.NONE
        _state.update { it.copy(isLoading = true, isPlaying = false, errorMessage = null, recoveryAttempt = 0) }
        loadAndPlay(current, ++loadGeneration, _state.value.positionMs)
    }

    // ── ExoPlayer accessor for MoodifyPlaybackService ────────────────────────

    /** Returns the underlying ExoPlayer instance for Media3 MediaSession integration. */
    fun getExoPlayer(): ExoPlayer = exoPlayer

    // ── Internal: resolve stream URL and hand to ExoPlayer ──────────────────

    private fun loadAndPlay(
        track: Track,
        generation: Long,
        startPositionMs: Long = 0L,
        profileOffset: Int = 0
    ) {
        loadJob?.cancel()
        val previousDiagnostics = _diagnostics.value
        val retrying = previousDiagnostics.status == "Recovering"
        recordDiagnostics(
            event = "Resolving stream",
            status = if (retrying) "Recovering" else "Resolving",
            route = if (retrying) previousDiagnostics.route else "—",
            clientProfile = if (retrying) previousDiagnostics.clientProfile else null,
            mimeType = if (retrying) previousDiagnostics.mimeType else null,
            itag = if (retrying) previousDiagnostics.itag else null,
            bitrateBps = if (retrying) previousDiagnostics.bitrateBps else null,
            expiresAtMs = if (retrying) previousDiagnostics.expiresAtMs else null,
            playerErrorCode = if (retrying) previousDiagnostics.playerErrorCode else null,
            httpStatusCode = if (retrying) previousDiagnostics.httpStatusCode else null,
            recoveryAction = if (retrying) previousDiagnostics.recoveryAction else null,
        )
        loadJob = scope.launch {
            try {
                if (generation != loadGeneration || _state.value.currentTrack?.id != track.id) return@launch
                _state.update { it.copy(isResolving = true, isLoading = true, errorMessage = null) }

                val downloaded = downloadManager.get(track.id)
                val streamKey = streamCacheKey(track.id)
                // A song already fully in the audio cache starts instantly
                // without resolving or validating a stream.
                val playFromCache = downloaded == null && isFullyCached(streamKey)

                // Resolve directly from this device; Android has no Moodify
                // playback fallback. Each InnerTube client can still be
                // rejected independently, so validate the CDN contract before
                // preparing ExoPlayer and advance past a client after a 403.
                // Signed URLs expire; ExoPlayer handles seeks and the resolver
                // refreshes the contract after a playback error.
                val directStream = if (downloaded != null || playFromCache) {
                    null
                } else {
                    try {
                        resolveValidatedDirectStream(track.id, profileOffset)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        streamResolver.invalidate(track.id)
                        android.util.Log.w(
                            "MoodifyPlayer",
                            "Device InnerTube resolution failed (${error::class.simpleName})"
                        )
                        null
                    }
                }
                directStream?.loudnessDb?.let { loudnessByTrack[track.id] = it }
                val directUrl = directStream?.url

                var selectedRoute = PlaybackRoute.NONE
                val streamChoice = when {
                    downloaded != null -> {
                        selectedRoute = PlaybackRoute.LOCAL
                        StreamChoice(
                            Uri.fromFile(File(downloaded.filePath)).toString(),
                            downloaded.mimeType,
                            null,
                            "moodify:${track.id}:download"
                        )
                    }
                    playFromCache -> {
                        selectedRoute = PlaybackRoute.DIRECT_CDN
                        StreamChoice(
                            streamUri(track.id).toString(),
                            streamContracts[streamKey]?.mimeType,
                            null,
                            streamKey,
                        )
                    }
                    directUrl != null -> {
                        selectedRoute = PlaybackRoute.DIRECT_CDN
                        android.util.Log.d(
                            "MoodifyPlayer",
                            "Direct InnerTube stream selected profile=${directStream.profileName} itag=${directStream.itag}"
                        )
                        StreamChoice(
                            streamUri(track.id).toString(),
                            directStream.mimeType,
                            directStream,
                            streamKey,
                        )
                    }
                    else -> {
                        throw InnerTubeStreamResolver.ResolveException(
                            "No direct YouTube audio stream was resolved for ${track.title}"
                        )
                    }
                }

                if (streamChoice.stream != null || selectedRoute == PlaybackRoute.LOCAL) {
                    registerStreamContract(
                        cacheKey = streamChoice.cacheKey,
                        route = selectedRoute,
                        stream = streamChoice.stream,
                        resetIncompatibleCache = true,
                    )
                } else {
                    routeByCacheKey[streamChoice.cacheKey] = selectedRoute
                }
                recordDiagnostics(
                    event = if (playFromCache) "Playing from audio cache" else "Stream contract validated",
                    status = "Preparing",
                    route = routeLabel(selectedRoute),
                    clientProfile = directStream?.profileName,
                    mimeType = streamChoice.mimeType,
                    itag = directStream?.itag,
                    bitrateBps = directStream?.bitrate,
                    expiresAtMs = directStream?.expiresAtMs,
                    playerErrorCode = null,
                    httpStatusCode = null,
                    recoveryAction = null,
                )

                if (generation != loadGeneration || _state.value.currentTrack?.id != track.id) return@launch

                activePlaybackRoute = selectedRoute

                val byteRangeRoute =
                    selectedRoute == PlaybackRoute.LOCAL ||
                        selectedRoute == PlaybackRoute.DIRECT_CDN
                val safeStartMs = if (byteRangeRoute) {
                    startPositionMs.coerceAtLeast(0L).let { pos ->
                        val dur = _state.value.durationMs
                        if (dur > 0L) pos.coerceAtMost(dur) else pos
                    }
                } else {
                    0L
                }

                val mediaItem = buildMediaItem(
                    track = track,
                    url = streamChoice.url,
                    mimeType = streamChoice.mimeType,
                    cacheKey = streamChoice.cacheKey
                )

                // Close the failed source before preparing the new contract.
                // This prevents a rejected URL, its cache span and its old
                // timeline from surviving a recovery attempt.
                exoPlayer.stop()
                exoPlayer.setMediaItem(mediaItem, safeStartMs)
                exoPlayer.prepare()
                exoPlayer.play()
                // The contract is in ExoPlayer's hands now: the UI moves from
                // "finding a stream" to ordinary buffering.
                _state.update { it.copy(isResolving = false) }
                applyLoudnessNormalization()
                preloadNext()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                android.util.Log.e("MoodifyPlayer", "Stream resolution failed (${e::class.simpleName})")
                recordDiagnostics(
                    event = "Stream resolution failed",
                    status = "Error",
                    playerErrorCode = null,
                    httpStatusCode = null,
                    recoveryAction = "Check the connection and retry",
                )
                setError("Could not load audio. Please try again.")
            }
        }
    }

    /**
     * Resolve a genuinely usable CDN contract before ExoPlayer is involved.
     * A YouTube URL can return 206 for the first bytes and still return 403
     * when the player advances. Rotate through distinct InnerTube clients and
     * validate both the beginning and a later range before selecting one.
     */
    private suspend fun resolveValidatedDirectStream(
        videoId: String,
        initialProfileOffset: Int,
    ): InnerTubeStreamResolver.ResolvedStream {
        var lastFailure: Throwable? = null
        var offset = initialProfileOffset.coerceAtLeast(0)
        val maxProfiles = streamResolver.profileCount()
        while (offset < maxProfiles) {
            val resolved = try {
                streamResolver.resolve(videoId, offset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // resolve() already tried every remaining profile. Advancing
                // one index would repeat a subset of those same requests.
                lastFailure = failure
                break
            }
            try {
                streamResolver.validate(resolved)
                return resolved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                streamResolver.invalidate(videoId)
                android.util.Log.w(
                    "MoodifyPlayer",
                    "Rejected stream contract profileOffset=$offset (${failure::class.simpleName})"
                )
                offset = streamResolver.nextProfileOffset(resolved.profileName)
                    ?: (offset + 1)
            }
        }
        throw (lastFailure ?: IllegalStateException("No validated stream contract"))
    }

    private fun setError(message: String) {
        _state.update { it.copy(isLoading = false, isResolving = false, isPlaying = false, errorMessage = message) }
        persistState()
    }

    private fun publishProgress(
        positionMs: Long = exoPlayer.currentPosition.coerceAtLeast(0L),
        durationMs: Long = exoPlayer.duration.takeIf { it > 0L } ?: _state.value.durationMs,
    ) {
        _progress.value = PlaybackProgress(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
        )
    }

    /**
     * Every route that can cause audio to start must also attach the player to
     * the foreground MediaLibraryService. In particular, a process-restored
     * queue reaches togglePlayPause without passing through playTrack().
     */
    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarter?.let { starter ->
            starter()
            serviceStarted = true
        }
    }

    private fun shouldDiscardPlaybackCache(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        ) {
            return true
        }

        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is EOFException || cause is Cache.CacheException) return true
            if (cause is IOException && cause.message?.contains(
                    "unexpected end of stream",
                    ignoreCase = true
                ) == true
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun httpStatusCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            (cause as? HttpDataSource.InvalidResponseCodeException)?.let { return it.responseCode }
            cause = cause.cause
        }
        return null
    }

    private fun buildMediaMetadata(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .apply {
            // The notification and lock screen draw artwork large; ask the CDN
            // for a sharp copy instead of the small catalog thumbnail.
            if (track.albumArtUrl.isNotBlank()) {
                setArtworkUri(Uri.parse(com.moodify.app.data.ArtworkUrls.sized(track.albumArtUrl, 720)))
            }
        }
        .build()

    private fun preloadNext() {
        val current = _state.value
        val nextIndex = if (current.isShuffle) {
            current.queue.indices.filter { it != current.currentIndex }.randomOrNull()
        } else {
            current.currentIndex + 1
        }
        val nextTrack = current.queue.getOrNull(nextIndex ?: -1) ?: return
        val currentTrackId = current.currentTrack?.id ?: return
        preloadJob?.cancel()
        preloadJob = scope.launch {
            if (_state.value.currentTrack?.id != currentTrackId ||
                exoPlayer.currentMediaItem?.mediaId != currentTrackId
            ) {
                return@launch
            }
            val alreadyQueued = (0 until exoPlayer.mediaItemCount).any {
                exoPlayer.getMediaItemAt(it).mediaId == nextTrack.id
            }
            val downloaded = downloadManager.get(nextTrack.id)
            val cacheKey = if (downloaded != null) "moodify:${nextTrack.id}:download" else streamCacheKey(nextTrack.id)
            if (!alreadyQueued) {
                // Queue the next song straight away: the data source resolves
                // its stream when ExoPlayer starts buffering it, so the hand-off
                // is gapless even if warming below has not finished.
                val item = if (downloaded != null) {
                    routeByCacheKey[cacheKey] = PlaybackRoute.LOCAL
                    buildMediaItem(nextTrack, Uri.fromFile(File(downloaded.filePath)).toString(), downloaded.mimeType, cacheKey)
                } else {
                    routeByCacheKey[cacheKey] = PlaybackRoute.DIRECT_CDN
                    buildMediaItem(nextTrack, streamUri(nextTrack.id).toString(), streamContracts[cacheKey]?.mimeType, cacheKey)
                }
                if (exoPlayer.currentMediaItem?.mediaId != currentTrackId) return@launch
                exoPlayer.addMediaItem(item)
            }
            // Warm a validated contract unless the song is already playable offline.
            if (downloaded != null || isFullyCached(cacheKey) || hasFreshContract(cacheKey)) return@launch
            val resolved = runCatching {
                resolveValidatedDirectStream(nextTrack.id, initialProfileOffset = 0)
            }.onFailure {
                android.util.Log.d("MoodifyPlayer", "Next track warm-up skipped (${it::class.simpleName})")
            }.getOrNull() ?: return@launch
            resolved.loudnessDb?.let { loudnessByTrack[nextTrack.id] = it }
            registerStreamContract(cacheKey, PlaybackRoute.DIRECT_CDN, resolved, resetIncompatibleCache = false)
        }
    }

    // ── Lazy stream resolution & audio cache ────────────────────────────────

    private fun streamCacheKey(videoId: String) = "moodify:$videoId"

    private fun streamUri(videoId: String): Uri = Uri.parse("$STREAM_SCHEME://track/$videoId")

    private fun hasFreshContract(cacheKey: String): Boolean =
        streamContracts[cacheKey]?.let { it.url != null && !it.isNearExpiry() } == true

    private fun StreamContract.isNearExpiry(): Boolean =
        expiresAtMs <= System.currentTimeMillis() + CONTRACT_REFRESH_MARGIN_MS

    private fun isFullyCached(cacheKey: String): Boolean = runCatching {
        val cache = playbackCache.cache
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        length > 0L && cache.isCached(cacheKey, 0L, length)
    }.getOrDefault(false)

    /**
     * Runs on ExoPlayer's loader thread when bytes are needed that the cache
     * does not have. Reuses a fresh validated contract or resolves one, checks
     * it matches what is already cached, and points the request at the CDN.
     */
    private fun resolvePlaybackDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != STREAM_SCHEME) {
            // Local files and explicit URLs pass through, with any registered headers.
            val contract = dataSpec.key?.let(streamContracts::get) ?: return dataSpec
            return applyContract(dataSpec, contract)
        }
        val videoId = dataSpec.uri.lastPathSegment?.takeIf { YOUTUBE_VIDEO_ID_REGEX.matches(it) }
            ?: throw IOException("Invalid Moodify stream reference")
        val cacheKey = dataSpec.key ?: streamCacheKey(videoId)
        var contract = streamContracts[cacheKey]?.takeIf { it.url != null && !it.isNearExpiry() }
        if (contract == null) {
            val resolved = try {
                runBlocking { resolveValidatedDirectStream(videoId, initialProfileOffset = 0) }
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException("Could not resolve an audio stream", error)
            }
            resolved.loudnessDb?.let { loudnessByTrack[videoId] = it }
            contract = registerStreamContract(cacheKey, PlaybackRoute.DIRECT_CDN, resolved, resetIncompatibleCache = false)
                ?: throw IOException("Could not register the audio stream")
        }
        ensureCacheMatches(cacheKey, contract)
        val url = contract.url ?: throw IOException("Resolved stream has no URL")
        return applyContract(dataSpec.buildUpon().setUri(Uri.parse(url)).build(), contract)
    }

    /**
     * Headers and content length belong to the contract, not a global HTTP
     * factory. A known length makes Media3 issue bounded Range requests —
     * the shape YouTube's CDN accepted during validation.
     */
    private fun applyContract(dataSpec: DataSpec, contract: StreamContract): DataSpec {
        val resolved = dataSpec.withAdditionalHeaders(contract.requestHeaders)
        val contentLength = contract.contentLength
        return if (contentLength != null && contentLength > resolved.position) {
            val remaining = contentLength - resolved.position
            val requested = if (resolved.length == C.LENGTH_UNSET.toLong()) remaining else resolved.length.coerceAtMost(remaining)
            resolved.subrange(0L, requested)
        } else {
            resolved
        }
    }

    /**
     * Bytes cached for a song must come from the same format (itag) and file
     * size as the stream we are about to read. A mismatch throws a cache
     * error; the player's recovery path then drops the cache and re-resolves.
     */
    private fun ensureCacheMatches(cacheKey: String, contract: StreamContract) {
        val cache = playbackCache.cache
        if (cache.getCachedSpans(cacheKey).isEmpty()) {
            pinCacheFormat(cacheKey, contract)
            return
        }
        val metadata = cache.getContentMetadata(cacheKey)
        val pinnedItag = metadata.get(ITAG_METADATA_KEY, -1L)
        val cachedLength = ContentMetadata.getContentLength(metadata)
        val expectedLength = contract.contentLength
        val mismatch = (pinnedItag > 0 && pinnedItag != contract.itag.toLong()) ||
            (cachedLength > 0 && expectedLength != null && cachedLength != expectedLength)
        if (mismatch) throw Cache.CacheException("Cached audio does not match the resolved stream")
        if (pinnedItag <= 0) pinCacheFormat(cacheKey, contract)
    }

    private fun pinCacheFormat(cacheKey: String, contract: StreamContract) {
        runCatching {
            val mutations = ContentMetadataMutations().set(ITAG_METADATA_KEY, contract.itag.toLong())
            contract.contentLength?.takeIf { it > 0 }?.let { ContentMetadataMutations.setContentLength(mutations, it) }
            playbackCache.cache.applyContentMetadataMutations(cacheKey, mutations)
        }
    }

    private fun persistState() {
        lastPersistAtMs = System.currentTimeMillis()
        val current = _state.value
        persistence.savePlaybackState(
            current.copy(
                positionMs = if (exoPlayer.currentMediaItem != null) {
                    exoPlayer.currentPosition.coerceAtLeast(0L)
                } else current.positionMs
            )
        )
    }

    private fun registerStreamContract(
        cacheKey: String,
        route: PlaybackRoute,
        stream: InnerTubeStreamResolver.ResolvedStream?,
        resetIncompatibleCache: Boolean = false,
    ): StreamContract? {
        val contract = if (stream == null) {
            streamContracts.remove(cacheKey)
            null
        } else {
            StreamContract(
                url = stream.url,
                requestHeaders = buildMap {
                    put("User-Agent", stream.userAgent)
                    putAll(stream.requestHeaders)
                },
                contentLength = stream.contentLength,
                durationMs = stream.durationMs,
                expiresAtMs = stream.expiresAtMs,
                profileName = stream.profileName,
                mimeType = safeMimeType(stream.mimeType),
                itag = stream.itag,
                bitrateBps = stream.bitrate,
            ).also { streamContracts[cacheKey] = it }
        }
        routeByCacheKey[cacheKey] = route

        // Before preparing (player stopped, nothing reading this key) a cache
        // written from a different format is discarded up front.
        if (contract != null && resetIncompatibleCache) {
            runCatching { ensureCacheMatches(cacheKey, contract) }.onFailure {
                playbackCache.remove(cacheKey)
                pinCacheFormat(cacheKey, contract)
            }
        }

        // Keep stale contracts from becoming a long-lived memory leak while
        // retaining enough resolved queue items for normal listening. The
        // player may only be queried from the main thread.
        if (streamContracts.size > MAX_STREAM_CONTRACTS && Looper.myLooper() == Looper.getMainLooper()) {
            val liveKeys = (0 until exoPlayer.mediaItemCount)
                .mapNotNull { index ->
                    exoPlayer.getMediaItemAt(index).localConfiguration?.customCacheKey
                }
                .toSet() + cacheKey
            streamContracts.keys
                .filterNot(liveKeys::contains)
                .take(streamContracts.size - MAX_STREAM_CONTRACTS)
                .forEach { staleKey ->
                    streamContracts.remove(staleKey)
                    routeByCacheKey.remove(staleKey)
                }
        }
        return contract
    }

    private fun recordDiagnostics(
        event: String,
        status: String = _diagnostics.value.status,
        route: String = _diagnostics.value.route,
        clientProfile: String? = _diagnostics.value.clientProfile,
        mimeType: String? = _diagnostics.value.mimeType,
        itag: Int? = _diagnostics.value.itag,
        bitrateBps: Int? = _diagnostics.value.bitrateBps,
        expiresAtMs: Long? = _diagnostics.value.expiresAtMs,
        playerErrorCode: Int? = _diagnostics.value.playerErrorCode,
        httpStatusCode: Int? = _diagnostics.value.httpStatusCode,
        recoveryAction: String? = _diagnostics.value.recoveryAction,
    ) {
        val now = System.currentTimeMillis()
        _diagnostics.update { current ->
            current.copy(
                status = status,
                route = route,
                clientProfile = clientProfile?.take(32),
                mimeType = mimeType?.let(::safeMimeType),
                itag = itag,
                bitrateBps = bitrateBps,
                expiresAtMs = expiresAtMs,
                playerErrorCode = playerErrorCode,
                httpStatusCode = httpStatusCode,
                recoveryAction = recoveryAction,
                updatedAtMs = now,
                events = (listOf(
                    PlaybackDiagnosticEvent(
                        timestampMs = now,
                        event = event,
                        clientProfile = clientProfile?.take(32),
                        itag = itag,
                        httpStatusCode = httpStatusCode,
                        playerErrorCode = playerErrorCode,
                    )
                ) + current.events).take(20),
            )
        }
    }

    private fun routeLabel(route: PlaybackRoute): String = when (route) {
        PlaybackRoute.NONE -> "—"
        PlaybackRoute.LOCAL -> "Local download"
        PlaybackRoute.DIRECT_CDN -> "Direct CDN"
    }

    private fun safeMimeType(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '/' || it == '-' || it == '.' }.take(64)

    private fun buildMediaItem(
        track: Track,
        url: String,
        mimeType: String?,
        cacheKey: String
    ): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMimeType(mimeType)
        .setMediaId(track.id)
        .setCustomCacheKey(cacheKey)
        .setMediaMetadata(buildMediaMetadata(track))
        .build()

    private data class StreamContract(
        val url: String?,
        val requestHeaders: Map<String, String>,
        val contentLength: Long?,
        val durationMs: Long?,
        val expiresAtMs: Long,
        val profileName: String,
        val mimeType: String,
        val itag: Int,
        val bitrateBps: Int,
    )

    private data class StreamChoice(
        val url: String,
        val mimeType: String?,
        val stream: InnerTubeStreamResolver.ResolvedStream?,
        val cacheKey: String
    )

}
