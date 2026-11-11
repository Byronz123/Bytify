package com.bytify.app.data.movie

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Respons JSON katalog film yang disediakan backend Bytify. */
@Serializable
data class MovieCatalogDto(
    val movies: List<MovieDto> = emptyList(),
    val sections: List<MovieSectionDto> = emptyList(),
)

@Serializable
data class MovieDto(
    val id: String = "",
    /** "movie" atau "series". */
    val type: String = "movie",
    val title: String = "",
    val overview: String = "",
    val poster: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: Int? = null,
    val year: JsonElement? = null,
    val durationMin: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: JsonElement? = null,
    val language: String? = null,
    val videoUrl: String? = null,
    val streamUrl: String? = null,
    val streamUrls: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val servers: List<MovieServerDto> = emptyList(),
    val trailerUrl: String? = null,
    val subtitles: List<MovieSubtitleDto> = emptyList(),
    val license: String? = null,
    val episodes: List<EpisodeDto> = emptyList(),
)

/** Satu episode serial; setiap episode punya URL video dan subtitle sendiri. */
@Serializable
data class EpisodeDto(
    val id: String = "",
    val title: String = "",
    val season: Int = 1,
    val episode: Int = 1,
    val durationMin: Int? = null,
    val videoUrl: String? = null,
    val subtitles: List<MovieSubtitleDto> = emptyList(),
)

@Serializable
data class MovieSubtitleDto(
    val lang: String = "",
    val label: String = "",
    val url: String = "",
)

/** Satu server player alternatif (p2p, cast, hydrax, turbovip, ...). */
@Serializable
data class MovieServerDto(
    val server: String = "",
    val url: String = "",
)

@Serializable
data class MovieSectionDto(
    val title: String = "",
    val movieIds: List<String> = emptyList(),
)

data class Movie(
    val id: String,
    val isSeries: Boolean,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseYear: Int?,
    val durationMin: Int?,
    val genres: List<String>,
    val rating: Double?,
    val language: String?,
    val videoUrl: String?,
    val trailerUrl: String?,
    val subtitles: List<MovieSubtitleDto>,
    val license: String?,
    /** Halaman sumber film. Dipakai sebagai Referer saat memuat embed player. */
    val sourceUrl: String? = null,
    /** Server player alternatif; dipakai untuk berpindah sumber/resolusi. */
    val servers: List<MovieServerDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
) {
    /** Bisa diputar bila film punya URL video atau serial punya minimal satu episode. */
    val isPlayable: Boolean get() = !videoUrl.isNullOrBlank() || episodes.isNotEmpty()

    fun episode(id: String?): EpisodeDto? = episodes.firstOrNull { it.id == id }
}

data class MovieSection(
    val title: String,
    val movieIds: List<String>,
)

fun MovieDto.toMovie(): Movie = Movie(
    id = id.trim(),
    isSeries = type.equals("series", ignoreCase = true),
    title = title.trim(),
    overview = overview,
    posterUrl = (posterUrl ?: poster).cleanUrlOrNull(),
    backdropUrl = backdropUrl.cleanUrlOrNull(),
    releaseYear = (releaseYear ?: year?.jsonPrimitive?.contentOrNull?.toIntOrNull())
        ?.takeIf { it in 1888..2200 },
    durationMin = durationMin?.takeIf { it > 0 },
    genres = genres.map(String::trim).filter(String::isNotEmpty).distinct(),
    rating = rating?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..10.0 },
    language = language?.trim()?.takeIf(String::isNotEmpty),
    videoUrl = (videoUrl ?: streamUrl ?: streamUrls.firstOrNull()).cleanUrlOrNull(),
    trailerUrl = trailerUrl.cleanUrlOrNull(),
    subtitles = subtitles.filter { it.url.cleanUrlOrNull() != null },
    license = license?.trim()?.takeIf(String::isNotEmpty),
    sourceUrl = sourceUrl.cleanUrlOrNull(),
    servers = servers.filter { it.url.cleanUrlOrNull() != null }.distinctBy { it.url },
    episodes = episodes
        .filter { it.id.isNotBlank() && it.videoUrl.cleanUrlOrNull() != null }
        .map { it.copy(subtitles = it.subtitles.filter { s -> s.url.cleanUrlOrNull() != null }) }
        .distinctBy { it.id },
)

/**
 * Penanda URL sampah: video kampanye anti-judi dan iklan yang disisipkan sumber
 * ke dalam halaman film. URL seperti ini valid secara teknis (benar-benar .mp4),
 * sehingga tanpa penyaringan ini pemutar akan memutarnya seolah-olah film.
 * Catatan: hanya "donasi.assetsy" yang disaring; "poster.assetsy" adalah CDN
 * poster yang sah dan harus tetap lolos.
 */
private val JUNK_URL_MARKERS = listOf(
    "stopjudi", "donasi.assetsy", "judol", "slot-gacor", "anti-judi",
)

internal fun isJunkMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    return JUNK_URL_MARKERS.any { lower.contains(it) }
}

private fun String?.cleanUrlOrNull(): String? = this
    ?.trim()
    ?.takeIf { value ->
        (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) &&
            !isJunkMediaUrl(value)
    }
