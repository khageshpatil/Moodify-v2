package com.moodify.app.data.social

import com.moodify.app.data.BrowseEndpoint
import com.moodify.app.data.CatalogItem
import com.moodify.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharePayloadTest {
    @Test
    fun songsRoundTrip() {
        val song = Track(id = "JGwWNGJdvx8", title = "Shape of You", artist = "Ed Sheeran", albumArtUrl = "https://i.ytimg.com/a.jpg", durationSeconds = 263, artistId = "UC0C-w0YjGpqDXGB8IHb662A")
        val (track, collection) = SharePayload.parse(SharePayload.track(song))!!
        assertEquals(song, track)
        assertNull(collection)
    }

    @Test
    fun collectionsRoundTrip() {
        val album = CatalogItem(type = "album", id = "MPREb_abc", title = "Divide", artworkUrl = "https://lh3.googleusercontent.com/x", endpoint = BrowseEndpoint("MPREb_abc", null, null, "MUSIC_PAGE_TYPE_ALBUM"))
        val (_, collection) = SharePayload.parse(SharePayload.collection(album))!!
        assertEquals(album, collection)
    }

    @Test
    fun roomInvitesRoundTrip() {
        val song = Track(id = "JGwWNGJdvx8", title = "Shape of You", artist = "Ed Sheeran", albumArtUrl = "https://i.ytimg.com/a.jpg", durationSeconds = 263)
        val parsed = SharePayload.parse(SharePayload.room("K7P2QX", song))!!
        assertEquals("K7P2QX", parsed.roomId)
        assertEquals(song, parsed.track)
        assertEquals("K7P2QX", SharePayload.parse(SharePayload.room("K7P2QX", null))!!.roomId)
        // Room codes are 6 Crockford characters; anything else is dropped.
        assertNull(SharePayload.parse("""{"v":1,"type":"room","room":{"id":"../x"}}"""))
    }

    @Test
    fun textAndReactionsCarryTheSendersMessageId() {
        val text = SharePayload.parse(SharePayload.text("  hey, listen to this ", mid = "mid-0001-abcd"))!!
        assertEquals("  hey, listen to this ", text.text)
        assertEquals("mid-0001-abcd", text.mid)
        assertEquals("text", text.type)
        assertEquals(SharePayload.MAX_TEXT, SharePayload.parse(SharePayload.text("x".repeat(5000)))!!.text!!.length)

        val reaction = SharePayload.parse(SharePayload.reaction(target = "mid-0001-abcd", emoji = "🔥"))!!
        assertEquals("mid-0001-abcd", reaction.reactionTarget)
        assertEquals("🔥", reaction.emoji)
        assertEquals("", SharePayload.parse(SharePayload.reaction("mid-0001-abcd", ""))!!.emoji)
        // A "reaction" that is really a message, or points nowhere, is dropped.
        assertNull(SharePayload.parse("""{"v":1,"type":"reaction","target":"mid-0001-abcd","emoji":"buy now"}"""))
        assertNull(SharePayload.parse("""{"v":1,"type":"reaction","target":"../x","emoji":"🔥"}"""))
        // Empty text isn't a message; a bad id is ignored rather than trusted.
        assertNull(SharePayload.parse("""{"v":1,"type":"text","text":"   "}"""))
        assertNull(SharePayload.parse("""{"v":1,"type":"text","mid":"<script>","text":"hi"}""")!!.mid)
    }

    @Test
    fun reactionsAreOnePerPerson() {
        var json = Reactions.apply("", "MOOD-AAAA-AAAA", "❤️")
        json = Reactions.apply(json, "MOOD-BBBB-BBBB", "🔥")
        json = Reactions.apply(json, "MOOD-AAAA-AAAA", "😂") // changing yours replaces it
        assertEquals(mapOf("MOOD-AAAA-AAAA" to "😂", "MOOD-BBBB-BBBB" to "🔥"), Reactions.parse(json))
        json = Reactions.apply(json, "MOOD-AAAA-AAAA", "")
        assertEquals(mapOf("MOOD-BBBB-BBBB" to "🔥"), Reactions.parse(json))
        assertEquals("", Reactions.apply(json, "MOOD-BBBB-BBBB", ""))
        assertEquals(emptyMap<String, String>(), Reactions.parse("not json"))
    }

    @Test
    fun hostileOrUnknownPayloadsAreDropped() {
        assertNull(SharePayload.parse("not json"))
        assertNull(SharePayload.parse("""{"v":2,"type":"track"}"""))
        assertNull(SharePayload.parse("""{"v":1,"type":"poll"}"""))
        assertNull(SharePayload.parse("""{"v":1,"type":"track","track":{"id":"../../x"}}"""))
        // Non-https artwork is stripped rather than loaded.
        val (track, _) = SharePayload.parse("""{"v":1,"type":"track","track":{"id":"JGwWNGJdvx8","title":"t","artist":"a","artworkUrl":"http://evil/x"}}""")!!
        assertEquals("", track!!.albumArtUrl)
    }

    @Test
    fun playlistsRoundTripAndAreCapped() {
        val songs = (1..3).map { Track(id = "JGwWNGJdvx$it", title = "Song $it", artist = "Artist", albumArtUrl = "https://i.ytimg.com/$it.jpg", durationSeconds = 200) }
        val parsed = SharePayload.parse(SharePayload.playlist("Road trip", songs, mid = "mid-0001-abcd"))!!
        assertEquals("playlist", parsed.type)
        assertEquals("mid-0001-abcd", parsed.mid)
        assertEquals("Road trip", parsed.playlist?.name)
        assertEquals(songs, parsed.playlist?.tracks)
        val many = (1..400).map { Track(id = "JGwWNGJdv%03d".format(it).takeLast(11).padStart(11, 'x'), title = "S$it", artist = "A") }
        assertEquals(SharePayload.MAX_PLAYLIST, SharePayload.parse(SharePayload.playlist("Big", many))!!.playlist?.tracks?.size)
        // A playlist with no playable songs isn't shown.
        assertNull(SharePayload.parse("""{"v":1,"type":"playlist","mid":"mid-0002-abcd","playlist":{"name":"x","tracks":[{"id":"bad"}]}}"""))
    }
}
