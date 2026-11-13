package com.bytify.app.data.remote

import com.bytify.app.BuildConfig
import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.RadioFavoriteEntity
import com.bytify.app.data.model.MediaKind
import com.bytify.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val favicon: String?,
    val tags: List<String>,
    val country: String,
    val codec: String,
    val bitrate: Int,
    val hls: Boolean,
) {
    fun toSong() = Song(
        id = "radio:$id",
        title = name,
        artist = tags.take(2).joinToString(" • ").ifBlank { "Radio" }.replaceFirstChar { it.uppercase() },
        thumbnailUrl = favicon,
        streamUrl = streamUrl,
        kind = MediaKind.RADIO,
        hls = hls,
    )

    fun toEntity() = RadioFavoriteEntity(id, name, streamUrl, favicon, tags.joinToString(","), country, hls)

    companion object {
        fun from(entity: RadioFavoriteEntity) = RadioStation(
            id = entity.id,
            name = entity.name,
            streamUrl = entity.streamUrl,
            favicon = entity.favicon,
            tags = entity.tags.split(',').filter { it.isNotBlank() },
            country = entity.country,
            codec = "",
            bitrate = 0,
            hls = entity.hls,
        )
    }
}

/** Satu negara di direktori radio, beserta banyak stasiunnya. */
data class NegaraRadio(val kode: String, val jumlah: Int)

@Serializable
private data class ApiNegara(
    val name: String = "",
    val stationcount: Int = 0,
)

/** Genre radio di layar Jelajah: label → tag Radio Browser. */
val RadioGenres = listOf(
    "Pop" to "pop",
    "Dangdut" to "dangdut",
    "Berita" to "news",
    "Religi" to "islamic",
    "Jazz" to "jazz",
    "Rock" to "rock",
    "Top 40" to "top 40",
    "Klasik" to "classical",
)

/**
 * Radio internet dari Radio Browser (radio-browser.info): direktori terbuka,
 * gratis, tanpa kunci API. Beberapa server cermin dicoba bergantian.
 */
/**
 * Batas jumlah stasiun per permintaan ke radio-browser. Terukur: Indonesia
 * punya 505 stasiun aktif, jadi batas lama 60 hanya menampilkan 12% katalog.
 */
const val JUMLAH_STASIUN_MAKS = 500

class RadioRepository(
    private val client: OkHttpClient,
    private val json: Json,
    private val dao: BytifyDao,
) {
    @Serializable
    private data class ApiStation(
        @SerialName("stationuuid") val uuid: String,
        val name: String,
        @SerialName("url_resolved") val urlResolved: String = "",
        val url: String = "",
        val favicon: String = "",
        val tags: String = "",
        @SerialName("countrycode") val countryCode: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        val hls: Int = 0,
        @SerialName("lastcheckok") val lastCheckOk: Int = 1,
    )

    private val cache = ConcurrentHashMap<String, List<RadioStation>>()

    @Volatile
    private var cacheNegara: List<NegaraRadio>? = null

    val favorites: Flow<List<RadioStation>> = dao.radioFavorites().map { list -> list.map(RadioStation::from) }

    suspend fun toggleFavorite(station: RadioStation, favorite: Boolean) {
        if (favorite) dao.deleteRadioFavorite(station.id) else dao.upsertRadioFavorite(station.toEntity())
    }

    /**
     * radio-browser tidak membatasi jumlah; Indonesia sendiri punya 505
     * stasiun aktif, jadi batas 60 memotong sebagian besar katalog.
     * Dinaikkan ke 500 — cukup memuat satu negara penuh tanpa membuat
     * satu permintaan menjadi terlalu besar.
     */
    /**
     * [country] null berarti SELURUH DUNIA: parameter countrycode tidak dikirim
     * sama sekali. Sebelumnya layar radio dipatok "ID", jadi direktori yang
     * sebenarnya mendunia hanya tampil sebagai katalog Indonesia.
     */
    suspend fun popular(country: String? = "ID", limit: Int = JUMLAH_STASIUN_MAKS) =
        query("search", buildMap {
            if (!country.isNullOrBlank()) put("countrycode", country)
            put("order", "clickcount")
            put("reverse", "true")
            put("limit", "$limit")
        })

    suspend fun byTag(tag: String, country: String? = "ID", limit: Int = JUMLAH_STASIUN_MAKS) =
        query("search", buildMap {
            if (!country.isNullOrBlank()) put("countrycode", country)
            put("tag", tag)
            put("order", "clickcount")
            put("reverse", "true")
            put("limit", "$limit")
        })

    suspend fun search(name: String, limit: Int = JUMLAH_STASIUN_MAKS): List<RadioStation> {
        val q = name.trim()
        if (q.isEmpty()) return emptyList()
        return query("search", mapOf("name" to q, "order" to "clickcount", "reverse" to "true", "limit" to "$limit"))
    }

    /**
     * Negara yang benar-benar punya stasiun, diurutkan dari yang terbanyak.
     * Diambil dari radio-browser sendiri supaya daftarnya tidak pernah basi.
     */
    suspend fun negara(batas: Int = 40): List<NegaraRadio> = withContext(Dispatchers.IO) {
        cacheNegara?.let { return@withContext it }
        for (host in MIRRORS) {
            try {
                val url = "https://$host/json/countrycodes".toHttpUrl()
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val mentah = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Daftar negara gagal dimuat (${response.code})")
                    json.decodeFromString<List<ApiNegara>>(response.body!!.string())
                }
                val hasil = mentah
                    .filter { it.name.length == 2 && it.stationcount >= 20 }
                    .sortedByDescending { it.stationcount }
                    .take(batas)
                    .map { NegaraRadio(it.name, it.stationcount) }
                cacheNegara = hasil
                return@withContext hasil
            } catch (_: Exception) {
                // cermin berikutnya
            }
        }
        emptyList()
    }

    private suspend fun query(path: String, params: Map<String, String>): List<RadioStation> = withContext(Dispatchers.IO) {
        val key = path + params.toSortedMap()
        cache[key]?.let { return@withContext it }
        var lastError: Exception? = null
        for (host in MIRRORS) {
            try {
                val url = "https://$host/json/stations/$path".toHttpUrl().newBuilder().apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                    addQueryParameter("hidebroken", "true")
                }.build()
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val stations = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Radio gagal dimuat (${response.code})")
                    json.decodeFromString<List<ApiStation>>(response.body!!.string())
                }
                val result = stations
                    .filter { it.lastCheckOk == 1 }
                    .mapNotNull { it.toStation() }
                    .distinctBy { it.name.lowercase().trim() }
                return@withContext result.also { cache[key] = it }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Radio tidak tersedia")
    }

    private fun ApiStation.toStation(): RadioStation? {
        val stream = urlResolved.ifBlank { url }.trim()
        if (!stream.startsWith("http")) return null
        return RadioStation(
            id = uuid,
            name = name.trim().ifBlank { return null },
            streamUrl = stream,
            favicon = favicon.takeIf { it.startsWith("http") },
            tags = tags.split(',').map { it.trim() }.filter { it.isNotBlank() },
            country = countryCode,
            codec = codec,
            bitrate = bitrate,
            hls = hls == 1 || stream.contains(".m3u8"),
        )
    }

    fun clearCache() {
        cache.clear()
        cacheNegara = null
    }

    companion object {
        private val MIRRORS = listOf(
            "de1.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "nl1.api.radio-browser.info",
        )
        private val USER_AGENT = "Bytify/${BuildConfig.VERSION_NAME}"
    }
}
