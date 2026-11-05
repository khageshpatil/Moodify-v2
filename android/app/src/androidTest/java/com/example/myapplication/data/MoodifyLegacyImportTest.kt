package com.moodify.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoodifyLegacyImportTest {
    private lateinit var database: MoodifyDatabase
    private lateinit var dao: MoodifyDao

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MoodifyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.moodifyDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun playlistImportCanResumeAfterPartialPreviousImportWithoutOverwritingRoomEdits() = runBlocking {
        val first = LocalPlaylistEntity("legacy-1", "Legacy one", 1L, 1L)
        val second = LocalPlaylistEntity("legacy-2", "Legacy two", 2L, 2L)
        val firstTrack = PlaylistTrackEntity("legacy-1", "track-1", "{}", 0)
        val secondTrack = PlaylistTrackEntity("legacy-2", "track-2", "{}", 0)

        // Simulate Room containing the first part of an interrupted import.
        dao.importLegacyPlaylistSnapshot(listOf(first), listOf(firstTrack))
        dao.upsertPlaylist(first.copy(name = "Edited in Room", updatedAtMs = 10L))

        // Replaying the full legacy snapshot imports what was missing, but
        // IGNORE conflicts preserve the already imported and edited records.
        dao.importLegacyPlaylistSnapshot(
            listOf(first, second),
            listOf(firstTrack, secondTrack),
        )

        val playlists = dao.playlists().associateBy { it.id }
        assertEquals(setOf("legacy-1", "legacy-2"), playlists.keys)
        assertEquals("Edited in Room", playlists.getValue("legacy-1").name)
        assertEquals(listOf(firstTrack), dao.playlistTracks("legacy-1"))
        assertEquals(listOf(secondTrack), dao.playlistTracks("legacy-2"))
    }

    @Test
    fun playlistImportRollsBackParentWhenTrackInsertFails() = runBlocking {
        val playlist = LocalPlaylistEntity("broken", "Broken", 1L, 1L)
        val track = PlaylistTrackEntity("broken", "track", "{}", 0)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_playlist_track BEFORE INSERT ON playlist_tracks " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                dao.importLegacyPlaylistSnapshot(listOf(playlist), listOf(track))
            }
        }
        assertFalse(dao.playlists().any { it.id == "broken" })
    }

    @Test
    fun pinnedPlaylistsPersistAndSortAheadOfRecentlyEditedPlaylists() = runBlocking {
        dao.upsertPlaylist(LocalPlaylistEntity("recent", "Recently edited", 1L, 50L))
        dao.upsertPlaylist(LocalPlaylistEntity("pinned", "Pinned", 2L, 2L))

        dao.setPlaylistPinned("pinned", true, updatedAtMs = 60L)

        val ordered = dao.playlists()
        assertEquals(listOf("pinned", "recent"), ordered.map { it.id })
        assertTrue(ordered.first().isPinned)
    }

    @Test
    fun historyImportMergesMissingTracksAndCanBeRepeated() = runBlocking {
        val first = ListeningHistoryEntity(trackId = "legacy-1", trackJson = "{}", playedAtMs = 10L)
        val second = ListeningHistoryEntity(trackId = "legacy-2", trackJson = "{}", playedAtMs = 20L)
        dao.importLegacyHistory(listOf(first), limit = 100)
        dao.addHistory(
            ListeningHistoryEntity(trackId = "room-new", trackJson = "{}", playedAtMs = 30L),
        )

        dao.importLegacyHistory(listOf(first, second), limit = 100)
        dao.importLegacyHistory(listOf(first, second), limit = 100)

        val rows = dao.history(100)
        assertEquals(setOf("legacy-1", "legacy-2", "room-new"), rows.map { it.trackId }.toSet())
        assertEquals(1, rows.count { it.trackId == "legacy-1" })
        assertTrue(rows.any { it.trackId == "room-new" })
    }
}
