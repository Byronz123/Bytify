package com.bytify.app.data.remote

import com.bytify.app.data.LibraryRepository
import com.bytify.app.data.model.Song
import com.bytify.app.data.youtube.YouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class ImportException(message: String) : Exception(message)

/**
 * Impor playlist dari link publik YouTube / YouTube Music, Spotify, atau
 * Apple Music. Lagu dari Spotify & Apple dicocokkan satu per satu ke YouTube Music.
 */
class ImportRepository(
    private val client: OkHttpClient,
    private val json: Json,
    private val youtube: YouTubeRepository,
    private val library: LibraryRepository,
) {
    enum class Phase { Reading, Matching, Saving }

    data class Progress(val phase: Phase, val found: Int = 0, val checked: Int = 0, val total: Int = 0)

    data class Result(
        val playlistId: Long,
        val name: String,
        val added: Int,
        val unmatched: List<String>,
        val truncatedNote: String?,
    )

    private data class SourceTrack(val title: String, val artist: String, val durationSec: Long?)

    private data class SourcePlaylist(
        val name: String,
        val tracks: List<SourceTrack> = emptyList(),
        val songs: List<Song>? = null,
        val truncatedNote: String? = null,
    )

    suspend fun import(link: String, onProgress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        onProgress(Progress(Phase.Reading))
        val source = read(link.trim())

        val (songs, unmatched) = source.songs?.let { it to emptyList() } ?: match(source.tracks, onProgress)
        if (songs.isEmpty()) throw ImportException("Tidak ada lagu yang ditemukan di YouTube Music.")

        onProgress(Progress(Phase.Saving, songs.size, songs.size, songs.size))
        val playlistId = library.createPlaylist(source.name.ifBlank { "Playlist impor" })
        songs.forEach { library.addToPlaylist(playlistId, it) }
        Result(playlistId, source.name, songs.size, unmatched, source.truncatedNote)
    }

    private suspend fun read(url: String): SourcePlaylist = when {
        ("youtube.com" in url || "youtu.be" in url) && "list=" in url -> {
            val (name, songs) = runCatching { youtube.playlist(url) }
                .getOrElse { throw ImportException("Playlist YouTube tidak bisa dibaca. Pastikan playlist bersifat publik.") }
            if (songs.isEmpty()) throw ImportException("Playlist ini kosong.")
            SourcePlaylist(name, songs = songs)
        }
        SPOTIFY.containsMatchIn(url) -> readSpotify(url)
        "music.apple.com" in url -> readApple(url)
        else -> throw ImportException("Link tidak dikenali. Gunakan link playlist/album publik dari YouTube, Spotify, atau Apple Music.")
    }

    private fun readSpotify(url: String): SourcePlaylist {
        val (kind, id) = SPOTIFY.find(url)!!.destructured
        val html = fetch("https://open.spotify.com/embed/$kind/$id")
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw ImportException("Playlist Spotify tidak bisa dibaca.")
        val entity = json.parseToJsonElement(data).path("props", "pageProps", "state", "data", "entity") as? JsonObject
            ?: throw ImportException("Playlist Spotify tidak bisa dibaca.")
        val tracks = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj.string("title") ?: return@mapNotNull null
            val artist = obj.string("subtitle").orEmpty().split(",").first().replace(' ', ' ').trim()
            SourceTrack(title, artist, obj["duration"]?.jsonPrimitive?.longOrNull?.div(1000))
        }
        if (tracks.isEmpty()) throw ImportException("Playlist Spotify ini kosong.")
        return SourcePlaylist(
            name = entity.string("name") ?: entity.string("title").orEmpty(),
            tracks = tracks,
            truncatedNote = if (kind == "playlist" && tracks.size >= 100) {
                "Tanpa login, Spotify hanya membagikan 100 lagu pertama dari playlist."
            } else null,
        )
    }

    private fun readApple(url: String): SourcePlaylist {
        val html = fetch(url)
        val data = Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw ImportException("Playlist Apple Music tidak bisa dibaca.")
        val root = json.parseToJsonElement(data)
        val sections = (root.path("data") as? JsonArray)?.firstOrNull()?.path("data", "sections") as? JsonArray
            ?: throw ImportException("Playlist Apple Music tidak bisa dibaca.")
        var name = ""
        val tracks = mutableListOf<SourceTrack>()
        sections.forEach { section ->
            val obj = section as? JsonObject ?: return@forEach
            val items = (obj["items"] as? JsonArray).orEmpty()
            when (obj.string("itemKind")) {
                "containerDetailHeaderLockup" -> name = (items.firstOrNull() as? JsonObject)?.string("title").orEmpty()
                "trackLockup" -> items.forEach { item ->
                    val track = item as? JsonObject ?: return@forEach
                    val title = track.string("title") ?: return@forEach
                    tracks += SourceTrack(
                        title,
                        track.string("artistName").orEmpty(),
                        track["duration"]?.jsonPrimitive?.longOrNull?.div(1000),
                    )
                }
            }
        }
        if (tracks.isEmpty()) throw ImportException("Playlist Apple Music ini kosong.")
        return SourcePlaylist(name, tracks)
    }

    /** Cocokkan tiap lagu ke YouTube Music (4 pencarian paralel, urutan tetap). */
    private suspend fun match(tracks: List<SourceTrack>, onProgress: (Progress) -> Unit): Pair<List<Song>, List<String>> =
        coroutineScope {
            val gate = Semaphore(4)
            val checked = AtomicInteger(0)
            val found = AtomicInteger(0)
            onProgress(Progress(Phase.Matching, 0, 0, tracks.size))
            val results = tracks.map { track ->
                async {
                    gate.withPermit {
                        val song = runCatching { bestMatch(track) }.getOrNull()
                        if (song != null) found.incrementAndGet()
                        onProgress(Progress(Phase.Matching, found.get(), checked.incrementAndGet(), tracks.size))
                        song
                    }
                }
            }.awaitAll()
            val songs = results.filterNotNull().distinctBy { it.id }
            val unmatched = tracks.zip(results).filter { it.second == null }.map { "${it.first.title} – ${it.first.artist}" }
            songs to unmatched
        }

    private suspend fun bestMatch(track: SourceTrack): Song? {
        val candidates = youtube.searchSongs("${track.title} ${track.artist}").take(5)
        if (candidates.isEmpty()) return null
        val duration = track.durationSec ?: return candidates.first()
        return candidates.firstOrNull { c -> c.durationSec?.let { abs(it - duration) <= 15 } == true }
            ?: candidates.first()
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).header("Accept-Language", "id,en;q=0.8").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ImportException("Link tidak bisa dibuka (${response.code}). Pastikan playlist bersifat publik.")
            return response.body!!.string()
        }
    }

    private fun JsonElement.path(vararg keys: String): JsonElement? =
        keys.fold(this as JsonElement?) { acc, key -> (acc as? JsonObject)?.get(key) }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private val SPOTIFY = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(playlist|album)/([A-Za-z0-9]+)""")
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
