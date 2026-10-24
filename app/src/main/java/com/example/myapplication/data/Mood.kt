package com.example.myapplication.data

import androidx.compose.ui.graphics.Color

data class Mood(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String,
    val startColor: Color,
    val endColor: Color,
    val searchQuery: String
)

val DEFAULT_MOODS = listOf(
    Mood("love", "Love & Romantic", "❤️", "Heartwarming acoustic & romantic melodies", Color(0xFFEC4899), Color(0xFF9333EA), "romantic hindi love songs"),
    Mood("chill", "Chill & Lo-Fi", "☕", "Laid back vibes and relaxing beats", Color(0xFF06B6D4), Color(0xFF3B82F6), "chill lofi relaxing songs"),
    Mood("party", "Party & Hype", "🔥", "High energy bangers and dance beats", Color(0xFFF59E0B), Color(0xFFEF4444), "bollywood party dance songs"),
    Mood("focus", "Deep Focus", "🎯", "Instrumental & ambient sounds to focus", Color(0xFF10B981), Color(0xFF059669), "instrumental study focus music"),
    Mood("workout", "Workout & Gym", "⚡", "Pump up tunes to smash your workout", Color(0xFF8B5CF6), Color(0xFF6D28D9), "workout motivation gym songs"),
    Mood("melancholy", "Melancholy & Sad", "🌧️", "Soulful tracks for quiet introspection", Color(0xFF64748B), Color(0xFF334155), "sad soulful acoustic songs")
)
