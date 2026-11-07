package com.moodify.app.data.social

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.moodify.app.MainActivity
import com.moodify.app.R

/**
 * Chat notifications, built on this phone from the decrypted message
 * (nothing readable ever goes through a push service). One per friend,
 * stacking that friend's latest messages.
 */
class ChatNotifier(private val context: Context) {
    private val recent = mutableMapOf<String, MutableList<String>>()

    fun show(peer: String, name: String, preview: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel()
        val lines = synchronized(recent) { recent.getOrPut(peer) { mutableListOf() }.apply { add(preview); while (size > 5) removeAt(0) }.toList() }
        val sender = Person.Builder().setName(name).setKey(peer).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        lines.forEach { style.addMessage(it, System.currentTimeMillis(), sender) }
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("moodify://chat/$peer"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setContentTitle(name)
            .setContentText(preview)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, peer.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(TAG, peer.hashCode(), notification) }
    }

    fun cancel(peer: String) {
        synchronized(recent) { recent.remove(peer) }
        NotificationManagerCompat.from(context).cancel(TAG, peer.hashCode())
    }

    fun cancelAll() {
        val peers = synchronized(recent) { recent.keys.toList().also { recent.clear() } }
        peers.forEach { NotificationManagerCompat.from(context).cancel(TAG, it.hashCode()) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages from friends", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Chat messages and songs your friends send you" },
        )
    }

    companion object {
        private const val CHANNEL = "chat"
        private const val TAG = "moodify-chat"

        /** What the notification says, for each kind of message. */
        fun preview(kind: String, parsed: SharePayload.Parsed): String = when (kind) {
            "text" -> parsed.text.orEmpty()
            "track" -> "🎵 ${parsed.track?.title.orEmpty()}"
            "collection" -> "💿 ${parsed.collection?.title.orEmpty()}"
            "room" -> "🎧 Invited you to listen together"
            else -> "New message"
        }
    }
}
