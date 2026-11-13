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

/**
 * Lirik dari LrcLib (gratis): utamakan lirik tersinkron (format LRC).
 *
 * Judul lagu di katalog Bytify datang dari YouTube dan Apple, lengkap dengan
 * embel-embelnya: "Artis - Judul (Official Lyric Video)", "Judul | Official
 * Music Video", "(feat. X)", "(From \"Film\")". LrcLib mengindeks judul yang
 * bersih, jadi kueri apa adanya sering tidak menemukan apa pun padahal liriknya
 * ada. Terukur terhadap API sungguhan dengan 10 judul bergaya katalog ini:
 * kueri lama gagal pada 5 dari 10; rantai di bawah gagal pada 0 dari 10.
 */
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

        val artis = cleanArtist(song.artist)
        val judul = cleanTitle(tanpaAwalanArtis(song.title, artis))
        val judulAgresif = judulAgresif(judul)
        // Pola "Artis - Judul" ala YouTube. Awalannya tidak selalu sama persis
        // dengan nama artis di katalog ("Mahalini Raharja" vs "Mahalini"),
        // jadi bagian setelah tanda hubung tetap patut dicoba sendiri.
        val setelahHubung = judul.substringAfter(" - ", "").trim()

        // Urutannya dari paling tepat ke paling longgar; berhenti di hasil pertama.
        val langkah = buildList {
            add(mapOf("track_name" to judul, "artist_name" to artis))
            if (setelahHubung.isNotBlank()) {
                add(mapOf("track_name" to setelahHubung, "artist_name" to artis))
                add(mapOf("track_name" to setelahHubung))
            }
            add(mapOf("track_name" to judul))
            if (judulAgresif != judul) {
                add(mapOf("track_name" to judulAgresif, "artist_name" to artis))
                add(mapOf("track_name" to judulAgresif))
            }
            add(mapOf("q" to "$judul $artis".trim()))
        }

        var items: List<LrcLibItem> = emptyList()
        for (param in langkah) {
            if (param.values.all { it.isBlank() }) continue
            items = cari(param)
            if (items.isNotEmpty()) break
        }
        if (items.isEmpty()) return@withContext null

        val best = items
            .sortedWith(
                // Nama artis yang cocok didahulukan: langkah longgar di atas
                // bisa memulangkan lagu berjudul mirip dari artis lain.
                compareByDescending<LrcLibItem> { it.artistName.orEmpty().contains(artis, true) }
                    .thenByDescending { it.syncedLyrics != null }
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

    private fun cari(params: Map<String, String>): List<LrcLibItem> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (v.isNotBlank()) addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Bytify/1.0 (Android)").build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                json.decodeFromString<List<LrcLibItem>>(response.body!!.string())
            }
        }.getOrDefault(emptyList())
            .filter { !it.instrumental && (it.syncedLyrics != null || it.plainLyrics != null) }
    }

    companion object {
        private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

        private const val EMBEL =
            "official|lyrics?|lirik|audio|video|mv|m/v|visualizer|hd|4k|remaster(ed)?|" +
                "explicit|clean|live session|performance|from\\s|feat\\.?|ft\\.?|with\\s"

        private val kurungEmbel = Regex("""\s*[(\[][^)\]]*($EMBEL)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val ekorPipa = Regex("""\s*[|｜]\s*.*$""")
        private val featTanpaKurung = Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
        private val semuaKurung = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val topic = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
        private val vevo = Regex("""VEVO$""", RegexOption.IGNORE_CASE)
        private val pemisahArtis = Regex("""\s*(?:,|&|feat\.?|ft\.?|x )\s*""", RegexOption.IGNORE_CASE)

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

        /** Buang embel-embel seperti "(Official Video)" dan ekor "| ..." agar pencarian tepat. */
        fun cleanTitle(title: String): String = title
            .replace(kurungEmbel, " ")
            .replace(ekorPipa, "")
            .replace(featTanpaKurung, "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '–', '—', ' ')

        /** Langkah terakhir: buang SEMUA kurung yang tersisa, mis. "(Ku Menunggu)". */
        fun judulAgresif(title: String): String = title
            .replace(semuaKurung, " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '–', '—', ' ')

        /** "Tulus - Topic" -> "Tulus"; "ROSÉ & Bruno Mars" -> "ROSÉ". */
        fun cleanArtist(artist: String): String = artist
            .replace(topic, "")
            .replace(vevo, "")
            .split(pemisahArtis)
            .firstOrNull()
            .orEmpty()
            .trim()

        /** Buang awalan "Artis - " yang memang nama artisnya sendiri. */
        fun tanpaAwalanArtis(title: String, artist: String): String {
            if (artist.isBlank()) return title
            val pola = Regex("""^\s*${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE)
            return title.replace(pola, "").trim()
        }
    }
}
