package com.bytify.app.data.remote

import com.bytify.app.data.model.LyricLine
import com.bytify.app.data.model.Lyrics
import com.bytify.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Lirik dari LrcLib (gratis): utamakan lirik tersinkron (format LRC). */
class LyricsRepository(private val client: OkHttpClient, private val json: Json) {

    @Serializable
    private data class LrcLibItem(
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        val instrumental: Boolean = false,
    )

    private val cache = ConcurrentHashMap<String, Lyrics>()

    suspend fun lyricsFor(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        cache[song.id]?.let { return@withContext it }
        val title = cleanTitle(song.title)
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", song.artist)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "Bytify/1.0 (Android)").build()
        val items = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                json.decodeFromString<List<LrcLibItem>>(response.body!!.string())
            }
        }.getOrDefault(emptyList())

        val best = items
            .filter { !it.instrumental && (it.syncedLyrics != null || it.plainLyrics != null) }
            .sortedWith(
                compareByDescending<LrcLibItem> { it.syncedLyrics != null }
                    .thenBy { item ->
                        val d = song.durationSec ?: return@thenBy 0.0
                        kotlin.math.abs((item.duration ?: 0.0) - d)
                    },
            )
            .firstOrNull() ?: return@withContext null

        val lyrics = best.syncedLyrics?.let { parseLrc(it) }?.takeIf { it.isNotEmpty() }
            ?.let { Lyrics(it, synced = true, source = "LrcLib") }
            ?: best.plainLyrics?.lines()?.map { LyricLine(null, it) }
                ?.let { Lyrics(it, synced = false, source = "LrcLib") }
        lyrics?.also { cache[song.id] = it }
    }

    companion object {
        private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

        fun parseLrc(lrc: String): List<LyricLine> = lrc.lines().flatMap { line ->
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) return@flatMap emptyList()
            val text = line.substring(tags.last().range.last + 1).trim()
            tags.map { match ->
                val (m, s, f) = match.destructured
                val fraction = f.padEnd(3, '0').take(3).toLongOrNull() ?: 0
                LyricLine(m.toLong() * 60_000 + s.toLong() * 1_000 + fraction, text)
            }
        }.sortedBy { it.timeMs }

        /** Buang embel-embel seperti "(Official Video)" agar pencarian lirik tepat. */
        fun cleanTitle(title: String): String = title
            .replace(Regex("""\s*[(\[](official|lyric|lirik|audio|video|mv|m/v|visualizer)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
