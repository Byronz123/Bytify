package com.bytify.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** HTTP client katalog live. Tidak mengambil link dari situs pihak ketiga di APK. */
class LiveCatalogApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val catalogUrl: String,
) {
    val isConfigured: Boolean get() = catalogUrl.isNotBlank()

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchCatalog(): LiveCatalogDto = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            throw IllegalStateException(
                "URL katalog Live TV belum diatur. Tambahkan live.catalog.url ke local.properties.",
            )
        }

        val url = catalogUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("live.catalog.url bukan URL HTTP/HTTPS yang valid.")
        if (url.scheme != "https" && url.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) {
            throw IllegalArgumentException("URL katalog Live TV harus menggunakan HTTPS.")
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bytify/${com.bytify.app.BuildConfig.VERSION_NAME} (Android)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Server Live TV mengembalikan HTTP ${response.code}.")
            }
            // Diurai dari aliran data, bukan String: katalog kanal kini
            // megabyte-an, dan memuatnya utuh ke memori pernah membuat
            // katalog film mati dengan OutOfMemoryError.
            val stream = response.body?.byteStream()
                ?: throw IOException("Respons katalog Live TV kosong.")
            try {
                json.decodeFromStream<LiveCatalogDto>(stream)
            } catch (error: Exception) {
                throw IOException("Format JSON Live TV tidak sesuai kontrak Bytify.", error)
            }
        }
    }
}
