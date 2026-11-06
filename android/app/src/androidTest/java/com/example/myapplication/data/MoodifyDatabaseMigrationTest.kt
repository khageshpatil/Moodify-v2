package com.moodify.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoodifyDatabaseMigrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPreviouslyShippedSchemaMigratesToLatestWithoutLosingQueueOrPlaylistState() = runBlocking {
        (1..5).forEach { oldVersion ->
            val databaseName = "moodify-migration-v$oldVersion.db"
            context.deleteDatabase(databaseName)
            createLegacyDatabase(databaseName, oldVersion)

            val migrated = MoodifyDatabase.openForMigrationTest(context, databaseName)
            try {
                val dao = migrated.moodifyDao()
                val queue = dao.queue()
                assertNotNull("queue should survive v$oldVersion migration", queue)
                assertEquals("queue-v$oldVersion", queue?.queueJson)
                assertEquals(42_000L, queue?.positionMs)
                assertEquals(1f, queue?.playbackSpeed ?: 0f, 0f)
                assertEquals(1f, queue?.pitch ?: 0f, 0f)
                assertEquals(if (oldVersion >= 3) 123_000L else 0L, queue?.durationMs)

                val download = dao.downloads().single()
                assertEquals("legacy-track", download.trackId)
                assertEquals("Legacy song", download.title)
                assertEquals("Legacy artist", download.artist)
                assertEquals("", download.album)
                assertEquals("", download.albumArtUrl)
                assertEquals(0, download.durationSeconds)
                assertEquals("/legacy/legacy-track.m4a", download.filePath)

                val playlist = dao.playlists().single()
                assertEquals("legacy-playlist", playlist.id)
                assertEquals("Legacy playlist", playlist.name)
                assertEquals("Existing playlists default to unpinned", false, playlist.isPinned)
                dao.setPlaylistPinned(playlist.id, true, 100L)
                assertEquals(true, dao.playlists().single().isPinned)

                dao.upsertCatalogPage(
                    CatalogPageCacheEntity(
                        cacheKey = "migration-check-$oldVersion",
                        localeLanguage = "en",
                        localeRegion = "US",
                        pageJson = "{}",
                        savedAtMs = oldVersion.toLong(),
                    ),
                )
                assertNotNull(dao.catalogPage("migration-check-$oldVersion"))

                // v8: the Library can keep albums, playlists and artists.
                dao.upsertSavedCollection(
                    SavedCollectionEntity(
                        id = "MPREb_v$oldVersion",
                        type = "album",
                        title = "Saved album",
                        subtitle = "",
                        artworkUrl = "",
                        params = null,
                        pageType = "MUSIC_PAGE_TYPE_ALBUM",
                        savedAtMs = 1L,
                    ),
                )
                assertEquals(listOf("MPREb_v$oldVersion"), dao.observeSavedCollections().first().map { it.id })

                // v6: favorites carry track metadata, back-filled from history.
                if (oldVersion >= 2) {
                    val favorite = dao.observeFavorites().first().single()
                    assertEquals("legacy-track", favorite.trackId)
                    assertEquals("{\"id\":\"legacy-track\"}", favorite.trackJson)
                }
            } finally {
                migrated.close()
                context.deleteDatabase(databaseName)
            }
        }
    }

    private fun createLegacyDatabase(name: String, version: Int) {
        val helper = object : SQLiteOpenHelper(context, name, null, version) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `catalog_tracks` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, `artworkUrl` TEXT NOT NULL, " +
                        "`durationSeconds` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE `listening_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, " +
                        "`trackJson` TEXT NOT NULL, `playedAtMs` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE `local_playlists` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        (if (version >= 5) "`isPinned` INTEGER NOT NULL DEFAULT 0, " else "") +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE `playlist_tracks` (" +
                        "`playlistId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `trackJson` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`))",
                )

                val queueColumns = buildList {
                    add("`id` INTEGER NOT NULL")
                    add("`queueJson` TEXT NOT NULL")
                    add("`currentIndex` INTEGER NOT NULL")
                    add("`positionMs` INTEGER NOT NULL")
                    add("`shuffle` INTEGER NOT NULL")
                    add("`repeat` INTEGER NOT NULL")
                    if (version >= 2) {
                        add("`playbackSpeed` REAL NOT NULL")
                        add("`pitch` REAL NOT NULL")
                    }
                    if (version >= 3) add("`durationMs` INTEGER NOT NULL")
                    add("`updatedAtMs` INTEGER NOT NULL")
                }
                db.execSQL(
                    "CREATE TABLE `queue_snapshots` (${queueColumns.joinToString()}, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE `downloads` (" +
                        "`trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        (if (version >= 4) "`album` TEXT NOT NULL DEFAULT '', `albumArtUrl` TEXT NOT NULL DEFAULT '', `durationSeconds` INTEGER NOT NULL DEFAULT 0, " else "") +
                        "`filePath` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `bytes` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`trackId`))",
                )
                db.execSQL(
                    "INSERT INTO `local_playlists` (`id`, `name`, `createdAtMs`, `updatedAtMs`) " +
                        "VALUES ('legacy-playlist', 'Legacy playlist', 3, 4)",
                )

                val queueColumnNames = buildList {
                    add("id")
                    add("queueJson")
                    add("currentIndex")
                    add("positionMs")
                    add("shuffle")
                    add("repeat")
                    if (version >= 2) {
                        add("playbackSpeed")
                        add("pitch")
                    }
                    if (version >= 3) add("durationMs")
                    add("updatedAtMs")
                }
                val queueValues = buildList<Any> {
                    add(1)
                    add("'queue-v$version'")
                    add(0)
                    add(42_000)
                    add(0)
                    add(0)
                    if (version >= 2) {
                        add(1.0)
                        add(1.0)
                    }
                    if (version >= 3) add(123_000)
                    add(7)
                }
                db.execSQL(
                    "INSERT INTO `queue_snapshots` (${queueColumnNames.joinToString { "`$it`" }}) " +
                        "VALUES (${queueValues.joinToString()})",
                )
                db.execSQL(
                    "INSERT INTO `downloads` (`trackId`, `title`, `artist`, `filePath`, `mimeType`, `bytes`, `updatedAtMs`) " +
                        "VALUES ('legacy-track', 'Legacy song', 'Legacy artist', '/legacy/legacy-track.m4a', 'audio/mp4', 1024, 9)",
                )

                if (version >= 2) {
                    db.execSQL("CREATE TABLE `favorites` (`trackId` TEXT NOT NULL, PRIMARY KEY(`trackId`))")
                    db.execSQL("INSERT INTO `favorites` (`trackId`) VALUES ('legacy-track')")
                    db.execSQL(
                        "INSERT INTO `listening_history` (`trackId`, `trackJson`, `playedAtMs`) " +
                            "VALUES ('legacy-track', '{\"id\":\"legacy-track\"}', 5)",
                    )
                    db.execSQL(
                        "CREATE TABLE `lyrics_cache` (`trackId` TEXT NOT NULL, `documentJson` TEXT NOT NULL, " +
                            "`savedAtMs` INTEGER NOT NULL, PRIMARY KEY(`trackId`))",
                    )
                }
                if (version >= 3) {
                    db.execSQL(
                        "CREATE TABLE `catalog_pages` (`cacheKey` TEXT NOT NULL, `localeLanguage` TEXT NOT NULL, " +
                            "`localeRegion` TEXT NOT NULL, `pageJson` TEXT NOT NULL, `savedAtMs` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`cacheKey`))",
                    )
                }
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
        helper.close()
    }
}
