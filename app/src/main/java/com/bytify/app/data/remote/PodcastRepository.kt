package com.bytify.app.data.remote

import android.util.Xml
import com.bytify.app.BuildConfig
import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.EpisodeProgressEntity
import com.bytify.app.data.db.PodcastSubscriptionEntity
import com.bytify.app.data.model.MediaKind
import com.bytify.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Podcast(
    val id: Long,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val feedUrl: String?,
    val genre: String? = null,
    val episodeCount: Int? = null,
) {
    fun toEntity(feed: String) = PodcastSubscriptionEntity(id, title, author, artworkUrl, feed)

    companion object {
        fun from(entity: PodcastSubscriptionEntity) =
            Podcast(entity.id, entity.title, entity.author, entity.artworkUrl, entity.feedUrl)
    }
}

data class Episode(
    val id: String,
    val title: String,
    val description: String,
    val publishedAt: Long,
    val durationSec: Long?,
    val audioUrl: String,
    val imageUrl: String?,
    val podcastId: Long,
    val podcastTitle: String,
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artist = podcastTitle,
        thumbnailUrl = imageUrl,
        durationSec = durationSec,
        streamUrl = audioUrl,
        kind = MediaKind.EPISODE,
    )
}

data class PodcastFeed(val podcast: Podcast, val description: String, val episodes: List<Episode>)

/**
 * Podcast: katalog & tangga dari Apple (gratis, tanpa kunci), daftar episode
 * dibaca langsung dari RSS feed resmi tiap acara.
 */
class PodcastRepository(
    private val client: OkHttpClient,
    private val json: Json,
    private val dao: BytifyDao,
) {
    @Serializable
    private data class SearchResponse(val results: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val collectionId: Long = 0,
        val collectionName: String = "",
        val artistName: String = "",
        val artworkUrl600: String? = null,
        val artworkUrl100: String? = null,
        val feedUrl: String? = null,
        val primaryGenreName: String? = null,
        val trackCount: Int? = null,
        val kind: String? = null,
        val wrapperType: String? = null,
    )

    @Serializable
    private data class ChartFeed(val feed: ChartBody)

    @Serializable
    private data class ChartBody(val results: List<ChartItem> = emptyList())

    @Serializable
    private data class ChartItem(val id: String)

    private val podcastCache = ConcurrentHashMap<String, List<Podcast>>()
    private val feedCache = ConcurrentHashMap<String, PodcastFeed>()

    val subscriptions: Flow<List<Podcast>> = dao.podcastSubscriptions().map { list -> list.map(Podcast::from) }
    val progress: Flow<Map<String, EpisodeProgressEntity>> =
        dao.allEpisodeProgress().map { list -> list.associateBy { it.episodeId } }

    /** Podcast teratas di [country] (urutan tangga Apple). */
    suspend fun top(country: String = "id", limit: Int = 30): List<Podcast> = withContext(Dispatchers.IO) {
        podcastCache["top:$country"]?.let { return@withContext it }
        val ids = get("https://rss.marketingtools.apple.com/api/v2/$country/podcasts/top/$limit/podcasts.json") {
            json.decodeFromString<ChartFeed>(it).feed.results.map { r -> r.id }
        }
        if (ids.isEmpty()) return@withContext emptyList()
        val byId = lookup(ids, country).associateBy { it.id.toString() }
        ids.mapNotNull { byId[it] }.also { podcastCache["top:$country"] = it }
    }

    suspend fun search(term: String, country: String = "id"): List<Podcast> = withContext(Dispatchers.IO) {
        val q = term.trim()
        if (q.isEmpty()) return@withContext emptyList()
        podcastCache["search:$country:${q.lowercase()}"]?.let { return@withContext it }
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", q)
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("limit", "40")
            .addQueryParameter("country", country)
            .build().toString()
        get(url) { body -> json.decodeFromString<SearchResponse>(body).results.mapNotNull { it.toPodcast() } }
            .also { podcastCache["search:$country:${q.lowercase()}"] = it }
    }

    suspend fun podcast(id: Long, country: String = "id"): Podcast? =
        withContext(Dispatchers.IO) { lookup(listOf(id.toString()), country).firstOrNull() }

    private fun lookup(ids: List<String>, country: String): List<Podcast> {
        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", ids.joinToString(","))
            .addQueryParameter("country", country)
            .addQueryParameter("entity", "podcast")
            .build().toString()
        return get(url) { body -> json.decodeFromString<SearchResponse>(body).results.mapNotNull { it.toPodcast() } }
    }

    private fun Item.toPodcast(): Podcast? {
        if (collectionId == 0L || collectionName.isBlank()) return null
        if (wrapperType != null && wrapperType != "track" && kind != "podcast") return null
        return Podcast(
            id = collectionId,
            title = collectionName,
            author = artistName,
            artworkUrl = artworkUrl600 ?: artworkUrl100,
            feedUrl = feedUrl,
            genre = primaryGenreName,
            episodeCount = trackCount,
        )
    }

    /** Baca RSS feed: daftar episode terbaru dulu. */
    suspend fun feed(podcast: Podcast, refresh: Boolean = false): PodcastFeed = withContext(Dispatchers.IO) {
        val feedUrl = podcast.feedUrl ?: error("Podcast ini tidak menyediakan RSS feed.")
        if (!refresh) feedCache[feedUrl]?.let { return@withContext it }
        val request = Request.Builder().url(feedUrl).header("User-Agent", USER_AGENT).build()
        val parsed = retrying {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Feed podcast gagal dimuat (${response.code})")
                response.body!!.byteStream().use { parseRss(it, podcast) }
            }
        }
        parsed.also { feedCache[feedUrl] = it }
    }

    /** Episode terbaru dari semua langganan, digabung & diurutkan. */
    suspend fun latestFromSubscriptions(subs: List<Podcast>, perPodcast: Int = 3): List<Episode> = coroutineScope {
        subs.map { sub -> async { runCatching { feed(sub).episodes.take(perPodcast) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .sortedByDescending { it.publishedAt }
    }

    suspend fun subscribe(podcast: Podcast) {
        val feed = podcast.feedUrl ?: return
        dao.upsertPodcastSubscription(podcast.toEntity(feed))
    }

    suspend fun unsubscribe(id: Long) = dao.deletePodcastSubscription(id)

    suspend fun markOpened(id: Long) = dao.markPodcastOpened(id, System.currentTimeMillis())

    suspend fun progressOf(episodeId: String) = dao.episodeProgress(episodeId)

    suspend fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long) =
        dao.upsertEpisodeProgress(EpisodeProgressEntity(episodeId, positionMs, durationMs))

    /** GET dengan 2x percobaan ulang untuk gangguan jaringan sesaat. */
    private fun <T> get(url: String, parse: (String) -> T): T = retrying {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Podcast gagal dimuat (${response.code})")
            parse(response.body!!.string())
        }
    }

    private fun <T> retrying(block: () -> T): T {
        var last: Exception? = null
        repeat(RETRIES) { attempt ->
            try {
                return block()
            } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(400L * (attempt + 1))
            }
        }
        throw last!!
    }

    private fun parseRss(input: java.io.InputStream, podcast: Podcast): PodcastFeed {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        var channelDescription = ""
        var channelImage: String? = podcast.artworkUrl
        val episodes = mutableListOf<Episode>()

        var inItem = false
        var title = ""
        var description = ""
        var pubDate = ""
        var duration: String? = null
        var guid: String? = null
        var audio: String? = null
        var image: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""; description = ""; pubDate = ""; duration = null; guid = null; audio = null; image = null
                    }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "description", "itunes:summary" -> {
                        val text = parser.nextText().trim()
                        if (inItem) { if (description.isBlank()) description = text }
                        else if (channelDescription.isBlank()) channelDescription = text
                    }
                    "pubDate" -> if (inItem) pubDate = parser.nextText().trim()
                    "itunes:duration" -> if (inItem) duration = parser.nextText().trim()
                    "guid" -> if (inItem) guid = parser.nextText().trim()
                    "enclosure" -> if (inItem) {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && (type.isBlank() || type.startsWith("audio") || type.contains("mpeg"))) audio = url
                    }
                    "itunes:image" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (inItem) image = href else if (!href.isNullOrBlank()) channelImage = href
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "item") {
                inItem = false
                val url = audio
                if (url != null && title.isNotBlank()) {
                    episodes += Episode(
                        id = "ep:" + hash(guid ?: url),
                        title = title,
                        description = cleanHtml(description),
                        publishedAt = parseDate(pubDate),
                        durationSec = parseDuration(duration),
                        audioUrl = url,
                        imageUrl = image ?: channelImage,
                        podcastId = podcast.id,
                        podcastTitle = podcast.title,
                    )
                }
            }
            event = parser.next()
        }
        return PodcastFeed(
            podcast = podcast.copy(artworkUrl = podcast.artworkUrl ?: channelImage),
            description = cleanHtml(channelDescription),
            episodes = episodes.sortedByDescending { it.publishedAt },
        )
    }

    fun clearCache() {
        podcastCache.clear()
        feedCache.clear()
    }

    companion object {
        private const val RETRIES = 3
        private val USER_AGENT = "Bytify/${BuildConfig.VERSION_NAME} (Android; podcast)"
        private val DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss Z",
        )

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

        fun parseDate(text: String): Long {
            for (pattern in DATE_FORMATS) {
                runCatching { return SimpleDateFormat(pattern, Locale.US).parse(text)!!.time }
            }
            return 0
        }

        /** "1:02:03", "23:06", atau detik saja. */
        fun parseDuration(text: String?): Long? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val parts = t.split(':').map { it.toLongOrNull() ?: return null }
            return parts.fold(0L) { acc, part -> acc * 60 + part }.takeIf { it > 0 }
        }

        fun cleanHtml(text: String): String = text
            .replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
