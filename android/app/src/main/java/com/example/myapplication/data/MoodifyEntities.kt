package com.moodify.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "catalog_tracks")
data class CatalogTrackEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val durationSeconds: Int,
    val updatedAtMs: Long,
)

@Entity(tableName = "listening_history")
data class ListeningHistoryEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val trackJson: String,
    val playedAtMs: Long,
)

@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val trackJson: String,
    val position: Int,
)

@Entity(tableName = "queue_snapshots")
data class QueueSnapshotEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val queueJson: String,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: Boolean,
    val playbackSpeed: Float = 1f,
    val pitch: Float = 1f,
    val durationMs: Long = 0L,
    val updatedAtMs: Long,
)

@Entity(tableName = "catalog_pages")
data class CatalogPageCacheEntity(
    @androidx.room.PrimaryKey val cacheKey: String,
    val localeLanguage: String,
    val localeRegion: String,
    val pageJson: String,
    val savedAtMs: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @androidx.room.PrimaryKey val trackId: String,
    /** Serialized [Track]; blank only for favorites saved before schema v6. */
    @ColumnInfo(defaultValue = "") val trackJson: String = "",
    @ColumnInfo(defaultValue = "0") val addedAtMs: Long = 0L,
)

/** How often each track has been played, for personal recommendations. */
@Entity(tableName = "play_stats")
data class PlayStatEntity(
    @androidx.room.PrimaryKey val trackId: String,
    val trackJson: String,
    val playCount: Int,
    val lastPlayedAtMs: Long,
)

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @androidx.room.PrimaryKey val trackId: String,
    val documentJson: String,
    val savedAtMs: Long,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @androidx.room.PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtUrl: String = "",
    val durationSeconds: Int = 0,
    val filePath: String,
    val mimeType: String,
    val bytes: Long,
    val updatedAtMs: Long,
)

@Dao
interface MoodifyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<CatalogTrackEntity>)

    @Query("SELECT * FROM catalog_tracks WHERE id IN (:ids)")
    suspend fun tracks(ids: List<String>): List<CatalogTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogPage(page: CatalogPageCacheEntity)

    @Query("SELECT * FROM catalog_pages WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun catalogPage(cacheKey: String): CatalogPageCacheEntity?

    @Query("DELETE FROM catalog_pages WHERE localeLanguage != :language OR localeRegion != :region")
    suspend fun deleteCatalogPagesForOtherLocale(language: String, region: String)

    @Query("DELETE FROM catalog_pages WHERE savedAtMs < :cutoffMs")
    suspend fun pruneCatalogPages(cutoffMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: LocalPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importLegacyPlaylists(playlists: List<LocalPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistTracks(tracks: List<PlaylistTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importLegacyPlaylistTracks(tracks: List<PlaylistTrackEntity>)

    @Transaction
    suspend fun importLegacyPlaylistSnapshot(
        playlists: List<LocalPlaylistEntity>,
        tracks: List<PlaylistTrackEntity>,
    ) {
        importLegacyPlaylists(playlists)
        importLegacyPlaylistTracks(tracks)
    }

    @Query("SELECT * FROM local_playlists ORDER BY isPinned DESC, updatedAtMs DESC")
    suspend fun playlists(): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlists ORDER BY isPinned DESC, updatedAtMs DESC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position")
    fun observePlaylistTracks(): Flow<List<PlaylistTrackEntity>>

    @Query("UPDATE local_playlists SET isPinned = :isPinned, updatedAtMs = :updatedAtMs WHERE id = :playlistId")
    suspend fun setPlaylistPinned(playlistId: String, isPinned: Boolean, updatedAtMs: Long)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun playlistTracks(playlistId: String): List<PlaylistTrackEntity>

    @Query("DELETE FROM local_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deletePlaylistTracks(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deletePlaylistTrack(playlistId: String, trackId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQueue(snapshot: QueueSnapshotEntity)

    @Query("SELECT * FROM queue_snapshots WHERE id = 1")
    suspend fun queue(): QueueSnapshotEntity?

    @Query("DELETE FROM queue_snapshots WHERE id = 1")
    suspend fun clearQueue()

    @Query("SELECT trackId FROM favorites")
    suspend fun favoriteIds(): List<String>

    @Query("SELECT * FROM favorites ORDER BY addedAtMs DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun deleteFavorite(trackId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorites(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites")
    suspend fun clearFavorites()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addHistory(entry: ListeningHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importLegacyHistoryEntries(entries: List<ListeningHistoryEntity>)

    @Query("SELECT DISTINCT trackId FROM listening_history")
    suspend fun historyTrackIds(): List<String>

    @Transaction
    suspend fun importLegacyHistory(entries: List<ListeningHistoryEntity>, limit: Int) {
        val existingTrackIds = historyTrackIds().toSet()
        importLegacyHistoryEntries(entries.filterNot { it.trackId in existingTrackIds })
        pruneHistory(limit)
    }

    @Query("SELECT * FROM listening_history ORDER BY playedAtMs DESC LIMIT :limit")
    suspend fun history(limit: Int): List<ListeningHistoryEntity>

    @Query("SELECT * FROM listening_history ORDER BY playedAtMs DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<ListeningHistoryEntity>>

    @Query("DELETE FROM listening_history")
    suspend fun clearHistory()

    @Query("DELETE FROM listening_history WHERE trackId = :trackId")
    suspend fun deleteHistoryForTrack(trackId: String)

    @Query("DELETE FROM listening_history WHERE id NOT IN (SELECT id FROM listening_history ORDER BY playedAtMs DESC LIMIT :limit)")
    suspend fun pruneHistory(limit: Int)

    @Query("SELECT * FROM play_stats WHERE trackId = :trackId")
    suspend fun playStat(trackId: String): PlayStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayStat(stat: PlayStatEntity)

    @Transaction
    suspend fun recordPlay(trackId: String, trackJson: String, playedAtMs: Long) {
        val existing = playStat(trackId)
        upsertPlayStat(
            PlayStatEntity(
                trackId = trackId,
                trackJson = trackJson,
                playCount = (existing?.playCount ?: 0) + 1,
                lastPlayedAtMs = playedAtMs,
            ),
        )
    }

    @Query("SELECT * FROM play_stats ORDER BY playCount DESC, lastPlayedAtMs DESC LIMIT :limit")
    suspend fun mostPlayed(limit: Int): List<PlayStatEntity>

    /** Songs played several times that have not come up for a while. */
    @Query("SELECT * FROM play_stats WHERE playCount >= :minPlays AND lastPlayedAtMs < :cutoffMs ORDER BY playCount DESC LIMIT :limit")
    suspend fun forgotten(cutoffMs: Long, minPlays: Int, limit: Int): List<PlayStatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyrics(entry: LyricsCacheEntity)

    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun lyrics(trackId: String): LyricsCacheEntity?

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun deleteLyrics(trackId: String)

    @Query("SELECT * FROM downloads")
    suspend fun downloads(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(entry: DownloadEntity)

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun deleteDownload(trackId: String)

    @Query("DELETE FROM catalog_tracks WHERE updatedAtMs < :cutoffMs")
    suspend fun pruneCatalog(cutoffMs: Long)
}

@Database(
    entities = [
        CatalogTrackEntity::class,
        ListeningHistoryEntity::class,
        LocalPlaylistEntity::class,
        PlaylistTrackEntity::class,
        QueueSnapshotEntity::class,
        DownloadEntity::class,
        FavoriteEntity::class,
        LyricsCacheEntity::class,
        CatalogPageCacheEntity::class,
        PlayStatEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class MoodifyDatabase : RoomDatabase() {
    abstract fun moodifyDao(): MoodifyDao

    companion object {
        @Volatile private var instance: MoodifyDatabase? = null

        fun get(context: android.content.Context): MoodifyDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    MoodifyDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build().also { instance = it }
            }

        /** Builds the same migration-aware database under an isolated name. */
        internal fun openForMigrationTest(
            context: android.content.Context,
            databaseName: String,
        ): MoodifyDatabase = androidx.room.Room.databaseBuilder(
            context.applicationContext,
            MoodifyDatabase::class.java,
            databaseName,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

        private const val DATABASE_NAME = "moodify.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "queue_snapshots", "playbackSpeed", "REAL NOT NULL DEFAULT 1.0")
                addColumnIfMissing(db, "queue_snapshots", "pitch", "REAL NOT NULL DEFAULT 1.0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorites (trackId TEXT NOT NULL, PRIMARY KEY(trackId))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lyrics_cache (trackId TEXT NOT NULL, documentJson TEXT NOT NULL, savedAtMs INTEGER NOT NULL, PRIMARY KEY(trackId))",
                )
            }

            private fun addColumnIfMissing(
                database: SupportSQLiteDatabase,
                table: String,
                column: String,
                definition: String,
            ) {
                database.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var exists = false
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                            exists = true
                            break
                        }
                    }
                    if (!exists) {
                        database.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
                    }
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "queue_snapshots", "durationMs", "INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS catalog_pages (cacheKey TEXT NOT NULL, localeLanguage TEXT NOT NULL, localeRegion TEXT NOT NULL, pageJson TEXT NOT NULL, savedAtMs INTEGER NOT NULL, PRIMARY KEY(cacheKey))",
                )
            }

            private fun addColumnIfMissing(
                database: SupportSQLiteDatabase,
                table: String,
                column: String,
                definition: String,
            ) {
                database.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var exists = false
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                            exists = true
                            break
                        }
                    }
                    if (!exists) database.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "downloads", "album", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "downloads", "albumArtUrl", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "downloads", "durationSeconds", "INTEGER NOT NULL DEFAULT 0")
            }

            private fun addColumnIfMissing(
                database: SupportSQLiteDatabase,
                table: String,
                column: String,
                definition: String,
            ) {
                database.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var exists = false
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                            exists = true
                            break
                        }
                    }
                    if (!exists) database.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
                }
            }
        }

        /**
         * v6 stores each favorite's track metadata so Library can show
         * favorites after a restart. Existing rows are back-filled from the
         * newest history or playlist copy of the same track when one exists.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS favorites (trackId TEXT NOT NULL, PRIMARY KEY(trackId))")
                addColumnIfMissing(db, "favorites", "trackJson", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "favorites", "addedAtMs", "INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE favorites SET trackJson = COALESCE(" +
                        "(SELECT h.trackJson FROM listening_history h WHERE h.trackId = favorites.trackId ORDER BY h.playedAtMs DESC LIMIT 1), " +
                        "(SELECT p.trackJson FROM playlist_tracks p WHERE p.trackId = favorites.trackId LIMIT 1), " +
                        "'') WHERE trackJson = ''",
                )
            }

            private fun addColumnIfMissing(
                database: SupportSQLiteDatabase,
                table: String,
                column: String,
                definition: String,
            ) {
                database.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var exists = false
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                            exists = true
                            break
                        }
                    }
                    if (!exists) database.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
                }
            }
        }

        /** v7 adds play counts; existing history seeds one play per track. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS play_stats (trackId TEXT NOT NULL, trackJson TEXT NOT NULL, " +
                        "playCount INTEGER NOT NULL, lastPlayedAtMs INTEGER NOT NULL, PRIMARY KEY(trackId))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO play_stats (trackId, trackJson, playCount, lastPlayedAtMs) " +
                        "SELECT trackId, trackJson, 1, MAX(playedAtMs) FROM listening_history GROUP BY trackId",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "local_playlists", "isPinned", "INTEGER NOT NULL DEFAULT 0")
            }

            private fun addColumnIfMissing(
                database: SupportSQLiteDatabase,
                table: String,
                column: String,
                definition: String,
            ) {
                database.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var exists = false
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                            exists = true
                            break
                        }
                    }
                    if (!exists) database.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
                }
            }
        }
    }
}
