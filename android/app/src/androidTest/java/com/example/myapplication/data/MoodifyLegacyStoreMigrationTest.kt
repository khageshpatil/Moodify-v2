package com.moodify.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moodify.app.player.ListeningHistoryEntry
import com.moodify.app.player.ListeningHistoryStore
import com.moodify.app.player.PlaybackPersistence
import com.moodify.app.player.PlaybackState
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoodifyLegacyStoreMigrationTest {
    private lateinit var context: Context
    private lateinit var database: MoodifyDatabase
    private val gson = Gson()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MoodifyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listOf("moodify_library", "moodify_history", "moodify_lyrics", "moodify_playback")
            .forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After
    fun tearDown() {
        database.close()
        listOf("moodify_library", "moodify_history", "moodify_lyrics", "moodify_playback")
            .forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun playlistAndHistoryStoresImportTheirRealPreferenceSnapshots() = runBlocking {
        val track = Track("legacy-track", "Old song", "Old artist", durationSeconds = 95)
        val playlistPreferences = context.getSharedPreferences("moodify_library", Context.MODE_PRIVATE)
        playlistPreferences.edit()
            .putString("playlists", gson.toJson(listOf(LocalPlaylist("legacy-list", "Old list", listOf(track)))))
            .commit()
        val historyPreferences = context.getSharedPreferences("moodify_history", Context.MODE_PRIVATE)
        historyPreferences.edit()
            .putString("entries", gson.toJson(listOf(ListeningHistoryEntry(track, 123_456L))))
            .commit()

        val playlistStore = PlaylistStore(context, database)
        val historyStore = ListeningHistoryStore(context, database)

        assertEquals(listOf(LocalPlaylist("legacy-list", "Old list", listOf(track))), playlistStore.list())
        assertEquals(track, historyStore.entries().single().track)
        assertEquals(123_456L, historyStore.entries().single().playedAtMs)
        assertFalse(playlistPreferences.contains("playlists"))
        assertFalse(historyPreferences.contains("entries"))
    }

    @Test
    fun lyricsStoreImportsValidEntriesAndRetainsMalformedEntriesForRetry() = runBlocking {
        val preferences = context.getSharedPreferences("moodify_lyrics", Context.MODE_PRIVATE)
        val document = LyricsDocument(
            lines = listOf(LyricsLine("Legacy lyric", startMs = 1_000L)),
            source = "legacy-provider",
            isSynced = true,
        )
        val cached = JSONObject()
            .put("savedAtMs", System.currentTimeMillis())
            .put("document", gson.toJson(document))
            .toString()
        preferences.edit()
            .putString("lyrics_legacy-track", cached)
            .putString("lyrics_unreadable", "not-json")
            .commit()

        val store = LyricsStore(context, database)
        val migrated = store.read("legacy-track")

        assertNotNull(migrated)
        assertEquals("Legacy lyric", migrated?.plainText)
        assertEquals("legacy-provider", migrated?.source)
        assertFalse(preferences.contains("lyrics_legacy-track"))
        assertEquals("not-json", preferences.getString("lyrics_unreadable", null))
    }

    @Test
    fun playbackPersistenceImportsQueueOptionsAndMergesFavorites() = runBlocking {
        val first = Track("queue-one", "First", "Artist", durationSeconds = 80)
        val second = Track("queue-two", "Second", "Artist", durationSeconds = 140)
        database.moodifyDao().upsertFavorites(listOf(FavoriteEntity("room-favorite")))
        val preferences = context.getSharedPreferences("moodify_playback", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("queue", gson.toJson(listOf(first, second)))
            .putInt("current_index", 1)
            .putLong("position_ms", 45_000L)
            .putBoolean("shuffle", true)
            .putBoolean("repeat", false)
            .putFloat("speed", 1.25f)
            .putFloat("pitch", 0.9f)
            .putString("favorites", gson.toJson(setOf("legacy-favorite", "room-favorite")))
            .commit()

        val persistence = PlaybackPersistence(context, database)
        val restored: PlaybackState? = persistence.restorePlaybackState()

        assertEquals(second, restored?.currentTrack)
        assertEquals(1, restored?.currentIndex)
        assertEquals(45_000L, restored?.positionMs)
        assertEquals(140_000L, restored?.durationMs)
        assertEquals(true, restored?.isShuffle)
        assertEquals(1.25f, restored?.playbackSpeed ?: 0f, 0f)
        assertEquals(0.9f, restored?.pitch ?: 0f, 0f)
        assertEquals(setOf("legacy-favorite", "room-favorite"), persistence.loadFavoriteIds())
        assertFalse(preferences.contains("queue"))
        assertFalse(preferences.contains("favorites"))
        val migratedQueue = database.moodifyDao().queue()
        assertNotNull(migratedQueue)
        assertEquals(1, migratedQueue?.currentIndex)
        assertEquals(45_000L, migratedQueue?.positionMs)
    }

    @Test
    fun playbackPersistenceRetainsMalformedLegacyJsonForRecovery() = runBlocking {
        val preferences = context.getSharedPreferences("moodify_playback", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("queue", "{broken")
            .putString("favorites", "{broken")
            .putInt("current_index", 2)
            .commit()

        PlaybackPersistence(context, database).ensureImported()

        assertEquals("{broken", preferences.getString("queue", null))
        assertEquals("{broken", preferences.getString("favorites", null))
        assertEquals(2, preferences.getInt("current_index", -1))
        assertEquals(null, database.moodifyDao().queue())
        assertEquals(emptyList<String>(), database.moodifyDao().favoriteIds())
    }
}
