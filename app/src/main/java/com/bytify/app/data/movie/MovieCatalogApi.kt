package com.bytify.app.data.movie

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Klien untuk satu endpoint katalog JSON yang dikelola backend aplikasi. */
class MovieCatalogApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val catalogUrl: String,
) {
    val isConfigured: Boolean
        get() = catalogUrl.isNotBlank()

    suspend fun fetchCatalog(): MovieCatalogDto = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            throw IllegalStateException(
                "URL katalog belum diatur. Tambahkan movie.catalog.url ke local.properties.",
            )
        }

        val parsedUrl = catalogUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("movie.catalog.url bukan URL HTTP/HTTPS yang valid.")
        if (parsedUrl.scheme != "https" && parsedUrl.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) {
            throw IllegalArgumentException("URL katalog harus menggunakan HTTPS.")
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
            val body = response.body?.string()?.takeIf(String::isNotBlank)
                ?: throw IOException("Respons katalog kosong.")
            try {
                json.decodeFromString<MovieCatalogDto>(body)
            } catch (error: Exception) {
                throw IOException("Format JSON katalog tidak sesuai kontrak Bytify.", error)
            }
        }
    }
}
