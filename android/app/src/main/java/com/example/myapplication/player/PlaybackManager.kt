package com.example.myapplication.player

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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Singleton playback engine that resolves YouTube audio through native
 * InnerTube direct-CDN playback, with the server's range-capable direct relay
 * as a fallback, using Media3 ExoPlayer.
 *
 * Background playback is handled by [MoodifyPlaybackService] (foreground service).
 */
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
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val persistence = PlaybackPersistence(context)
    private val historyStore = ListeningHistoryStore(context)
    private val _state = MutableStateFlow(
        persistence.restorePlaybackState() ?: PlaybackState()
    )
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Callback set by Activity so PlaybackManager can start the foreground service. */
    var serviceStarter: (() -> Unit)? = null
    private var serviceStarted = false
    private var loadGeneration = 0L
    private val refreshAttempts = mutableMapOf<String, Int>()
    private val streamResolver = InnerTubeStreamResolver()
    private val downloadManager = MoodifyDownloadManager(context, streamResolver)
    private val playbackCache = PlaybackCache(context)
    private var preloadJob: Job? = null
    private var lastPersistAtMs = 0L
    private var historyRecordedForTrack: String? = null
    private enum class PlaybackRoute { NONE, LOCAL, DIRECT_CDN, SERVER_DIRECT_CDN, SERVER_DIRECT_RELAY }
    @Volatile
    private var activePlaybackRoute = PlaybackRoute.NONE
    @Volatile
    private var activeDirectHeaders: Map<String, String> = emptyMap()
    private val directHttpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Moodify-Android/1.0")
        .setAllowCrossProtocolRedirects(true)
        .setReadTimeoutMs(30000)
        .setConnectTimeoutMs(15000)
        .setDefaultRequestProperties(
            mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "identity"
            )
        )
    private val dataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        playbackCache.factory(DefaultDataSource.Factory(context, directHttpDataSourceFactory))
    ) { dataSpec ->
        var resolved = dataSpec
        android.util.Log.d(
            "MoodifyPlayer",
            "DataSpec position=${dataSpec.position} length=${dataSpec.length}"
        )
        if (activeDirectHeaders.isNotEmpty()) {
            resolved = resolved.withAdditionalHeaders(activeDirectHeaders)
        }
        android.util.Log.d(
            "MoodifyPlayer",
            "ResolvedDataSpec position=${resolved.position} length=${resolved.length} headers=${resolved.httpRequestHeaders.keys}"
        )
        resolved
    }

    // ── ExoPlayer ────────────────────────────────────────────────────────────

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
        )
        .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
        true
    ).setHandleAudioBecomingNoisy(true).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true) }
                    Player.STATE_READY     -> _state.update {
                        val current = it.copy(
                            isLoading = false,
                            errorMessage = null,
                            durationMs = exoPlayer.duration.takeIf { duration -> duration > 0L }
                                ?: it.durationMs,
                            bufferedPositionMs = exoPlayer.bufferedPosition
                        )
                        current.currentTrack?.let { track ->
                            if (historyRecordedForTrack != track.id) {
                                historyStore.record(track)
                                historyRecordedForTrack = track.id
                            }
                        }
                        current
                    }
                    Player.STATE_ENDED     -> {
                        _state.update { it.copy(isPlaying = false, isLoading = false) }
                        val current = _state.value
                        if (current.isRepeat && current.currentTrack != null) {
                            loadAndPlay(current.currentTrack, ++loadGeneration)
                        } else if (current.isShuffle || current.currentIndex < current.queue.lastIndex) {
                            playNext()
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
                if (current != null) {
                    // Signed CDN URLs and their token context must never be
                    // retried unchanged after a transport failure.
                    streamResolver.invalidate(current.id)
                }
                if (current != null &&
                    activePlaybackRoute == PlaybackRoute.DIRECT_CDN &&
                    attempts < DIRECT_PROFILE_RETRY_COUNT
                ) {
                    // Rotate the InnerTube client after a CDN rejection instead
                    // of resolving the same client and signed URL repeatedly.
                    val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    refreshAttempts[current.id] = attempts + 1
                    _state.update {
                        it.copy(
                            isLoading = true,
                            isPlaying = false,
                            positionMs = resumePositionMs,
                            errorMessage = null
                        )
                    }
                    loadAndPlay(
                        current,
                        ++loadGeneration,
                        resumePositionMs,
                        profileOffset = attempts + 1
                    )
                    return
                }
                if (current != null &&
                    activePlaybackRoute == PlaybackRoute.DIRECT_CDN &&
                    attempts == DIRECT_PROFILE_RETRY_COUNT
                ) {
                    // Resolve a tokenized CDN URL through the backend, but
                    // keep the media bytes on this device.
                    val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    refreshAttempts[current.id] = attempts + 1
                    _state.update {
                        it.copy(
                            isLoading = true,
                            isPlaying = false,
                            positionMs = resumePositionMs,
                            errorMessage = null
                        )
                    }
                    loadAndPlay(
                        current,
                        ++loadGeneration,
                        resumePositionMs,
                        forceServerDirect = true
                    )
                    return
                }
                if (current != null &&
                    activePlaybackRoute == PlaybackRoute.SERVER_DIRECT_CDN &&
                    attempts == DIRECT_PROFILE_RETRY_COUNT + 1
                ) {
                    val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    refreshAttempts[current.id] = attempts + 1
                    _state.update {
                        it.copy(
                            isLoading = true,
                            isPlaying = false,
                            positionMs = resumePositionMs,
                            errorMessage = null
                        )
                    }
                    loadAndPlay(
                        current,
                        ++loadGeneration,
                        resumePositionMs,
                        forceDirectRelay = true
                    )
                    return
                }
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "No internet connection."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Audio stream unavailable (server error)."
                    PlaybackException.ERROR_CODE_TIMEOUT ->
                        "Connection timed out."
                    else -> "Playback error: ${error.message}"
                }
                android.util.Log.e(
                    "MoodifyPlayer",
                    "ExoPlayer error: ${error.errorCode} — ${error.message}; cause=${error.cause?.message}",
                    error.cause
                )
                _state.update { it.copy(isLoading = false, isPlaying = false, errorMessage = msg) }
                persistState()
            }
        })
    }

    // ── Progress timer ───────────────────────────────────────────────────────

    init {
        _state.update { it.copy(downloadedTrackIds = downloadManager.all().map { record -> record.id }.toSet()) }
        // Poll ExoPlayer every 500 ms for position/duration updates.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                if (_state.value.isPlaying) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0)
                    val dur = exoPlayer.duration.takeIf { it > 0 } ?: _state.value.durationMs
                    _state.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = dur,
                            bufferedPositionMs = exoPlayer.bufferedPosition
                        )
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
            setError("Invalid YouTube video ID for: ${track.title}")
            return
        }

        val index = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        _state.update {
            it.copy(
                currentTrack = track,
                queue = newQueue,
                currentIndex = index,
                isLoading = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = track.durationSeconds * 1000L,
                errorMessage = null
            )
        }

        // Ensure foreground service is running before we start (keeps process alive)
        if (!serviceStarted) {
            serviceStarter?.invoke()
            serviceStarted = true
        }

        refreshAttempts.remove(track.id)
        activePlaybackRoute = PlaybackRoute.NONE
        persistState()
        loadAndPlay(track, ++loadGeneration)
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
        current.queue.getOrNull(nextIndex)?.let { playTrack(it, current.queue) }
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
        if ((activePlaybackRoute == PlaybackRoute.DIRECT_CDN ||
                activePlaybackRoute == PlaybackRoute.SERVER_DIRECT_CDN ||
                activePlaybackRoute == PlaybackRoute.SERVER_DIRECT_RELAY) &&
            exoPlayer.currentMediaItem != null
        ) {
            // Direct CDN playback is a byte-range resource. Let Media3 issue
            // the correct range request for a native seek.
            exoPlayer.seekTo(safe)
            _state.update { it.copy(positionMs = safe, errorMessage = null) }
            persistState()
        } else {
            _state.update { it.copy(positionMs = safe, isLoading = true, errorMessage = null) }
            loadAndPlay(
                current,
                ++loadGeneration,
                safe,
                forceDirectRelay = activePlaybackRoute == PlaybackRoute.SERVER_DIRECT_RELAY
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
            current.copy(queue = queue, currentIndex = nextIndex)
        }
        persistState()
    }

    suspend fun downloadTrack(track: Track): DownloadedTrack {
        val downloaded = downloadManager.download(track)
        _state.update { it.copy(downloadedTrackIds = it.downloadedTrackIds + track.id) }
        return downloaded
    }

    fun deleteDownload(trackId: String) = downloadManager.delete(trackId)

    fun isDownloaded(trackId: String): Boolean = downloadManager.get(trackId) != null

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

    fun listeningHistory(): List<ListeningHistoryEntry> = historyStore.entries()

    fun clearListeningHistory() = historyStore.clear()

    fun retryCurrent() {
        val current = _state.value.currentTrack ?: return
        streamResolver.invalidate(current.id)
        refreshAttempts.remove(current.id)
        activePlaybackRoute = PlaybackRoute.NONE
        _state.update { it.copy(isLoading = true, isPlaying = false, errorMessage = null) }
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
        forceDirectRelay: Boolean = false,
        forceServerDirect: Boolean = false,
        profileOffset: Int = 0
    ) {
        scope.launch {
            try {
                if (generation != loadGeneration || _state.value.currentTrack?.id != track.id) return@launch

                val metadata = buildMediaMetadata(track)
                val downloaded = downloadManager.get(track.id)

                // ── Strategy 1: Direct InnerTube call from this device ──────────────
                // Calls YouTube's /youtubei/v1/player API directly (IOS client).
                // The request comes from the user's real mobile/WiFi IP, so YouTube
                // never bot-challenges it — same approach as ViMusic and InnerTune.
                // CDN URLs are pre-signed and expire; ExoPlayer handles seeks with
                // byte ranges and we refresh the URL only after a playback error.
                val directStream = if (downloaded != null || forceDirectRelay || forceServerDirect) {
                    null
                } else {
                    runCatching {
                        streamResolver.resolve(track.id, profileOffset)
                    }.onFailure { error ->
                        android.util.Log.w(
                            "MoodifyPlayer",
                            "Device InnerTube resolution failed for ${track.id}: ${error.message}"
                        )
                    }.getOrNull()
                }
                val directUrl = directStream?.url
                val serverStream = if (downloaded == null && !forceDirectRelay && directStream == null) {
                    runCatching {
                        streamResolver.resolveFromServer(track.id)
                    }.onFailure { error ->
                        android.util.Log.w(
                            "MoodifyPlayer",
                            "Server CDN resolution failed for ${track.id}: ${error.message}"
                        )
                    }.getOrNull()
                } else {
                    null
                }
                val serverDirectUrl = serverStream?.url

                val streamChoice = when {
                    downloaded != null -> {
                        activePlaybackRoute = PlaybackRoute.LOCAL
                        activeDirectHeaders = emptyMap()
                        StreamChoice(
                            Uri.fromFile(File(downloaded.filePath)).toString(),
                            downloaded.mimeType,
                            null,
                            "moodify:${track.id}:download"
                        )
                    }
                    directUrl != null -> {
                        activePlaybackRoute = PlaybackRoute.DIRECT_CDN
                        configureDirectStream(requireNotNull(directStream))
                        android.util.Log.d("MoodifyPlayer",
                            "Direct InnerTube URL for ${track.id}")
                        // Direct CDN: no start-time support — ExoPlayer can seek natively
                        StreamChoice(
                            directUrl,
                            directStream?.mimeType ?: MimeTypes.AUDIO_MP4,
                            directStream,
                            "moodify:${track.id}:${directStream?.itag ?: -1}"
                        )
                    }
                    serverDirectUrl != null -> {
                        activePlaybackRoute = PlaybackRoute.SERVER_DIRECT_CDN
                        configureDirectStream(requireNotNull(serverStream))
                        android.util.Log.d(
                            "MoodifyPlayer",
                            "Server-resolved CDN URL for ${track.id} (device transport)"
                        )
                        StreamChoice(
                            serverDirectUrl,
                            serverStream?.mimeType ?: MimeTypes.AUDIO_MP4,
                            serverStream,
                            "moodify:${track.id}:${serverStream?.itag ?: -1}"
                        )
                    }
                    else -> {
                        // ── Strategy 2: Server direct relay ────────────────────────────
                        // Fall back to the backend when the direct call fails.
                        activePlaybackRoute = PlaybackRoute.SERVER_DIRECT_RELAY
                        configureDirectRelay()
                        val mediaUrl =
                            "${BuildConfig.MOODIFY_SERVER_URL.trimEnd('/')}/api/media/${track.id}?transport=direct"
                        android.util.Log.d("MoodifyPlayer",
                            "Using server direct relay: $mediaUrl")
                        StreamChoice(mediaUrl, MimeTypes.AUDIO_MP4, null, "moodify:${track.id}:relay")
                    }
                }

                if (generation != loadGeneration || _state.value.currentTrack?.id != track.id) return@launch

                val byteRangeRoute =
                    activePlaybackRoute == PlaybackRoute.LOCAL ||
                        activePlaybackRoute == PlaybackRoute.DIRECT_CDN ||
                        activePlaybackRoute == PlaybackRoute.SERVER_DIRECT_CDN ||
                        activePlaybackRoute == PlaybackRoute.SERVER_DIRECT_RELAY
                val safeStartMs = if (byteRangeRoute) {
                    startPositionMs.coerceAtLeast(0L).let { pos ->
                        val dur = _state.value.durationMs
                        if (dur > 0L) pos.coerceAtMost(dur) else pos
                    }
                } else {
                    0L
                }

                val mediaItem = MediaItem.Builder()
                    .setUri(streamChoice.url)
                    .setMimeType(streamChoice.mimeType)
                    .setMediaId(track.id)
                    .setCustomCacheKey(streamChoice.cacheKey)
                    .setMediaMetadata(metadata)
                    .build()

                exoPlayer.setMediaItem(mediaItem, safeStartMs)
                exoPlayer.prepare()
                exoPlayer.play()
                preloadNext()
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                android.util.Log.e("MoodifyPlayer", "Stream resolution failed", e)
                setError("Could not load audio: ${e.message ?: "Resolution failed"}")
            }
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(isLoading = false, isPlaying = false, errorMessage = message) }
        persistState()
    }

    private fun buildMediaMetadata(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .apply {
            if (track.albumArtUrl.isNotBlank()) setArtworkUri(Uri.parse(track.albumArtUrl))
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
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            streamResolver.preload(nextTrack.id)
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

    private fun configureDirectStream(stream: InnerTubeStreamResolver.ResolvedStream) {
        activeDirectHeaders = stream.requestHeaders
        android.util.Log.d(
            "MoodifyPlayer",
            "Direct stream metadata: mime=${stream.mimeType} length=${stream.contentLength} " +
                "itag=${stream.itag} ua=${stream.userAgent} headers=${stream.requestHeaders.keys}"
        )
        directHttpDataSourceFactory
            .setUserAgent(stream.userAgent)
            .setDefaultRequestProperties(
                buildMap {
                    put("Accept", "*/*")
                    put("Accept-Encoding", "identity")
                    putAll(stream.requestHeaders)
                }
            )
    }

    private fun configureDirectRelay() {
        activeDirectHeaders = emptyMap()
        directHttpDataSourceFactory
            .setUserAgent("Moodify-Android/1.0")
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity"
                )
            )
    }

    private data class StreamChoice(
        val url: String,
        val mimeType: String,
        val stream: InnerTubeStreamResolver.ResolvedStream?,
        val cacheKey: String
    )

}
