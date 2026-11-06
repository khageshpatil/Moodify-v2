package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStatsTest {
    private fun song(id: String, artist: String, seconds: Int = 240) =
        Track(id = id, title = "Song $id", artist = artist, durationSeconds = seconds)

    @Test
    fun ranksSongsArtistsAndTotalsListeningTime() {
        val stats = ListeningStats.from(
            listOf(
                song("a", "Arijit Singh, Pritam") to 5,
                song("b", "Arijit Singh") to 3,
                song("c", "Diljit Dosanjh") to 6,
                song("d", "Unknown artist") to 9,
            ),
        )

        assertEquals(listOf("d", "c", "a", "b"), stats.topSongs.map { it.first.id })
        // Credits roll up to the first-named artist; "Unknown artist" never ranks.
        assertEquals(listOf("Arijit Singh" to 8, "Diljit Dosanjh" to 6), stats.topArtists)
        assertEquals(23, stats.totalPlays)
        assertEquals(92L, stats.totalMinutes)
    }

    @Test
    fun emptyHistoryIsEmpty() {
        assertTrue(ListeningStats.from(emptyList()).isEmpty)
    }
}
