package com.moodify.app.data

/** A summary of what you listen to, built from per-song play counts. */
data class ListeningStats(
    val topSongs: List<Pair<Track, Int>> = emptyList(),
    val topArtists: List<Pair<String, Int>> = emptyList(),
    /** Estimated from plays × song length (partial plays count as whole ones). */
    val totalMinutes: Long = 0L,
    val totalPlays: Int = 0,
) {
    val isEmpty: Boolean get() = totalPlays == 0

    /** Plain text for sharing ("My top songs on Moodify…"). */
    fun shareText(): String = buildString {
        appendLine("My music on Moodify 🎧")
        if (topArtists.isNotEmpty()) appendLine("Top artist: ${topArtists.first().first}")
        appendLine()
        topSongs.take(5).forEachIndexed { index, (track, _) ->
            appendLine("${index + 1}. ${track.title} — ${track.artist}")
        }
    }.trim()

    companion object {
        fun from(plays: List<Pair<Track, Int>>, songLimit: Int = 10, artistLimit: Int = 5): ListeningStats {
            val counted = plays.filter { it.second > 0 }
            val artists = counted
                .groupBy({ primaryArtist(it.first.artist) }, { it.second })
                .filterKeys { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }
                .mapValues { (_, counts) -> counts.sum() }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(artistLimit)
                .map { it.key to it.value }
            return ListeningStats(
                topSongs = counted.sortedByDescending { it.second }.take(songLimit),
                topArtists = artists,
                totalMinutes = counted.sumOf { (track, count) -> track.durationSeconds.toLong() * count } / 60,
                totalPlays = counted.sumOf { it.second },
            )
        }
    }
}
