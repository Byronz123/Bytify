package com.bytify.app.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.bytify.app.data.model.Song
import com.bytify.app.playback.BytifyDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/** Status unduhan satu lagu untuk UI. */
enum class DownloadState { Queued, Downloading, Completed, Failed }

/**
 * Unduhan offline permanen. Disimpan di cache terpisah tanpa batas ukuran,
 * dibaca lebih dulu oleh pemutar sehingga lagu terunduh tidak butuh internet.
 */
@OptIn(UnstableApi::class)
class DownloadRepository(
    private val context: Context,
    databaseProvider: DatabaseProvider,
    upstreamFactory: DataSource.Factory,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    val cache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)

    val manager = DownloadManager(
        context,
        databaseProvider,
        cache,
        upstreamFactory,
        Executors.newFixedThreadPool(2),
    ).apply { maxParallelDownloads = 2 }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                _states.value = _states.value + (download.request.id to download.toState())
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                _states.value = _states.value - download.request.id
            }

            override fun onInitialized(manager: DownloadManager) = refreshAll()
        })
        if (manager.isInitialized) refreshAll()
    }

    private fun refreshAll() {
        val map = mutableMapOf<String, DownloadState>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                map[download.request.id] = download.toState()
            }
        }
        _states.value = map
    }

    private fun Download.toState() = when (state) {
        Download.STATE_COMPLETED -> DownloadState.Completed
        Download.STATE_DOWNLOADING -> DownloadState.Downloading
        Download.STATE_FAILED -> DownloadState.Failed
        else -> DownloadState.Queued
    }

    /** Lagu yang selesai diunduh, terbaru dulu sesuai urutan unduh. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadedSongs: Flow<List<Song>> = states
        .map { map -> map.filterValues { it == DownloadState.Completed }.keys.toList() }
        .flatMapLatest { ids -> library.songsByIds(ids) }

    fun isDownloaded(songId: String) = _states.value[songId] == DownloadState.Completed

    fun download(song: Song) {
        scope.launch { library.saveSong(song) }
        val request = DownloadRequest.Builder(song.id, Song.uriFor(song.id))
            .setCustomCacheKey(song.id)
            .setData(song.title.toByteArray())
            .build()
        DownloadService.sendAddDownload(context, BytifyDownloadService::class.java, request, false)
    }

    fun downloadAll(songs: List<Song>) = songs.filter { _states.value[it.id] != DownloadState.Completed }.forEach(::download)

    fun remove(songId: String) {
        DownloadService.sendRemoveDownload(context, BytifyDownloadService::class.java, songId, false)
    }

    fun removeAll() {
        DownloadService.sendRemoveAllDownloads(context, BytifyDownloadService::class.java, false)
    }
}
