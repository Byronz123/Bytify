package com.bytify.app.data.remote

import com.bytify.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

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

    private val cache = ConcurrentHashMap<String, List<Song>>()

    suspend fun topSongs(country: String, limit: Int = 50): List<Song> = withContext(Dispatchers.IO) {
        val key = "$country:$limit"
        cache[key]?.let { return@withContext it }
        val url = "https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/$limit/songs.json"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Tangga lagu gagal dimuat (${response.code})")
            val feed = json.decodeFromString<Feed>(response.body!!.string())
            feed.feed.results.map {
                Song.pending(
                    title = it.name,
                    artist = it.artistName,
                    thumbnailUrl = it.artworkUrl100?.replace(Regex("\\d+x\\d+bb"), "600x600bb"),
                )
            }.also { cache[key] = it }
        }
    }

    fun clearCache() = cache.clear()
}
