package com.bytify.app.data.youtube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Memilih sumber per permintaan: `bytify://` (musik YouTube) lewat [music],
 * alamat langsung (radio, episode podcast) lewat [direct]. Siaran radio tidak
 * punya akhir, jadi tidak boleh masuk cache lagu.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(
    private val music: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        music.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == SCHEME) music else direct
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: error("Sumber belum dibuka")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(private val music: DataSource.Factory, private val direct: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RoutingDataSource(music.createDataSource(), direct.createDataSource())
    }

    companion object {
        const val SCHEME = "bytify"
    }
}
