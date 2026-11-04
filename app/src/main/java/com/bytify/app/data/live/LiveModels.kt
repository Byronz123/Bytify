package com.bytify.app.data.live

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Kontrak respons backend live TV. Status ketersediaan ditentukan oleh backend. */
@Serializable
data class LiveCatalogDto(
    val schemaVersion: Int = 1,
    val generatedAtEpochMs: Long = 0L,
    val refreshAfterSeconds: Int = 300,
    val channels: List<LiveChannelDto> = emptyList(),
    val sourceSyncError: String? = null,
)

@Serializable
data class LiveChannelDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val logoUrl: String? = null,
    val category: String = "General",
    val country: String = "ID",
    val language: String = "",
    val streamUrl: String = "",
    val streamType: String = "hls",
    val isAvailable: Boolean = false,
    val statusMessage: String? = null,
    val checkedAtEpochMs: Long = 0L,
    val license: String? = null,
)

data class LiveChannel(
    val id: String,
    val name: String,
    val description: String,
    val logoUrl: String?,
    val category: String,
    val country: String,
    val language: String,
    val streamUrl: String,
    val streamType: String,
    val isAvailable: Boolean,
    val statusMessage: String?,
    val checkedAtEpochMs: Long,
    val license: String?,
)

fun LiveChannelDto.toChannelOrNull(): LiveChannel? {
    val idValue = id.trim()
    val title = name.trim()
    val url = streamUrl.toHttpsUrlOrNull() ?: return null
    if (idValue.isBlank() || idValue.length > 160 || title.isBlank() || title.length > 160) return null
    val type = streamType.trim().lowercase().ifBlank { "hls" }
    if (type !in setOf("hls", "mp4")) return null
    return LiveChannel(
        id = idValue,
        name = title,
        description = description.trim().take(600),
        logoUrl = logoUrl.toHttpsUrlOrNull(),
        category = category.trim().take(80).ifBlank { "General" },
        country = country.trim().uppercase().take(8).ifBlank { "ID" },
        language = language.trim().take(24),
        streamUrl = url,
        streamType = type,
        isAvailable = isAvailable,
        statusMessage = statusMessage?.trim()?.take(160)?.takeIf(String::isNotEmpty),
        checkedAtEpochMs = checkedAtEpochMs.coerceAtLeast(0L),
        license = license?.trim()?.take(240)?.takeIf(String::isNotEmpty),
    )
}

private fun String?.toHttpsUrlOrNull(): String? {
    val value = this?.trim()?.takeIf { it.length <= 4096 } ?: return null
    val parsed = value.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    return parsed.toString()
}
