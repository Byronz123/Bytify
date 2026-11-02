package com.bytify.app.data.youtube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Membaca stream dalam potongan range kecil. Server YouTube memperlambat
 * permintaan satu file utuh hingga sekecepatan putar; potongan kecil
 * diunduh dengan kecepatan penuh.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long,
) : DataSource {
    private var spec: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var ended = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        ended = false
        openChunk()
        return dataSpec.length
    }

    private fun openChunk() {
        val s = spec ?: return
        val length = if (remaining == C.LENGTH_UNSET.toLong()) chunkSize else minOf(chunkSize, remaining)
        upstream.open(s.subrange(position - s.position, length))
        chunkRemaining = length
        chunkOpen = true
    }

    /** @return false jika sudah melewati akhir file (HTTP 416). */
    private fun openNextChunk(): Boolean = try {
        openChunk()
        true
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode == 416) false else throw e
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ended) return C.RESULT_END_OF_INPUT
        while (true) {
            if (chunkRemaining == 0L) {
                closeChunk()
                if (remaining == 0L || !openNextChunk()) {
                    ended = true
                    return C.RESULT_END_OF_INPUT
                }
                continue
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read == C.RESULT_END_OF_INPUT) {
                // Potongan lebih pendek dari yang diminta: berarti akhir file.
                closeChunk()
                ended = true
                return C.RESULT_END_OF_INPUT
            }
            position += read
            chunkRemaining -= read
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            return read
        }
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        spec = null
    }

    companion object {
        const val DEFAULT_CHUNK = 1024L * 1024
    }
}
