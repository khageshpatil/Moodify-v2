package com.moodify.app.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.moodify.app.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A download that is queued or running, for the Downloads screen. */
data class ActiveDownload(val track: Track, val progress: Int?, val isRunning: Boolean)

/**
 * Downloads one song in the background. Runs under WorkManager so a download
 * keeps going after the listener leaves the screen or the app, waits for a
 * connection, and retries a couple of times on failure.
 */
@UnstableApi
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val track = inputData.getString(KEY_TRACK)
            ?.let { runCatching { Gson().fromJson(it, Track::class.java) }.getOrNull() }
            ?: return Result.failure()
        // The player owns ExoPlayer, which must be created on the main thread.
        val manager = withContext(Dispatchers.Main) { PlaybackManager.getInstance(applicationContext) }
        return try {
            manager.downloadTrack(track) { progress ->
                setProgressAsync(workDataOf(KEY_PROGRESS to progress))
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "moodify-download"
        private const val TRACK_TAG_PREFIX = "moodify-track:"
        private const val KEY_TRACK = "track"
        private const val KEY_PROGRESS = "progress"
        private const val MAX_ATTEMPTS = 2

        /** Queues [tracks] for download; songs already queued are left alone. */
        fun enqueue(context: Context, tracks: List<Track>) {
            val gson = Gson()
            val workManager = WorkManager.getInstance(context)
            tracks.forEach { track ->
                val json = gson.toJson(track.copy(isFavorite = false))
                val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf(KEY_TRACK to json))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(TAG)
                    // The track travels in a tag so the queue survives process death.
                    .addTag(TRACK_TAG_PREFIX + json)
                    .build()
                workManager.enqueueUniqueWork("download-${track.id}", ExistingWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context, trackId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("download-$trackId")
        }

        /** Queued and running downloads, oldest first. */
        fun observeActive(context: Context): Flow<List<ActiveDownload>> {
            val gson = Gson()
            return WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
                infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
                    .mapNotNull { info ->
                        val json = info.tags.firstOrNull { it.startsWith(TRACK_TAG_PREFIX) }?.removePrefix(TRACK_TAG_PREFIX)
                            ?: return@mapNotNull null
                        val track = runCatching { gson.fromJson(json, Track::class.java) }.getOrNull() ?: return@mapNotNull null
                        ActiveDownload(
                            track = track,
                            progress = info.progress.getInt(KEY_PROGRESS, -1).takeIf { it >= 0 },
                            isRunning = info.state == WorkInfo.State.RUNNING,
                        )
                    }
            }
        }
    }
}
