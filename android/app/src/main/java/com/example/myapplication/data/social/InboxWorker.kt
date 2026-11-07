package com.moodify.app.data.social

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moodify.app.AppGraph
import com.moodify.app.R
import java.util.concurrent.TimeUnit

/**
 * Fetches the inbox in the background: right away after a wake-up ping
 * ([runNow], see Push.kt), and about every 15 minutes as a fallback that needs
 * no Google services ([schedule]).
 */
@UnstableApi
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { AppGraph.social.syncInBackground() }
        return Result.success()
    }

    /** Only used for an expedited run on Android 11 and older, which needs a (brief) notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(SYNC_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(SYNC_CHANNEL, "Checking for messages", NotificationManager.IMPORTANCE_MIN))
        }
        val notification = NotificationCompat.Builder(applicationContext, SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Checking for messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        return ForegroundInfo(SYNC_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NAME = "moodify-inbox"
        private const val NOW = "moodify-inbox-now"
        private const val SYNC_CHANNEL = "inbox-sync"
        private const val SYNC_NOTIFICATION_ID = 7301

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** After a wake-up ping: fetch now. Pings that arrive meanwhile queue one more run. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<InboxWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(NOW)
        }
    }
}
