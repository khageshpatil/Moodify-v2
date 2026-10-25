package com.example.myapplication.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException

/**
 * Reassembles a finite media request from bounded HTTP ranges.
 *
 * YouTube's CDN can accept a small byte range while rejecting a single
 * full-file range for the same signed URL. This class keeps the container
 * stream intact for ExoPlayer and only changes the HTTP transport shape.
 */
internal class ChunkedHttpDataSource(
    private val upstream: DataSource,
    private val chunkLength: Long,
) : DataSource {
    private var request: DataSpec? = null
    private var bytesRead = 0L
    private var chunkBytesRemaining = C.LENGTH_UNSET.toLong()
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(request == null) { "DataSource is already open" }
        request = dataSpec
        bytesRead = 0L
        val openedLength = openNextChunk(dataSpec)
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) openedLength else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val dataSpec = checkNotNull(request) { "DataSource is not open" }
        if (dataSpec.length != C.LENGTH_UNSET.toLong() && bytesRead >= dataSpec.length) {
            return C.RESULT_END_OF_INPUT
        }

        if (chunkBytesRemaining == 0L) {
            closeUpstream()
            openNextChunk(dataSpec.subrange(bytesRead))
        }

        val readLength = if (chunkBytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), chunkBytesRemaining).toInt()
        }

        val count = upstream.read(buffer, offset, readLength)
        if (count == C.RESULT_END_OF_INPUT) {
            if (chunkBytesRemaining > 0L) {
                throw EOFException("Audio range ended before its requested bytes were read")
            }
            return count
        }

        bytesRead += count
        if (chunkBytesRemaining != C.LENGTH_UNSET.toLong()) {
            chunkBytesRemaining -= count
        }
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        request = null
        bytesRead = 0L
        chunkBytesRemaining = C.LENGTH_UNSET.toLong()
        closeUpstream()
    }

    private fun openNextChunk(dataSpec: DataSpec): Long {
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            null
        } else {
            minOf(dataSpec.length, chunkLength)
        }
        val chunkSpec = length?.let { dataSpec.subrange(0L, it) } ?: dataSpec
        chunkBytesRemaining = chunkSpec.length
        upstreamOpen = true
        return upstream.open(chunkSpec)
    }

    private fun closeUpstream() {
        if (!upstreamOpen) return
        upstreamOpen = false
        upstream.close()
    }
}
