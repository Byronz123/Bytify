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