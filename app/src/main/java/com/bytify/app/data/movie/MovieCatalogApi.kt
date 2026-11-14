package com.bytify.app.data.movie

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URLEncoder

/** Klien untuk endpoint katalog JSON dan detail on-demand yang dikelola backend aplikasi. */
class MovieCatalogApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val catalogUrl: String,
) {
    val isConfigured: Boolean
        get() = catalogUrl.isNotBlank()

    private val baseApiUrl: String
        get() {
            val idx = catalogUrl.indexOf("/api/")
            return if (idx > 0) catalogUrl.substring(0, idx) else catalogUrl.removeSuffix("/catalog.json")
        }

    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "127.0.0.1" || h == "10.0.2.2" ||
            h.startsWith("192.168.") || h.startsWith("10.") || h.endsWith(".local")
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchCatalog(): MovieCatalogDto = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            throw IllegalStateException(
                "URL katalog belum diatur. Tambahkan movie.catalog.url ke local.properties.",
            )
        }

        val parsedUrl = catalogUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("movie.catalog.url bukan URL HTTP/HTTPS yang valid.")
        if (parsedUrl.scheme != "https" && !isAllowedHost(parsedUrl.host)) {
            throw IllegalArgumentException("URL katalog harus menggunakan HTTPS atau IP lokal.")
        }

        val request = Request.Builder()
            .url(parsedUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bytify/${com.bytify.app.BuildConfig.VERSION_NAME} (Android)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Server katalog mengembalikan HTTP ${response.code}.")
            }
            // Diurai langsung dari aliran data, TIDAK lewat String.
            // Katalog kini ~32 MB; memuatnya sebagai String menjadi ~66 MB di
            // memori (UTF-16) dan terbukti membuat aplikasi mati dengan
            // OutOfMemoryError sebelum sempat mengurai apa pun.
            val stream = response.body?.byteStream()
                ?: throw IOException("Respons katalog kosong.")
            try {
                json.decodeFromStream<MovieCatalogDto>(stream)
            } catch (error: Exception) {
                throw IOException("Format JSON katalog tidak sesuai kontrak Bytify.", error)
            }
        }
    }

    /**
     * Pengunduh bersama untuk katalog yang terbit sebagai berkas TAMBAHAN di
     * samping catalog.json — serial, film Indonesia, dan anime.
     *
     * Ketiganya dipisah karena alasan yang sama: batas keras 25 MiB per berkas
     * di Cloudflare Pages, sementara catalog.json sendiri sudah 23,26 MiB.
     * URL-nya diturunkan dari URL katalog film, jadi tidak ada setelan baru di
     * local.properties.
     *
     * Mengembalikan null — bukan melempar — bila berkasnya belum ada, agar APK
     * lama tetap jalan melawan deploy yang belum memuat katalog itu.
     *
     * Diurai dari ALIRAN DATA; jangan dikembalikan ke decodeFromString, karena
     * katalog sebesar ini membuat String UTF-16 yang mematikan aplikasi dengan
     * OutOfMemoryError.
     */
    private suspend fun <T> ambilBerkasTambahan(
        berkas: String,
        urai: (java.io.InputStream) -> T,
    ): T? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val parsedUrl = "$baseApiUrl/api/$berkas.json".toHttpUrlOrNull() ?: return@withContext null
        if (parsedUrl.scheme != "https" && !isAllowedHost(parsedUrl.host)) return@withContext null

        val request = Request.Builder()
            .url(parsedUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bytify/${com.bytify.app.BuildConfig.VERSION_NAME} (Android)")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val stream = response.body?.byteStream() ?: return@withContext null
                urai(stream)
            }
        } catch (error: Exception) {
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun ambilKatalogTambahan(berkas: String): MovieCatalogDto? =
        ambilBerkasTambahan(berkas) { json.decodeFromStream<MovieCatalogDto>(it) }

    /**
     * Mengambil katalog serial, yang terbit sebagai berkas terpisah di samping
     * catalog.json.
     *
     * Dipisah karena batas keras 25 MiB per berkas di Cloudflare Pages: katalog
     * film sendiri sudah 23,26 MiB, sedangkan serial membawa puluhan ribu
     * episode. URL-nya diturunkan dari URL katalog film supaya tidak ada
     * setelan baru yang harus diisi di local.properties.
     *
     * Mengembalikan null — bukan melempar — bila berkasnya belum ada, agar APK
     * lama tetap jalan melawan deploy yang belum memuat serial.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchSeriesCatalog(): MovieCatalogDto? = ambilKatalogTambahan("series")

    /**
     * Mengambil katalog film Indonesia, yang terbit sebagai berkas terpisah.
     *
     * Film-film ini diputar lewat embed framextv (berkunci TMDB id), bukan
     * lewat server LK21 — terbukti berputar di Galaxy A36. Dipisah dari
     * catalog.json karena alasan yang sama dengan serial: batas keras 25 MiB
     * per berkas di Cloudflare Pages.
     *
     * Mengembalikan null bila berkasnya belum ada, agar APK lama tetap jalan.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchIndonesiaCatalog(): MovieCatalogDto? = ambilKatalogTambahan("indonesia")

    /**
     * Mengambil katalog anime. Sumber metadatanya AniList; pemutarnya rute
     * embed anime framextv yang dikunci dengan AniList id — pola yang sama
     * dengan film Indonesia yang memakai TMDB id.
     */
    suspend fun fetchAnimeCatalog(): MovieCatalogDto? = ambilKatalogTambahan("anime")

    /**
     * Mengambil lapisan KETERSEDIAAN RESMI (data JustWatch lewat TMDB).
     *
     * Bukan katalog pemutaran: isinya daftar layanan sah di Indonesia per judul
     * plus tautan resminya, yang dibuka DI LUAR aplikasi lewat intent. Karena
     * itu bentuknya WatchCatalogDto, bukan MovieCatalogDto, dan isinya tidak
     * pernah masuk tabel `movies`.
     *
     * Mengembalikan null bila berkasnya belum ada, agar APK lama tetap jalan.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchWatchCatalog(): WatchCatalogDto? =
        ambilBerkasTambahan("watch") { json.decodeFromStream<WatchCatalogDto>(it) }

    /**
     * Daftar subtitle untuk satu judul, lewat proxy backend.
     *
     * URL tiap subtitle menunjuk ke /api/subtitles/file?fileId=... yang
     * mengalihkan ke tautan sementara OpenSubtitles saat diambil. Jadi kuota
     * unduh baru terpakai ketika pemutar benar-benar mengunduh berkasnya,
     * bukan saat daftarnya ditampilkan.
     *
     * Mengembalikan daftar kosong pada kegagalan apa pun: subtitle itu
     * tambahan, dan pemutaran tidak boleh ikut gagal karenanya.
     */
    suspend fun fetchSubtitles(
        tmdbId: Int,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        languages: String = "id,en",
    ): List<MovieSubtitleDto> = withContext(Dispatchers.IO) {
        if (!isConfigured || tmdbId <= 0) return@withContext emptyList()
        val dasar = baseApiUrl
        val sb = StringBuilder("$dasar/api/subtitles?tmdbId=$tmdbId")
            .append("&lang=").append(URLEncoder.encode(languages, "UTF-8"))
            .append("&type=").append(if (type == "episode") "episode" else "movie")
        if (season != null) sb.append("&season=").append(season)
        if (episode != null) sb.append("&episode=").append(episode)
        val parsedUrl = sb.toString().toHttpUrlOrNull() ?: return@withContext emptyList()
        if (parsedUrl.scheme != "https" && !isAllowedHost(parsedUrl.host)) return@withContext emptyList()

        val request = Request.Builder()
            .url(parsedUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bytify/${com.bytify.app.BuildConfig.VERSION_NAME} (Android)")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string()?.takeIf(String::isNotBlank) ?: return@use emptyList()
                val dto = json.decodeFromString<SubtitleResponseDto>(body)
                dto.subtitles.mapNotNull { pilihan ->
                    if (pilihan.fileId <= 0) return@mapNotNull null
                    val kode = (pilihan.lang ?: "").lowercase().ifBlank { "und" }
                    MovieSubtitleDto(
                        lang = kode,
                        label = labelBahasa(kode) + if (pilihan.hearingImpaired) " (CC)" else "",
                        url = "$dasar/api/subtitles/file?fileId=${pilihan.fileId}",
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Mengambil detail lengkap (sinopsis, trailer, dan seluruh episode series) dari backend LK21. */
    suspend fun fetchLk21Details(movieId: String): MovieDto? = withContext(Dispatchers.IO) {
        if (!isConfigured || !movieId.startsWith("lk21-")) return@withContext null
        val withoutPrefix = movieId.removePrefix("lk21-")
        val type = if (withoutPrefix.startsWith("series-")) "series" else "movie"
        val slug = withoutPrefix.removePrefix("series-").removePrefix("movie-")
        val encodedSlug = URLEncoder.encode(slug, "UTF-8")
        val targetUrl = "$baseApiUrl/api/lk21/details/$type/$encodedSlug".toHttpUrlOrNull()
            ?: return@withContext null

        val request = Request.Builder()
            .url(targetUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bytify/${com.bytify.app.BuildConfig.VERSION_NAME} (Android)")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string()?.takeIf(String::isNotBlank) ?: return@use null
                json.decodeFromString<MovieDto>(body)
            }
        }.getOrNull()
    }

    /** Mencari judul film/serial secara langsung ke LK21 via backend. */
    suspend fun searchRemote(query: String): List<MovieDto> = withContext(Dispatchers.IO) {
        if (!isConfigured || query.trim().length < 2) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val targetUrl = "$baseApiUrl/api/lk21/search?q=$encoded".toHttpUrlOrNull()
            ?: return@withContext emptyList()

        val request = Request.Builder()
            .url(targetUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bytify/${com.bytify.app.BuildConfig.VERSION_NAME} (Android)")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string()?.takeIf(String::isNotBlank) ?: return@use emptyList()
                json.decodeFromString<MovieCatalogDto>(body).movies
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Nama bahasa untuk label subtitle. Hanya yang paling sering muncul di katalog
 * ini; sisanya memakai kode aslinya dalam huruf besar, yang tetap terbaca.
 */
private fun labelBahasa(kode: String): String = when (kode.lowercase()) {
    "id" -> "Indonesia"
    "en" -> "English"
    "ms" -> "Melayu"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "zh" -> "中文"
    "th" -> "ไทย"
    "ar" -> "العربية"
    "es" -> "Español"
    "fr" -> "Français"
    "de" -> "Deutsch"
    "hi" -> "हिन्दी"
    "und" -> "Subtitle"
    else -> kode.uppercase()
}
