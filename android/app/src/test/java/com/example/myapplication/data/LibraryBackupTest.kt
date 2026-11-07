package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryBackupTest {
    @Test
    fun roundTripsPlaylistsLikesAndSavedItems() {
        val song = Track(id = "BddP6PYo2gs", title = "Kesariya", artist = "Arijit Singh", artistId = "UCDxKh1gFWeYsqePvgVzmPoQ")
        val backup = LibraryBackup(
            playlists = listOf(LibraryBackup.BackupPlaylist("Late nights", isPinned = true, tracks = listOf(song))),
            likedSongs = listOf(song),
            saved = listOf(CatalogItem(type = "album", id = "MPREb_abc", title = "Brahmastra", endpoint = BrowseEndpoint("MPREb_abc"))),
        )

        val restored = LibraryBackup.fromJson(backup.toJson())

        assertEquals(backup.playlists, restored?.playlists)
        assertEquals(backup.likedSongs, restored?.likedSongs)
        assertEquals(backup.saved, restored?.saved)
    }

    @Test
    fun rejectsFilesThatAreNotMoodifyBackups() {
        assertNull(LibraryBackup.fromJson("""{"hello":"world"}"""))
        assertNull(LibraryBackup.fromJson("not json"))
        assertNull(LibraryBackup.fromJson("""{"format":"moodify-library","version":99}"""))
    }

    @Test
    fun skipsBrokenEntriesInsteadOfFailing() {
        val json = """{"format":"moodify-library","version":1,
            "likedSongs":[{"id":"a1b2c3d4e5f","title":"Ok","artist":"Someone"},{"title":"No id"}],
            "playlists":[{"name":"Mix","tracks":[{"id":"x"}]}]}"""

        val restored = LibraryBackup.fromJson(json)

        assertEquals(1, restored?.likedSongs?.size)
        assertEquals(0, restored?.playlists?.single()?.tracks?.size)
        assertEquals(0, restored?.saved?.size)
    }
}
