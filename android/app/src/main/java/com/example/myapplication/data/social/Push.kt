package com.moodify.app.data.social

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.moodify.app.AppGraph
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Wake-up pings (docs/social-protocol/chat.md §3). The server sends a ping
 * with no content, `{kind: "message"}`, through Firebase Cloud Messaging; this
 * phone then fetches its encrypted inbox and builds the notification itself.
 * Without Firebase (no google-services.json) or Google Play services, nothing
 * here runs and the 15-minute [InboxWorker] check still delivers messages.
 */
object PushTokens {
    /** True when this build has Firebase set up (FirebaseInitProvider found its resources). */
    fun available(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** This install's FCM token, or null without Firebase / Play services. */
    suspend fun current(context: Context): String? {
        if (!available(context)) return null
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    // Off in the manifest until there's a Moodify code; this is that moment.
                    FirebaseMessaging.getInstance().isAutoInitEnabled = true
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /** After the Moodify code is deleted: this phone stops getting pings at all. */
    fun forget(context: Context) {
        if (available(context)) runCatching {
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
            FirebaseMessaging.getInstance().deleteToken()
        }
    }
}

@UnstableApi
class MoodifyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AppGraph.social.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] == "message") InboxWorker.runNow(applicationContext)
    }
}
