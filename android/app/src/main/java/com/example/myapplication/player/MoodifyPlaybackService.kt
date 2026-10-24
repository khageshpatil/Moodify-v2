package com.example.myapplication.player

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
import com.example.myapplication.MainActivity
import com.example.myapplication.R

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val playbackManager = PlaybackManager.getInstance(applicationContext)

        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // MediaSessionService normally promotes itself when playback starts.
        // PlaybackManager starts this service before the network resolver
        // finishes, so explicitly publish a notification now or Android 12+
        // will kill the process with ForegroundServiceDidNotStartInTimeException.
        val currentTrack = playbackManager.state.value.currentTrack
        val bootstrapNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(currentTrack?.title ?: "Moodify")
            .setContentText(currentTrack?.artist ?: "Starting playback…")
            .setContentIntent(activityIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .build()

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
