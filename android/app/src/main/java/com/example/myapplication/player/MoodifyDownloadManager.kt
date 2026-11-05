package com.moodify.app.player

import android.content.Context
import com.moodify.app.data.DownloadEntity
import com.moodify.app.data.MoodifyDatabase
import com.moodify.app.data.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import com.moodify.app.data.OnceGate
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
    private val dao = MoodifyDatabase.get(appContext).moodifyDao()
    private val gson = Gson()
    private val records = ConcurrentHashMap<String, DownloadedTrack>()
    private val recordsLoaded = OnceGate()

    /** Loads download records from Room (and the legacy JSON file) once, off the main thread. */
    suspend fun ensureLoaded() = recordsLoaded.run {
        withContext(Dispatchers.IO) { records.putAll(loadRecords()) }
    }

    suspend fun get(id: String): DownloadedTrack? = withContext(Dispatchers.IO) {
        ensureLoaded()
        records[id]?.takeIf { File(it.filePath).isFile }
    }

    suspend fun all(): List<DownloadedTrack> = withContext(Dispatchers.IO) {
        ensureLoaded()
        records.values
            .filter { File(it.filePath).isFile }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun download(
        track: Track,
        resolvedStream: InnerTubeStreamResolver.ResolvedStream? = null,
        onProgress: (Int) -> Unit = {},
    ): DownloadedTrack = withContext(Dispatchers.IO) {
        get(track.id)?.let { return@withContext it }

        val stream = resolvedStream ?: resolver.resolve(track.id)
        val temporaryFile = File(downloadDirectory, "${track.id}.part")
        val targetFile = File(downloadDirectory, "${track.id}.${extensionFor(stream.mimeType)}")
        val resumeAt = temporaryFile.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
        val knownLength = stream.contentLength?.takeIf { it > 0L }
        if (knownLength != null) {
            downloadInChunks(stream, temporaryFile, resumeAt.coerceAtMost(knownLength), knownLength, onProgress)
        } else {
            downloadInOneRequest(stream, temporaryFile, resumeAt, onProgress)
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
        saveRecord(record)
        record
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        ensureLoaded()
        records.remove(id)?.let { File(it.filePath).delete() }
        File(downloadDirectory, "$id.part").delete()
        dao.deleteDownload(id)
    }

    private suspend fun loadRecords(): Map<String, DownloadedTrack> {
        val roomRecords = dao.downloads().associate { entity ->
            entity.trackId to DownloadedTrack(
                id = entity.trackId,
                title = entity.title,
                artist = entity.artist,
                    album = entity.album,
                    albumArtUrl = entity.albumArtUrl,
                    durationSeconds = entity.durationSeconds,
                filePath = entity.filePath,
                mimeType = entity.mimeType,
            )
        }
        if (roomRecords.isNotEmpty()) return roomRecords

        val legacy = runCatching {
            gson.fromJson<Map<String, DownloadedTrack>>(
                metadataFile.takeIf { it.isFile }?.readText(),
                object : TypeToken<Map<String, DownloadedTrack>>() {}.type,
            )
        }.getOrNull().orEmpty()
        legacy.values.forEach { record ->
            dao.upsertDownload(
                DownloadEntity(
                    trackId = record.id,
                    title = record.title,
                    artist = record.artist,
                    album = record.album,
                    albumArtUrl = record.albumArtUrl,
                    durationSeconds = record.durationSeconds,
                    filePath = record.filePath,
                    mimeType = record.mimeType,
                    bytes = File(record.filePath).takeIf { it.isFile }?.length() ?: 0L,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
        if (legacy.isNotEmpty()) metadataFile.delete()
        return legacy
    }

    private suspend fun saveRecord(record: DownloadedTrack) {
        withContext(Dispatchers.IO) {
            dao.upsertDownload(
                DownloadEntity(
                    trackId = record.id,
                    title = record.title,
                    artist = record.artist,
                    album = record.album,
                    albumArtUrl = record.albumArtUrl,
                    durationSeconds = record.durationSeconds,
                    filePath = record.filePath,
                    mimeType = record.mimeType,
                    bytes = File(record.filePath).takeIf { it.isFile }?.length() ?: 0L,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * YouTube throttles one long open-ended request to roughly playback
     * speed. Bounded range requests are served at full speed, so a known-size
     * file is fetched in [CHUNK_BYTES] pieces (resuming from [startAt]).
     */
    private fun downloadInChunks(
        stream: InnerTubeStreamResolver.ResolvedStream,
        temporaryFile: File,
        startAt: Long,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        var position = startAt
        var lastReportedProgress = -1
        FileOutputStream(temporaryFile, startAt > 0L).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (position < totalBytes) {
                val end = minOf(position + CHUNK_BYTES, totalBytes) - 1
                val request = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", stream.userAgent)
                    .header("Range", "bytes=$position-$end")
                    .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code != 206 && !(response.code == 200 && position == 0L)) {
                        throw IOException("Download failed with HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Download returned no body")
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            position += read
                            val progress = (position * 100L / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }
        onProgress(100)
    }

    /** Fallback when the size is unknown: one request, resuming a partial file if possible. */
    private fun downloadInOneRequest(
        stream: InnerTubeStreamResolver.ResolvedStream,
        temporaryFile: File,
        resumeAt: Long,
        onProgress: (Int) -> Unit,
    ) {
        val request = Request.Builder()
            .url(stream.url)
            .header("User-Agent", stream.userAgent)
            .apply { if (resumeAt > 0L) header("Range", "bytes=$resumeAt-") }
            .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed with HTTP ${response.code}")
            val body = response.body ?: throw IOException("Download returned no body")
            val append = resumeAt > 0L && response.code == 206
            if (!append && resumeAt > 0L) temporaryFile.delete()
            val startBytes = if (append) resumeAt else 0L
            val totalBytes = body.contentLength().takeIf { it > 0L }?.let { startBytes + it }
            body.byteStream().use { input ->
                FileOutputStream(temporaryFile, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var completedBytes = startBytes
                    var lastReportedProgress = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        completedBytes += read
                        if (totalBytes != null) {
                            val progress = (completedBytes * 100L / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            if (totalBytes != null) onProgress(100)
        }
    }

    private fun extensionFor(mimeType: String): String = when {
        mimeType.contains("webm", ignoreCase = true) -> "webm"
        mimeType.contains("mp4", ignoreCase = true) -> "m4a"
        else -> "audio"
    }

    private companion object {
        /** Small enough to dodge YouTube's throttle, large enough to keep overhead low. */
        const val CHUNK_BYTES = 1L shl 20 // 1 MiB
    }
}
