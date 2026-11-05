package com.moodify.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextParserTest {
    private fun panelItem(id: String, title: String, byline: String, length: String, explicit: Boolean = false) = """
        {"playlistPanelVideoRenderer": {
          "videoId": "$id",
          "title": {"runs": [{"text": "$title"}]},
          "longBylineText": {"runs": [{"text": "$byline"}]},
          "lengthText": {"runs": [{"text": "$length"}]},
          "thumbnail": {"thumbnails": [{"url": "https://lh3.googleusercontent.com/x=w60-h60"}, {"url": "https://lh3.googleusercontent.com/x=w120-h120"}]}
          ${if (explicit) ""","badges": [{"musicInlineBadgeRenderer": {"icon": {"iconType": "MUSIC_EXPLICIT_BADGE"}}}]""" else ""}
        }}
    """.trimIndent()

    @Test
    fun parsesFirstPageWithContinuationAndRelatedTab() {
        val json = """
        {"contents": {"singleColumnMusicWatchNextResultsRenderer": {"tabbedRenderer": {"watchNextTabbedResultsRenderer": {"tabs": [
          {"tabRenderer": {"content": {"musicQueueRenderer": {"content": {"playlistPanelRenderer": {
            "contents": [
              ${panelItem("aaaaaaaaaaa", "First", "Artist One • Album One • 2021", "3:05")},
              ${panelItem("bbbbbbbbbbb", "Second", "Artist Two • 1.2M views", "4:10", explicit = true)}
            ],
            "continuations": [{"nextRadioContinuationData": {"continuation": "CONT123"}}]
          }}}}}},
          {"tabRenderer": {"endpoint": {"browseEndpoint": {"browseId": "MPLYtLyrics"}}}},
          {"tabRenderer": {"endpoint": {"browseEndpoint": {"browseId": "MPTRtRelated"}}}}
        ]}}}}}
        """.trimIndent()

        val page = CatalogRendererParser.parseUpNext(JSONObject(json))

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), page.tracks.map { it.id })
        val first = page.tracks[0]
        assertEquals("First", first.title)
        assertEquals("Artist One", first.artist)
        assertEquals("Album One", first.album)
        assertEquals(185, first.durationSeconds)
        assertTrue(first.albumArtUrl.endsWith("w120-h120"))
        assertEquals("", page.tracks[1].album) // view counts are not an album
        assertTrue(page.tracks[1].explicit)
        assertEquals("CONT123", page.continuation)
        assertEquals("MPTRtRelated", page.relatedBrowseId)
    }

    @Test
    fun parsesContinuationPage() {
        val json = """
        {"continuationContents": {"playlistPanelContinuation": {
          "contents": [${panelItem("ccccccccccc", "Third", "Artist Three", "2:00")}]
        }}}
        """.trimIndent()
        val page = CatalogRendererParser.parseUpNext(JSONObject(json))
        assertEquals(listOf("ccccccccccc"), page.tracks.map { it.id })
        assertNull(page.continuation)
    }

    @Test
    fun emptyResponseYieldsEmptyPage() {
        val page = CatalogRendererParser.parseUpNext(JSONObject("{}"))
        assertTrue(page.tracks.isEmpty())
    }
}
