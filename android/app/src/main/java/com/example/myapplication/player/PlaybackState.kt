package com.moodify.app.player

import com.moodify.app.data.Track

data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffle: Boolean = false,
    val isRepeat: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val bufferedPositionMs: Long = 0L,
    val downloadedTrackIds: Set<String> = emptySet(),
    val isDownloading: Boolean = false,
    val downloadProgress: Int? = null,
    val playbackSpeed: Float = 1f,
    val pitch: Float = 1f,
    /** True while a stream contract is being resolved/validated, before ExoPlayer buffers. */
    val isResolving: Boolean = false,
    /** 0 for the first attempt; incremented each time playback recovers with a fresh contract. */
    val recoveryAttempt: Int = 0,
    /** Wall-clock time when the sleep timer pauses playback, or null. */
    val sleepTimerEndsAtMs: Long? = null,
    /** Pause when the current track finishes. */
    val sleepAtEndOfTrack: Boolean = false,
    /** Queue index where autoplay (radio) songs begin, or null when none were added. */
    val autoplayFromIndex: Int? = null,
    val isLoadingRadio: Boolean = false,
    /** Where the queue came from ("Liked songs", "Arijit Singh", "Kesariya radio"), or null. */
    val queueSource: String? = null,
) {
    /**
     * A single, UI-friendly description of what the player is doing. Derived
     * from the lower-level flags so the engine keeps one source of truth.
     */
    val phase: PlaybackPhase
        get() = when {
            currentTrack == null -> PlaybackPhase.Idle
            errorMessage != null -> PlaybackPhase.Error(errorMessage)
            isResolving -> PlaybackPhase.Resolving(recoveryAttempt)
            isLoading -> PlaybackPhase.Buffering
            isPlaying -> PlaybackPhase.Playing
            else -> PlaybackPhase.Paused
        }
}

sealed interface PlaybackPhase {
    data object Idle : PlaybackPhase
    /** [attempt] is 0 for the first resolution and >0 while recovering. */
    data class Resolving(val attempt: Int) : PlaybackPhase
    data object Buffering : PlaybackPhase
    data object Playing : PlaybackPhase
    data object Paused : PlaybackPhase
    data class Error(val message: String) : PlaybackPhase

    val isBusy: Boolean get() = this is Resolving || this == Buffering
}

/**
 * High-frequency position data, published separately from [PlaybackState]
 * so that only the seek bar / progress indicators recompose while playing.
 */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction: Float
        get() = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Safe, in-memory playback diagnostics. Never add media URLs, IDs, or tokens here. */
data class PlaybackDiagnostics(
    val status: String = "Idle",
    val route: String = "—",
    val clientProfile: String? = null,
    val mimeType: String? = null,
    val itag: Int? = null,
    val bitrateBps: Int? = null,
    val expiresAtMs: Long? = null,
    val playerErrorCode: Int? = null,
    val httpStatusCode: Int? = null,
    val recoveryAction: String? = null,
    val updatedAtMs: Long = 0L,
    val events: List<PlaybackDiagnosticEvent> = emptyList(),
)

data class PlaybackDiagnosticEvent(
    val timestampMs: Long,
    val event: String,
    val clientProfile: String? = null,
    val itag: Int? = null,
    val httpStatusCode: Int? = null,
    val playerErrorCode: Int? = null,
)
