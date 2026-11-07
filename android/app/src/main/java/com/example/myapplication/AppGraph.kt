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

    /** Friends, presence and shares (social M2). Created on first use. */
    val social by lazy { com.moodify.app.data.social.SocialRepository(app, playbackManager.state, playbackManager::currentPositionMs) }

    /** Listen together (social M4): one room at a time. */
    val rooms by lazy {
        com.moodify.app.data.social.RoomController(
            client = social.socialClient,
            playback = playbackManager,
            token = { social.sessionToken() },
            sendInvite = { friend, roomId, track -> social.sendRoomInvite(friend, roomId, track) },
            presenceOf = { code -> social.presenceOf(code) },
            serverClock = { social.serverClockOffsetMs() },
            syncClock = { social.syncClock() },
        )
    }
}
