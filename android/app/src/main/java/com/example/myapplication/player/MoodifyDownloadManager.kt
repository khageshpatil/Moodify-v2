package com.example.myapplication.player

import android.content.Context
import com.example.myapplication.data.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class DownloadedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String,
    val durationSeconds: Int,
    val filePath: String,
    val mimeType: String
)

/**
 * Resumable local downloads. Files are stored in app-private
 * storage, so no broad storage permission is needed on modern Android.
 */
class MoodifyDownloadManager(
    context: Context,
    private val resolver: InnerTubeStreamResolver,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val downloadDirectory = File(appContext.filesDir, "downloads").apply { mkdirs() }
    private val metadataFile = File(downloadDirectory, "downloads.json")
    private val gson = Gson()
    private val records = ConcurrentHashMap<String, DownloadedTrack>().apply { putAll(loadRecords()) }

    fun get(id: String): DownloadedTrack? = records[id]?.takeIf { File(it.filePath).isFile }

    fun all(): List<DownloadedTrack> = records.values
        .filter { File(it.filePath).isFile }
        .sortedBy { it.title.lowercase() }

    suspend fun download(track: Track): DownloadedTrack = withContext(Dispatchers.IO) {
        get(track.id)?.let { return@withContext it }

        val stream = resolver.resolve(track.id)
        val temporaryFile = File(downloadDirectory, "${track.id}.part")
        val targetFile = File(downloadDirectory, "${track.id}.${extensionFor(stream.mimeType)}")
        val resumeAt = temporaryFile.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
        val request = Request.Builder()
            .url(stream.url)
            .header("User-Agent", stream.userAgent)
            .apply { if (resumeAt > 0L) header("Range", "bytes=$resumeAt-") }
            .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed with HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Download returned no body")
            val append = resumeAt > 0L && response.code == 206
            if (!append && resumeAt > 0L) temporaryFile.delete()
            body.byteStream().use { input ->
                FileOutputStream(temporaryFile, append).use { output -> input.copyTo(output) }
            }
        }

        if (!temporaryFile.renameTo(targetFile)) {
            temporaryFile.delete()
            throw IOException("Could not finalize downloaded audio")
        }

        val record = DownloadedTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumArtUrl = track.albumArtUrl,
            durationSeconds = track.durationSeconds,
            filePath = targetFile.absolutePath,
            mimeType = stream.mimeType
        )
        records[track.id] = record
        saveRecords()
        record
    }

    fun delete(id: String) {
        records.remove(id)?.let { File(it.filePath).delete() }
        File(downloadDirectory, "$id.part").delete()
        saveRecords()
    }

    private fun loadRecords(): Map<String, DownloadedTrack> = runCatching {
        gson.fromJson<Map<String, DownloadedTrack>>(
            metadataFile.takeIf { it.isFile }?.readText(),
            object : TypeToken<Map<String, DownloadedTrack>>() {}.type
        )
    }.getOrNull().orEmpty()

    private fun saveRecords() {
        metadataFile.writeText(gson.toJson(records.toMap()))
    }

    private fun extensionFor(mimeType: String): String = when {
        mimeType.contains("webm", ignoreCase = true) -> "webm"
        mimeType.contains("mp4", ignoreCase = true) -> "m4a"
        else -> "audio"
    }
}
