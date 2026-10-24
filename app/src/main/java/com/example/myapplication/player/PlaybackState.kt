package com.example.myapplication.player

import com.example.myapplication.data.Track

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
    val errorMessage: String? = null
)
