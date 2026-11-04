package com.bytify.app.data.movie

import kotlinx.serialization.Serializable

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
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: Int? = null,
    val durationMin: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val language: String? = null,
    val videoUrl: String? = null,
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
    posterUrl = posterUrl.cleanUrlOrNull(),
    backdropUrl = backdropUrl.cleanUrlOrNull(),
    releaseYear = releaseYear?.takeIf { it in 1888..2200 },
    durationMin = durationMin?.takeIf { it > 0 },
    genres = genres.map(String::trim).filter(String::isNotEmpty).distinct(),
    rating = rating?.takeIf { it.isFinite() && it in 0.0..10.0 },
    language = language?.trim()?.takeIf(String::isNotEmpty),
    videoUrl = videoUrl.cleanUrlOrNull(),
    trailerUrl = trailerUrl.cleanUrlOrNull(),
    subtitles = subtitles.filter { it.url.cleanUrlOrNull() != null },
    license = license?.trim()?.takeIf(String::isNotEmpty),
    episodes = episodes
        .filter { it.id.isNotBlank() && it.videoUrl.cleanUrlOrNull() != null }
        .map { it.copy(subtitles = it.subtitles.filter { s -> s.url.cleanUrlOrNull() != null }) }
        .distinctBy { it.id },
)

private fun String?.cleanUrlOrNull(): String? = this
    ?.trim()
    ?.takeIf { value ->
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
    }
