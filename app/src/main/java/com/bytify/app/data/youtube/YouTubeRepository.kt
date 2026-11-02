package com.bytify.app.data.youtube

import com.bytify.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

class TrackUnavailableException(message: String) : Exception(message)

/**
 * Katalog & ekstraksi audio dari YouTube (Music) lewat NewPipe Extractor.
 * Tidak ada file audio yang disimpan; URL stream diambil saat diputar.
 */
class YouTubeRepository {
    private val service = ServiceList.YouTube

    private data class CachedStream(val url: String, val expiresAt: Long)

    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val searchCache = ConcurrentHashMap<String, String>()

    /** Kualitas tinggi = bitrate terbesar; hemat data = bitrate terkecil. */
    @Volatile
    var preferHighQuality: Boolean = true
        set(value) {
            if (field != value) streamCache.clear()
            field = value
        }

    /** Cari lagu di YouTube Music (filter: lagu resmi). */
    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val extractor = service.getSearchExtractor(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            "",
        )
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.toSong() }
    }

    /** Baca playlist YouTube / YouTube Music (maks. [limit] lagu). */
    suspend fun playlist(url: String, limit: Int = 500): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(service, url)
        val items = info.relatedItems.toMutableList<InfoItem>()
        var page = info.nextPage
        while (page != null && items.size < limit) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            items += more.items
            page = more.nextPage
        }
        info.name to items.take(limit).mapNotNull { it.toSong() }
    }

    /** Lagu terkait untuk radio / putar otomatis. */
    suspend fun related(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, watchUrl(videoId))
        cacheStream(videoId, info)
        info.relatedItems.mapNotNull { it.toSong() }.filter { it.id != videoId }
    }

    /**
     * Ubah id lagu menjadi URL audio yang bisa diputar. Dipanggil dari thread
     * pemuat ExoPlayer, jadi memang blocking.
     */
    fun resolveStreamUrlBlocking(songId: String): String {
        val videoId = if (songId.startsWith(Song.SEARCH_PREFIX)) {
            resolvePendingBlocking(songId.removePrefix(Song.SEARCH_PREFIX))
        } else {
            songId
        }
        streamCache[videoId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.url }

        val info = StreamInfo.getInfo(service, watchUrl(videoId))
        return cacheStream(videoId, info)
            ?: throw TrackUnavailableException("Stream audio tidak tersedia untuk lagu ini.")
    }

    private fun resolvePendingBlocking(query: String): String {
        searchCache[query]?.let { return it }
        val extractor = service.getSearchExtractor(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            "",
        )
        extractor.fetchPage()
        val first = extractor.initialPage.items.firstNotNullOfOrNull { it.toSong() }
            ?: throw TrackUnavailableException("Lagu \"$query\" tidak ditemukan.")
        searchCache[query] = first.id
        return first.id
    }

    private fun cacheStream(videoId: String, info: StreamInfo): String? {
        val streams = info.audioStreams.filter { it.isUrl && it.content.isNotBlank() }
        val byFormat = compareByDescending<AudioStream> { it.format == MediaFormat.M4A }
        val best = streams
            .sortedWith(
                if (preferHighQuality) {
                    byFormat.thenByDescending { it.averageBitrate }
                } else {
                    byFormat.thenBy { it.averageBitrate }
                },
            )
            .firstOrNull() ?: return null
        streamCache[videoId] = CachedStream(best.content, System.currentTimeMillis() + STREAM_TTL_MS)
        return best.content
    }

    private fun InfoItem.toSong(): Song? {
        if (this !is StreamInfoItem) return null
        val id = runCatching { service.streamLHFactory.getId(url) }.getOrNull() ?: return null
        return Song(
            id = id,
            title = name,
            artist = uploaderName?.removeSuffix(" - Topic").orEmpty(),
            thumbnailUrl = thumbnails.bestUrl(),
            durationSec = duration.takeIf { it > 0 },
        )
    }

    companion object {
        private const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L

        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

        /** Gambar terbesar; thumbnail YouTube Music diperbesar ke 544px. */
        fun List<Image>.bestUrl(): String? {
            val url = maxByOrNull { it.height }?.url ?: return null
            return url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        }
    }
}
