package com.bytify.app.data.movie

import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.MovieEntity
import com.bytify.app.data.db.MovieFavoriteEntity
import com.bytify.app.data.db.MovieSectionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MovieRepository(
    private val dao: BytifyDao,
    private val api: MovieCatalogApi,
    private val json: Json,
) {
    val isCatalogConfigured: Boolean get() = api.isConfigured

    val movies: Flow<List<Movie>> = dao.movies().map { rows -> rows.map { it.toMovie(json) } }
    val favoriteMovies: Flow<List<Movie>> = dao.favoriteMovies().map { rows -> rows.map { it.toMovie(json) } }
    val favoriteIds: Flow<Set<String>> = dao.movieFavoriteIds().map { it.toSet() }
    val sections: Flow<List<MovieSection>> = dao.movieSections().map { rows ->
        rows.map { row ->
            MovieSection(
                title = row.title,
                movieIds = runCatching { json.decodeFromString<List<String>>(row.movieIdsJson) }.getOrDefault(emptyList()),
            )
        }
    }

    fun movie(id: String): Flow<Movie?> = dao.movie(id).map { it?.toMovie(json) }

    fun search(query: String): Flow<List<Movie>> = dao.searchMovies(query.trim())
        .map { rows -> rows.map { it.toMovie(json) } }

    /** Ambil katalog, simpan perubahan ke Room, dan pertahankan status favorit. */
    suspend fun refresh(): Int {
        val catalog = api.fetchCatalog()
        val now = System.currentTimeMillis()
        val validMovies = catalog.movies
            .asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .map { it.toMovie() }
            .distinctBy(Movie::id)
            .toList()

        if (validMovies.isNotEmpty()) {
            dao.upsertMovies(validMovies.map { it.toEntity(json, now) })
            dao.pruneMovies(now)
        }

        val validSections = catalog.sections
            .asSequence()
            .map { it.copy(title = it.title.trim(), movieIds = it.movieIds.distinct()) }
            .filter { it.title.isNotEmpty() }
            .distinctBy { it.title }
            .mapIndexed { index, section ->
                MovieSectionEntity(
                    title = section.title,
                    movieIdsJson = json.encodeToString(section.movieIds),
                    updatedAt = now + index,
                )
            }
            .toList()
        dao.replaceMovieSections(validSections)
        return validMovies.size
    }

    /** Memperkaya sinopsis, trailer, dan daftar episode lengkap saat halaman detail dibuka. */
    suspend fun enrichIfNeeded(movie: Movie) {
        if (!movie.id.startsWith("lk21-")) return
        if (!movie.isSeries && !movie.overview.startsWith("Tonton ") && movie.overview.isNotBlank()) return
        val detailedDto = api.fetchLk21Details(movie.id) ?: return
        val enriched = detailedDto.toMovie()
        dao.upsertMovies(listOf(enriched.toEntity(json, System.currentTimeMillis())))
    }

    /** Mencari film/serial ke server LK21 dan menyimpannya ke database lokal agar langsung tampil di UI. */
    suspend fun searchRemoteAndCache(query: String) {
        val results = api.searchRemote(query)
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = results
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .map { it.toMovie().toEntity(json, now) }
        if (entities.isNotEmpty()) {
            dao.upsertMovies(entities)
        }
    }

    suspend fun setFavorite(movie: Movie, favorite: Boolean) {
        if (favorite) {
            dao.upsertMovieFavorite(MovieFavoriteEntity(movieId = movie.id, savedAt = System.currentTimeMillis()))
        } else {
            dao.deleteMovieFavorite(movie.id)
        }
    }

    suspend fun playbackHistory(movieId: String) = dao.movieHistoryById(movieId)

    val history = dao.movieHistory()

    suspend fun savePlaybackPosition(movie: Movie, positionMs: Long, durationMs: Long, episode: EpisodeDto? = null) {
        dao.upsertMovieHistory(
            com.bytify.app.data.db.MovieHistoryEntity(
                movieId = episode?.id ?: movie.id,
                title = if (episode != null) "${movie.title} · ${episode.title}" else movie.title,
                posterUrl = movie.posterUrl,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}

private fun Movie.toEntity(json: Json, updatedAt: Long) = MovieEntity(
    id = id,
    type = if (isSeries) "series" else "movie",
    episodesJson = json.encodeToString(episodes),
    title = title,
    overview = overview,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    releaseYear = releaseYear,
    durationMin = durationMin,
    genresJson = json.encodeToString(genres),
    rating = rating,
    language = language,
    videoUrl = videoUrl,
    trailerUrl = trailerUrl,
    subtitlesJson = json.encodeToString(subtitles),
    license = license,
    updatedAt = updatedAt,
)

private fun MovieEntity.toMovie(json: Json) = Movie(
    id = id,
    isSeries = type == "series",
    episodes = runCatching { json.decodeFromString<List<EpisodeDto>>(episodesJson) }.getOrDefault(emptyList()),
    title = title,
    overview = overview,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    releaseYear = releaseYear,
    durationMin = durationMin,
    genres = runCatching { json.decodeFromString<List<String>>(genresJson) }.getOrDefault(emptyList()),
    rating = rating,
    language = language,
    videoUrl = videoUrl,
    trailerUrl = trailerUrl,
    subtitles = runCatching { json.decodeFromString<List<MovieSubtitleDto>>(subtitlesJson) }.getOrDefault(emptyList()),
    license = license,
)