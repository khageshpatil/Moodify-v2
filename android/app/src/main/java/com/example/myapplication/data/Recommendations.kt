package com.moodify.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlin.random.Random

/**
 * Personal "For you" picks.
 *
 * Each seed (a song the listener plays a lot) contributes its YouTube Music
 * radio. A candidate that several seeds agree on ranks higher; earlier seeds,
 * earlier radio positions and shared artists add weight. Songs the listener
 * already knows are excluded so the shelf is about discovery, and the top of
 * the ranking is rotated so Home feels fresh on every visit.
 */
object QuickPicksRanker {
    private const val SEED_WEIGHT = 100
    private const val ARTIST_AFFINITY = 80
    private const val POSITION_DEPTH = 30
    private const val ROTATION_POOL_MULTIPLIER = 3

    fun rank(
        seeds: List<Track>,
        radios: List<List<Track>>,
        excludeIds: Set<String>,
        limit: Int,
        random: Random = Random.Default,
    ): List<Track> {
        require(seeds.size == radios.size) { "one radio per seed" }
        val seedIds = seeds.mapTo(HashSet()) { it.id }
        val seedArtists = seeds.flatMapTo(HashSet()) { artistKeys(it.artist) }

        class Candidate(val track: Track, val firstSeen: Int) {
            var sources = 0
            var score = 0
        }

        val candidates = LinkedHashMap<String, Candidate>()
        radios.forEachIndexed { seedIndex, radio ->
            radio.distinctBy { it.id }.take(POSITION_DEPTH).forEachIndexed { position, track ->
                if (track.id in seedIds || track.id in excludeIds) return@forEachIndexed
                val candidate = candidates.getOrPut(track.id) { Candidate(track, candidates.size) }
                candidate.sources += 1
                candidate.score += (seeds.size - seedIndex) * SEED_WEIGHT +
                    (POSITION_DEPTH - position) +
                    if (artistKeys(track.artist).any(seedArtists::contains)) ARTIST_AFFINITY else 0
            }
        }
        val ranked = candidates.values
            .sortedWith(
                compareByDescending<Candidate> { it.sources }
                    .thenByDescending { it.score }
                    .thenBy { it.firstSeen },
            )
            .map { it.track }
        // Rotate within the strongest candidates rather than always showing
        // the same top items.
        return ranked.take(limit * ROTATION_POOL_MULTIPLIER).shuffled(random).take(limit)
    }

    /** Normalised artist names, splitting collaborations ("A, B & C"). */
    internal fun artistKeys(artist: String): List<String> =
        artist.split(",", "&", " x ", " feat. ", " ft. ")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != "unknown artist" }
}

/** Loads For-you picks from YouTube Music radios of the listener's seed songs. */
class QuickPicksRecommender(private val client: InnerTubeCatalogClient) {

    suspend fun load(seeds: List<Track>, excludeIds: Set<String>, limit: Int = 20): List<Track> {
        if (seeds.isEmpty()) return emptyList()
        val radios = supervisorScope {
            seeds.map { seed ->
                async {
                    runCatching { client.upNext(videoId = seed.id, playlistId = "RDAMVM${seed.id}").tracks }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        if (radios.all { it.isEmpty() }) return emptyList()
        return QuickPicksRanker.rank(seeds, radios, excludeIds, limit)
    }

    companion object {
        const val MAX_SEEDS = 6

        /**
         * Most-played songs first, topped up with recent plays and likes, so
         * new listeners still get recommendations after a couple of songs.
         */
        fun pickSeeds(mostPlayed: List<Track>, recent: List<Track>, liked: List<Track>): List<Track> =
            (mostPlayed + recent + liked).distinctBy { it.id }.take(MAX_SEEDS)
    }
}
