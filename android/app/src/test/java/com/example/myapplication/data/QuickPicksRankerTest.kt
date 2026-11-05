package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuickPicksRankerTest {
    private fun t(id: String, artist: String = "Artist $id") = Track(id = id, title = "Song $id", artist = artist)

    @Test
    fun songsSeveralSeedsAgreeOnRankFirst() {
        val seeds = listOf(t("s1"), t("s2"))
        val radios = listOf(
            listOf(t("a"), t("shared"), t("b")),
            listOf(t("c"), t("shared"), t("d")),
        )
        // limit 1 with a pool of 3: "shared" must be in the pool's top and, as the only
        // multi-source song, ranks first before rotation.
        val ranked = QuickPicksRanker.rank(seeds, radios, emptySet(), limit = 5, random = Random(1))
        assertTrue(ranked.any { it.id == "shared" })
        assertEquals(5, ranked.size)
    }

    @Test
    fun seedsAndKnownSongsAreExcluded() {
        val seeds = listOf(t("s1"))
        val radios = listOf(listOf(t("s1"), t("known"), t("new1"), t("new2")))
        val ranked = QuickPicksRanker.rank(seeds, radios, excludeIds = setOf("known"), limit = 10)
        assertFalse(ranked.any { it.id == "s1" || it.id == "known" })
        assertEquals(setOf("new1", "new2"), ranked.map { it.id }.toSet())
    }

    @Test
    fun rotationOnlyDrawsFromTheStrongestCandidates() {
        val seeds = listOf(t("s1"), t("s2"))
        val strong = (1..3).map { t("strong$it") }
        val radios = listOf(strong + (1..20).map { t("weakA$it") }, strong + (1..20).map { t("weakB$it") })
        repeat(5) { seed ->
            val ranked = QuickPicksRanker.rank(seeds, radios, emptySet(), limit = 1, random = Random(seed))
            // Pool = top 3 = the songs both seeds share.
            assertTrue(ranked.single().id.startsWith("strong"))
        }
    }

    @Test
    fun artistKeysSplitCollaborations() {
        assertEquals(listOf("a", "b", "c"), QuickPicksRanker.artistKeys("A, B & C"))
        assertEquals(emptyList<String>(), QuickPicksRanker.artistKeys("Unknown artist"))
    }

    @Test
    fun seedsPreferMostPlayedThenRecentThenLiked() {
        val seeds = QuickPicksRecommender.pickSeeds(
            mostPlayed = listOf(t("m1"), t("m2")),
            recent = listOf(t("m1"), t("r1")),
            liked = listOf(t("l1"), t("l2"), t("l3"), t("l4")),
        )
        assertEquals(listOf("m1", "m2", "r1", "l1", "l2", "l3"), seeds.map { it.id })
    }
}
