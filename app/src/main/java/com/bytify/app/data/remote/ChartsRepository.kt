package com.bytify.app.data.remote

import com.bytify.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Tangga lagu dianggap usang setelah 3 jam — kadens sama dengan katalog film. */
const val USIA_TANGGA_DEFAULT_MS = 3L * 60 * 60 * 1000

/**
 * Apple Music RSS membatasi umpan most-played maksimum 100 entri per negara
 * (101+ mengembalikan HTTP 500). Jadi inilah jumlah maksimum dari sumbernya.
 */
const val JUMLAH_TANGGA_MAKS = 100

/**
 * Album terpopuler dari tangga Apple Music. [query] dipakai saat kartu diketuk
 * untuk mencari lagu-lagu album itu lewat YouTube.
 */
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val artworkUrl: String?,
    val query: String,
)

/**
 * Tangga lagu terkini dari Apple Music RSS (gratis, tanpa kunci API).
 * Hasilnya lagu "pending" yang dicari ke YouTube saat diputar.
 */
class ChartsRepository(private val client: OkHttpClient, private val json: Json) {

    @Serializable
    private data class Feed(val feed: FeedBody)

    @Serializable
    private data class FeedBody(val results: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val name: String, val artistName: String, val artworkUrl100: String? = null)

    @Serializable
    private data class AlbumFeed(val feed: AlbumBody)

    @Serializable
    private data class AlbumBody(val results: List<AlbumEntry> = emptyList())

    @Serializable
    private data class AlbumEntry(
        val id: String,
        val name: String,
        val artistName: String,
        val artworkUrl100: String? = null,
    )

    private data class Tersimpan(val padaMs: Long, val lagu: List<Song>)
    private data class TersimpanAlbum(val padaMs: Long, val album: List<Album>)

    private val cache = ConcurrentHashMap<String, Tersimpan>()
    private val albumCache = ConcurrentHashMap<String, TersimpanAlbum>()

    private fun besarkanArtwork(url: String?) = url?.replace(Regex("\\d+x\\d+bb"), "600x600bb")

    /**
     * Ambil tangga lagu, utuh dari cache bila masih segar (< [USIA_TANGGA_DEFAULT_MS]),
     * jika tidak unduh ulang. Pola yang sama dengan refreshIfStale pada katalog film:
     * data terkini muncul otomatis tanpa memaksa unduh tiap kali diakses.
     */
    suspend fun topSongs(country: String, limit: Int = JUMLAH_TANGGA_MAKS): List<Song> =
        ambilUmpan(country, limit, "songs")

    /**
     * Tangga video musik. Isinya lagu juga (judul + artis), tapi sebagian besar
     * berbeda dari umpan `songs`, sehingga melipatgandakan jangkauan katalog.
     */
    suspend fun topMusicVideos(country: String, limit: Int = JUMLAH_TANGGA_MAKS): List<Song> =
        ambilUmpan(country, limit, "music-videos")

    private suspend fun ambilUmpan(country: String, limit: Int, jenis: String): List<Song> = withContext(Dispatchers.IO) {
        val key = "$country:$limit:$jenis"
        cache[key]?.let {
            if (System.currentTimeMillis() - it.padaMs < USIA_TANGGA_DEFAULT_MS) return@withContext it.lagu
        }
        val url = "https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/$limit/$jenis.json"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Tangga lagu gagal dimuat (${response.code})")
            val feed = json.decodeFromString<Feed>(response.body!!.string())
            feed.feed.results.map {
                Song.pending(
                    title = it.name,
                    artist = it.artistName,
                    thumbnailUrl = besarkanArtwork(it.artworkUrl100),
                )
            }.also { cache[key] = Tersimpan(System.currentTimeMillis(), it) }
        }
    }

    /** Album terpopuler, cache ber-TTL sama seperti [topSongs]. */
    suspend fun topAlbums(country: String, limit: Int = JUMLAH_TANGGA_MAKS): List<Album> = withContext(Dispatchers.IO) {
        val key = "$country:$limit"
        albumCache[key]?.let {
            if (System.currentTimeMillis() - it.padaMs < USIA_TANGGA_DEFAULT_MS) return@withContext it.album
        }
        val url = "https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/$limit/albums.json"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Album gagal dimuat (${response.code})")
            val feed = json.decodeFromString<AlbumFeed>(response.body!!.string())
            feed.feed.results.map {
                Album(
                    id = it.id,
                    name = it.name,
                    artist = it.artistName,
                    artworkUrl = besarkanArtwork(it.artworkUrl100),
                    query = "${it.name} ${it.artistName}",
                )
            }.also { albumCache[key] = TersimpanAlbum(System.currentTimeMillis(), it) }
        }
    }

    fun clearCache() {
        cache.clear()
        albumCache.clear()
    }
}
