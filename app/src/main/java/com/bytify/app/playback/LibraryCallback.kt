package com.bytify.app.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.bytify.app.AppContainer
import com.bytify.app.data.model.Song
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import java.util.concurrent.ConcurrentHashMap

/**
 * Pohon penjelajahan untuk Android Auto / perangkat media lain, plus
 * pembangunan ulang URI item yang dikirim controller.
 */
@OptIn(UnstableApi::class)
class LibraryCallback(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val ownPackage: String,
) : MediaLibrarySession.Callback {

    /** Daftar terakhir yang dijelajahi, untuk memutar satu lagu beserta daftarnya. */
    private val browsedLists = ConcurrentHashMap<String, List<Song>>()
    private val searchResults = ConcurrentHashMap<String, List<Song>>()

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future { LibraryResult.ofItem(folder(ROOT, "Bytify"), params) }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val items: List<MediaItem> = when (parentId) {
            ROOT -> listOf(
                folder(LIKED, "Lagu yang disukai"),
                folder(RECENT, "Baru diputar"),
                folder(DOWNLOADED, "Diunduh"),
                folder(PLAYLISTS, "Playlist"),
            )
            PLAYLISTS -> container.library.playlists.first().map { folder("playlist:${it.id}", it.name) }
            else -> {
                val songs = container.songsForSource(parentId)
                browsedLists[parentId] = songs
                songs.map { it.toBrowsableItem() }
            }
        }
        LibraryResult.ofItemList(items.page(page, pageSize), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
        val song = findSong(mediaId)
        if (song != null) LibraryResult.ofItem(song.toBrowsableItem(), null)
        else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
        val results = runCatching { container.youtube.searchSongs(query) }.getOrDefault(emptyList())
        searchResults[query] = results
        browsedLists["search:$query"] = results
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val results = searchResults[query].orEmpty().map { it.toBrowsableItem() }
        LibraryResult.ofItemList(results.page(page, pageSize), params)
    }

    /** Controller tidak boleh mengirim URI, jadi URI & metadata dibangun ulang di sini. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future(Dispatchers.IO) { resolve(mediaItems) }

    private suspend fun resolve(mediaItems: List<MediaItem>): MutableList<MediaItem> = mediaItems.map { item ->
        val fromLibrary = if (item.mediaMetadata.title == null) findSong(item.mediaId)?.toMediaItem() else null
        (fromLibrary ?: item).buildUpon()
            .setUri(Song.uriFor(item.mediaId))
            .setCustomCacheKey(item.mediaId)
            .build()
    }.toMutableList()

    /** Dari Android Auto: memilih satu lagu memutar seluruh daftar tempat lagu itu berada. */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        val single = mediaItems.singleOrNull()
        val list = if (single != null && controller.packageName != ownPackage) {
            browsedLists.values.firstOrNull { songs -> songs.any { it.id == single.mediaId } }
        } else null
        if (list != null) {
            val index = list.indexOfFirst { it.id == single!!.mediaId }
            MediaSession.MediaItemsWithStartPosition(
                list.map { it.toMediaItem() }, index, startPositionMs,
            )
        } else {
            MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
        }
    }

    private suspend fun findSong(id: String): Song? =
        browsedLists.values.asSequence().flatten().firstOrNull { it.id == id }
            ?: container.library.songsByIds(listOf(id)).first().firstOrNull()

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                .build(),
        )
        .build()

    private fun Song.toBrowsableItem() = MediaItem.Builder()
        .setMediaId(id)
        .setUri(Song.uriFor(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun <T> List<T>.page(page: Int, pageSize: Int): ImmutableList<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(this)
        val from = (page * pageSize).coerceAtMost(size)
        return ImmutableList.copyOf(subList(from, (from + pageSize).coerceAtMost(size)))
    }

    companion object {
        private const val ROOT = "root"
        private const val LIKED = "liked"
        private const val RECENT = "recent"
        private const val DOWNLOADED = "downloaded"
        private const val PLAYLISTS = "playlists"
    }
}
