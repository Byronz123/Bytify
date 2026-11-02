package com.bytify.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Terjemahan lirik per baris lewat Google Translate gratis (tanpa kunci API). */
class TranslateRepository(private val client: OkHttpClient, private val json: Json) {
    private val cache = ConcurrentHashMap<String, List<String?>>()

    /**
     * Terjemahkan [lines] ke [target]. Hasil sejajar dengan input; baris kosong
     * atau yang sudah berbahasa target bernilai null.
     */
    suspend fun translate(cacheKey: String, lines: List<String>, target: String = "id"): List<String?> =
        withContext(Dispatchers.IO) {
            cache["$cacheKey:$target"]?.let { return@withContext it }
            val result = MutableList<String?>(lines.size) { null }
            val indexed = lines.withIndex().filter { it.value.isNotBlank() }
            indexed.chunked(BATCH).forEach { batch ->
                val url = "https://clients5.google.com/translate_a/t".toHttpUrl().newBuilder()
                    .addQueryParameter("client", "dict-chrome-ex")
                    .addQueryParameter("sl", "auto")
                    .addQueryParameter("tl", target)
                    .apply { batch.forEach { addQueryParameter("q", it.value) } }
                    .build()
                val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("Terjemahan gagal (${response.code})")
                    response.body!!.string()
                }
                val items = json.parseToJsonElement(body) as? JsonArray ?: error("Format terjemahan tidak dikenal")
                items.forEachIndexed { i, element ->
                    val original = batch.getOrNull(i) ?: return@forEachIndexed
                    val (text, lang) = when (element) {
                        is JsonArray -> (element.getOrNull(0) as? JsonPrimitive)?.content to
                            (element.getOrNull(1) as? JsonPrimitive)?.content
                        is JsonPrimitive -> element.content to null
                        else -> null to null
                    }
                    val same = lang == target || text.equals(original.value, ignoreCase = true)
                    result[original.index] = text?.takeUnless { same }
                }
            }
            result.also { cache["$cacheKey:$target"] = it }
        }

    companion object {
        private const val BATCH = 30
    }
}
