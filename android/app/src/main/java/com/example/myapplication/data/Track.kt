package com.example.myapplication.data

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtUrl: String = "",
    val durationSeconds: Int = 0,
    val audioUrl: String = "",
    val isFavorite: Boolean = false,
    val explicit: Boolean = false
)
