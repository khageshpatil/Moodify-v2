package com.example.myapplication.data

/** A small device-local playlist. It deliberately stores canonical Track metadata. */
data class LocalPlaylist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
)
