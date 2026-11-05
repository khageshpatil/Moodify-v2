package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLyricsRepositorySelectionTest {
    private val repository = NativeLyricsRepository()
    private val requestedTrack = Track(
        id = "video-id",
        title = "Apna Bana Le (From \"Bhediya\")",
        artist = "Arijit Singh",
        durationSeconds = 261,
    )

    @Test
    fun lrclibSearchDoesNotFilterByUnreliableYoutubeAlbumMetadata() {
        val url = buildLrclibSearchUrl(
            title = requestedTrack.title,
            artist = requestedTrack.artist,
        )

        assertEquals(requestedTrack.title, url.queryParameter("track_name"))
        assertEquals(requestedTrack.artist, url.queryParameter("artist_name"))
        assertFalse(url.queryParameterNames.contains("album_name"))
    }

    @Test
    fun prefersCloseMatchingSyncedLyricsOverExactArtistPlainLyrics() {
        val plain = LyricsSearchResult(
            title = requestedTrack.title,
            artist = requestedTrack.artist,
            album = "",
            durationSeconds = 261,
            document = requireNotNull(LyricsParser.parse(
                "Tu mera koi na hoke bhi kuchh laage\nKiya re jo bhi tune",
                source = "YouLyPlus",
            )),
        )
        val synced = LyricsSearchResult(
            title = requestedTrack.title,
            artist = "Arijit Singh, Sachin-Jigar",
            album = "Bhediya",
            durationSeconds = 262,
            document = requireNotNull(LyricsParser.parse(
                "[00:22.14]Tu mera koi na hoke bhi kuchh laage\n[00:27.26]Tu mera koi na hoke bhi kuchh laage",
                source = "LRCLIB",
            )),
        )

        val selected = repository.selectBestSearchResult(requestedTrack, listOf(plain, synced))

        assertEquals("LRCLIB", selected?.document?.source)
        assertTrue(selected?.document?.isSynced == true)
    }

    @Test
    fun doesNotPreferAWeaklyMatchedSyncedCoverOverTheExactPlainTrack() {
        val exactPlain = LyricsSearchResult(
            title = requestedTrack.title,
            artist = requestedTrack.artist,
            album = "Bhediya",
            durationSeconds = 261,
            document = requireNotNull(LyricsParser.parse("Correct plain lyrics", source = "YouLyPlus")),
        )
        val weakSynced = LyricsSearchResult(
            title = "Apna Bana Le acoustic cover",
            artist = "Different Artist",
            album = "",
            durationSeconds = 261,
            document = requireNotNull(LyricsParser.parse("[00:01.00]Unrelated lyrics", source = "LRCLIB")),
        )

        val selected = repository.selectBestSearchResult(requestedTrack, listOf(weakSynced, exactPlain))

        assertEquals("YouLyPlus", selected?.document?.source)
        assertTrue(selected?.document?.isSynced == false)
    }

    @Test
    fun prefersWordTimedLyricsOverLineTimedLyricsForTheSameTrack() {
        val lineTimed = LyricsSearchResult(
            title = requestedTrack.title,
            artist = requestedTrack.artist,
            album = "Bhediya",
            durationSeconds = 261,
            document = requireNotNull(LyricsParser.parse(
                "[00:22.14]Tu mera koi na hoke bhi kuchh laage",
                source = "LRCLIB",
            )),
        )
        val wordTimed = LyricsSearchResult(
            title = requestedTrack.title,
            artist = requestedTrack.artist,
            album = "Bhediya",
            durationSeconds = 261,
            document = LyricsDocument(
                lines = listOf(
                    LyricsLine(
                        text = "Tu mera",
                        startMs = 22_140,
                        words = listOf(
                            LyricsWord("Tu", 22_140, 22_420),
                            LyricsWord("mera", 22_420, 22_900),
                        ),
                    ),
                ),
                source = "BetterLyrics",
                isSynced = true,
            ),
        )

        val selected = repository.selectBestSearchResult(requestedTrack, listOf(lineTimed, wordTimed))

        assertEquals("BetterLyrics", selected?.document?.source)
    }
}
