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


// --- Lapisan ketersediaan resmi (JustWatch lewat TMDB) ---------------------
//
// Bentuknya SENGAJA beda dari MovieCatalogDto: kuncinya `titles` dengan kamus
// `providers` terpisah, bukan `movies`. Alasannya dua. Pertama, mengulang nama
// penyedia ("Amazon Prime Video") di puluhan ribu entri jauh lebih mahal
// daripada satu kamus 46 baris. Kedua, lapisan ini bukan daftar yang bisa
// diputar di dalam aplikasi — tidak ada videoUrl atau servers di sini sama
// sekali, dan uji kontrak di build-pages.test.mjs menjaga agar tetap begitu.

/** Respons JSON lapisan ketersediaan resmi. */
@Serializable
data class WatchCatalogDto(
    /** Atribusi wajib; ditampilkan di halaman detail. */
    val attribution: String? = null,
    val region: String = "ID",
    /** id penyedia (sebagai teks, karena kunci JSON) -> nama & logo. */
    val providers: Map<String, WatchProviderDto> = emptyMap(),
    val titles: List<WatchTitleDto> = emptyList(),
    /**
     * id katalog -> TMDB id, hasil pencocokan ketat judul+tahun. Inilah yang
     * menjembatani film LK21 berid siput ke lapisan ketersediaan & subtitle.
     */
    val aliases: Map<String, WatchAliasDto> = emptyMap(),
)

@Serializable
data class WatchAliasDto(
    val tmdbId: Int = 0,
    val type: String = "movie",
)

@Serializable
data class WatchProviderDto(
    val name: String = "",
    val logoPath: String? = null,
    val priority: Int? = null,
)

@Serializable
data class WatchTitleDto(
    val id: String = "",
    val tmdbId: Int = 0,
    /** "movie" atau "tv" — ruang id TMDB terpisah untuk keduanya. */
    val type: String = "movie",
    val title: String = "",
    val posterUrl: String? = null,
    val year: Int? = null,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtimeMin: Int? = null,
    /** jenis langganan -> daftar id penyedia, mis. {"flatrate":[8,159]}. */
    val watch: Map<String, List<Int>> = emptyMap(),
    /** Tautan resmi halaman tonton; dibuka DI LUAR aplikasi. */
    val watchLink: String? = null,
)

/** Satu penyedia sah yang siap ditampilkan sebagai chip. */
data class PenyediaTonton(
    val id: Int,
    val nama: String,
    val logoUrl: String?,
    /** "flatrate", "free", "ads", "rent", atau "buy". */
    val jenis: String,
)

/** Ketersediaan resmi satu judul, sudah dipetakan untuk UI. */
data class KetersediaanResmi(
    val tmdbId: Int,
    val judul: String,
    val penyedia: List<PenyediaTonton>,
    val tautan: String?,
    val atribusi: String?,
) {
    val kosong: Boolean get() = penyedia.isEmpty()
}


// --- Subtitle dari OpenSubtitles lewat proxy backend ---------------------
//
// Kunci API TIDAK ada di APK: ia hanya di sisi server, dan APK memanggil
// /api/subtitles. Kunci yang ditanam di APK bisa diambil siapa pun yang
// membongkar berkasnya.

@Serializable
data class SubtitleResponseDto(
    val ok: Boolean = false,
    val tmdbId: Int = 0,
    val subtitles: List<SubtitlePilihanDto> = emptyList(),
    val totalFound: Int = 0,
    val attribution: String? = null,
)

@Serializable
data class SubtitlePilihanDto(
    val fileId: Long = 0,
    val fileName: String? = null,
    val lang: String? = null,
    val release: String? = null,
    val downloads: Int = 0,
    /** Subtitle untuk penonton dengan gangguan pendengaran. */
    val hearingImpaired: Boolean = false,
)
