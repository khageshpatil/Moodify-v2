package com.moodify.app

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.moodify.app.data.CatalogCacheStore
import com.moodify.app.data.CatalogLocale
import com.moodify.app.data.InnerTubeCatalogClient
import com.moodify.app.data.LyricsStore
import com.moodify.app.data.MusicRepository
import com.moodify.app.data.PlaylistStore
import com.moodify.app.data.SettingsStore
import com.moodify.app.player.PlaybackManager
import com.moodify.app.player.PlaybackPersistence

/**
 * Process-wide dependencies shared by every ViewModel. Kept deliberately
 * small: a hand-rolled graph is enough for one module and avoids a DI library.
 */
@UnstableApi
object AppGraph {
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    /** Updated by MainViewModel whenever the language/region setting changes. */
    @Volatile
    var catalogLocale: CatalogLocale = CatalogLocale("en", "US")

    val settingsStore by lazy { SettingsStore(app) }
    val playlistStore by lazy { PlaylistStore(app) }
    val persistence by lazy { PlaybackPersistence(app) }
    val lyricsStore by lazy { LyricsStore(app) }
    val savedCollections by lazy { com.moodify.app.data.SavedCollectionsStore(app) }

    val repository by lazy {
        MusicRepository(
            catalogClient = InnerTubeCatalogClient(localeProvider = { catalogLocale }),
            catalogCache = CatalogCacheStore(app),
            localeProvider = { catalogLocale },
        )
    }

    val playbackManager: PlaybackManager
        get() = PlaybackManager.getInstance(app)
}
