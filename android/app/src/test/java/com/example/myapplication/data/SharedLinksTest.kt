package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinksTest {
    @Test
    fun songLinksFromEveryYouTubeHost() {
        assertEquals(SharedLink.Song("BddP6PYo2gs"), SharedLinks.parse("https://music.youtube.com/watch?v=BddP6PYo2gs&si=abc"))
        assertEquals(SharedLink.Song("BddP6PYo2gs"), SharedLinks.parse("https://www.youtube.com/watch?v=BddP6PYo2gs&list=RDBddP6PYo2gs"))
        assertEquals(SharedLink.Song("BddP6PYo2gs"), SharedLinks.parse("https://youtu.be/BddP6PYo2gs?si=xyz"))
        assertEquals(SharedLink.Song("BddP6PYo2gs"), SharedLinks.parse("https://youtube.com/shorts/BddP6PYo2gs"))
    }

    @Test
    fun linksInsideSharedText() {
        assertEquals(
            SharedLink.Song("BddP6PYo2gs"),
            SharedLinks.parse("Listen to Kesariya: https://music.youtube.com/watch?v=BddP6PYo2gs&feature=share."),
        )
    }

    @Test
    fun playlistsAlbumsAndArtists() {
        assertEquals(SharedLink.Playlist("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"), SharedLinks.parse("https://music.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))
        assertEquals(SharedLink.Album("MPREb_abc123XYZ"), SharedLinks.parse("https://music.youtube.com/browse/MPREb_abc123XYZ"))
        assertEquals(SharedLink.Artist("UCDxKh1gFWeYsqePvgVzmPoQ"), SharedLinks.parse("https://music.youtube.com/channel/UCDxKh1gFWeYsqePvgVzmPoQ"))
    }

    @Test
    fun ignoresOtherSitesAndJunk() {
        assertNull(SharedLinks.parse("https://example.com/watch?v=BddP6PYo2gs"))
        assertNull(SharedLinks.parse("no link here"))
        assertNull(SharedLinks.parse("https://www.youtube.com/watch?v=short"))
    }
}
