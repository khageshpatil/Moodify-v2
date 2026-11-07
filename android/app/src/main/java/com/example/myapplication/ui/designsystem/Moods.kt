package com.moodify.app.ui.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.moodify.app.ui.theme.MoodHues

/** A mood the listener can start from: a label, a search query, an icon and a hue. */
data class MoodOption(
    val id: String,
    val label: String,
    val query: String,
    val icon: ImageVector,
    val hue: Color,
    /** Names YouTube Music uses for the same mood in "Moods & genres", best match first. */
    val catalogTitles: List<String> = listOf(label),
)

val MoodOptions = listOf(
    MoodOption("chill", "Chill", "chill vibes", Icons.Rounded.Spa, MoodHues[2], listOf("Chill", "Relax")),
    MoodOption("focus", "Focus", "deep focus music", Icons.Rounded.Psychology, MoodHues[4]),
    MoodOption("energy", "Energy", "high energy hits", Icons.Rounded.Bolt, MoodHues[3], listOf("Energy boosters", "Energize", "Energy")),
    MoodOption("romance", "Romance", "romantic songs", Icons.Rounded.Favorite, MoodHues[5], listOf("Romance", "Romantic")),
    MoodOption("party", "Party", "party anthems", Icons.Rounded.Celebration, MoodHues[0], listOf("Party", "Feel good")),
    MoodOption("workout", "Workout", "workout music", Icons.Rounded.FitnessCenter, MoodHues[1]),
    MoodOption("roadtrip", "Road trip", "road trip songs", Icons.Rounded.DirectionsCar, MoodHues[6], listOf("Commute", "Road trip")),
    MoodOption("heartbreak", "Heartbreak", "sad songs", Icons.Rounded.HeartBroken, MoodHues[7], listOf("Sad", "Heartbreak")),
    MoodOption("sleep", "Sleep", "sleep music", Icons.Rounded.Bedtime, MoodHues[4]),
)

/** Moods with the listener's favourites first, in the order they picked them. */
fun orderedMoods(favoriteIds: List<String>): List<MoodOption> {
    val favorites = favoriteIds.mapNotNull { id -> MoodOptions.firstOrNull { it.id == id } }
    return favorites + MoodOptions.filterNot { it in favorites }
}

/** True when a "Moods & genres" tile is one of Moodify's own moods (shown once, as a mood). */
fun isMoodTile(title: String): Boolean =
    MoodOptions.any { mood -> mood.catalogTitles.any { it.equals(title.trim(), ignoreCase = true) } }
