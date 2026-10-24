package com.example.myapplication.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Persistent LRU cache for already-read audio bytes.
 *
 * The cache is deliberately separate from downloads.  A short-lived CDN URL
 * may change between resolutions, while the Media3 cache key supplied by the
 * player is stable per video/format.
 */
class PlaybackCache(context: Context) {
    private val cacheDirectory = File(context.cacheDir, "moodify-player-cache")
    private val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)

    val cache = SimpleCache(
        cacheDirectory,
        LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
        databaseProvider
    )

    fun factory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        private const val MAX_CACHE_BYTES = 150L * 1024L * 1024L
    }
}
