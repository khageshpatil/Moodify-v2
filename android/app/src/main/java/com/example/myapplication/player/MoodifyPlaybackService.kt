package com.moodify.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.moodify.app.MainActivity
import com.moodify.app.R
import androidx.media3.session.DefaultMediaNotificationProvider

/**
 * Foreground service that keeps the process alive for background audio playback
 * and provides a persistent media notification with controls.
 *
 * Uses Media3 [MediaLibraryService] which automatically manages the foreground
 * notification, lock-screen controls, and Bluetooth/headphone button support —
 * all wired directly to ExoPlayer via [MediaSession].
 */
@UnstableApi
class MoodifyPlaybackService : MediaLibraryService() {

    companion object {
        private const val CHANNEL_ID = "moodify_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ROOT_ID = "moodify_root"
    }

    private var mediaSession: MediaLibraryService.MediaLibrarySession? = null
    private var activityIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.moodify_playback_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_launcher_foreground) }
        )
        val playbackManager = PlaybackManager.getInstance(applicationContext)

        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        this.activityIntent = activityIntent

        // MediaSessionService normally promotes itself when playback starts.
        // PlaybackManager starts this service before the network resolver
        // finishes, so explicitly publish a notification now or Android 12+
        // will kill the process with ForegroundServiceDidNotStartInTimeException.
        val bootstrapNotification = buildBootstrapNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                bootstrapNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, bootstrapNotification)
        }

        val player = playbackManager.getExoPlayer()

        // Tapping the notification opens the app
        mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, player, object : MediaLibraryService.MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val root = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Moodify")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }

            override fun onGetChildren(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val state = PlaybackManager.instance.state.value
                val children = when (parentId) {
                    ROOT_ID -> listOf(
                        browsableItem("queue", "Queue"),
                        browsableItem("downloads", "Downloads")
                    )
                    "queue" -> state.queue.map { track ->
                        MediaItem.Builder()
                            .setMediaId(track.id)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.artist)
                                    .setAlbumTitle(track.album)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .build()
                            )
                            .build()
                    }
                    else -> emptyList()
                }
                val from = (page * pageSize).coerceAtMost(children.size)
                val to = if (pageSize <= 0) children.size else (from + pageSize).coerceAtMost(children.size)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, to)), params)
                )
            }
        }).setSessionActivity(activityIntent)
            .build()
        // PlaybackManager controls ExoPlayer directly and does not bind an
        // app-side MediaController. Register the session explicitly so the
        // service's MediaNotificationManager owns its notification lifecycle.
        addSession(checkNotNull(mediaSession))

        // MediaLibraryService now owns notification updates. Its MediaStyle
        // notification is derived from this session and the current media
        // metadata, so Android receives the title, artist, artwork, and
        // transport controls instead of a manually replaced text card.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    // ── Notification channel (required on API 26+) ────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "moodify_playback",
                "Moodify Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current track and playback controls"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildBootstrapNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Starting playback")
            .setContentIntent(activityIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun browsableItem(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

}
