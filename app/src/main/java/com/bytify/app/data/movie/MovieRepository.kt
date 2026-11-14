package com.bytify.app.data.movie

import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.MovieEntity
import com.bytify.app.data.db.MovieFavoriteEntity
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.bytify.app.data.db.MovieGenreEntity
import com.bytify.app.data.db.MovieSectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Penanda bahwa katalog masih segar sehingga tidak diunduh ulang. */
const val KATALOG_MASIH_SEGAR = -1

/** Katalog dianggap usang setelah 3 jam. */
/** Berapa lama katalog yang sudah diurai ditahan setelah layar terakhir tutup. */
private const val TAHAN_MS = 60_000L

const val USIA_KATALOG_DEFAULT_MS = 3L * 60 * 60 * 1000

class MovieRepository(
    private val dao: BytifyDao,
    private val api: MovieCatalogApi,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    val isCatalogConfigured: Boolean get() = api.isConfigured

    // DAFTAR PENUH SENGAJA TIDAK ADA LAGI.
    //
    // Dulu di sini ada `movies: Flow<List<Movie>>` ber-stateIn yang menahan
    // SELURUH katalog. Terukur di Galaxy A36: puncaknya 101 MB dari batas heap
    // 256 MB, dan StateFlow menahan nilai terakhirnya walau tidak ada lagi yang
    // berlangganan. Itu jalan yang pernah mematikan aplikasi dengan
    // OutOfMemoryError.
    //
    // Penggantinya: halaman() untuk grid, deretGenre()/ratingTertinggi()/
    // filmTahun() untuk deret, dan jumlahFilm untuk "katalog ada isinya".
    // Jangan menambahkannya kembali — satu pemakai saja sudah cukup untuk
    // membuat seluruh daftar hidup lagi di memori.
    val favoriteMovies: Flow<List<Movie>> = dao.favoriteMovies()
        .map { rows -> rows.map { it.toMovieRingkas(json) } }
        .flowOn(Dispatchers.Default)
    val favoriteIds: Flow<Set<String>> = dao.movieFavoriteIds().map { it.toSet() }
    val sections: Flow<List<MovieSection>> = dao.movieSections().map { rows ->
        rows.map { row ->
            MovieSection(
                title = row.title,
                movieIds = runCatching { json.decodeFromString<List<String>>(row.movieIdsJson) }.getOrDefault(emptyList()),
            )
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(TAHAN_MS, TAHAN_MS), emptyList())

    fun movie(id: String): Flow<Movie?> = dao.movie(id).map { it?.toMovie(json) }

    fun search(query: String): Flow<List<Movie>> = dao.searchMovies(query.trim())
        .map { rows -> rows.map { it.toMovieRingkas(json) } }
        .flowOn(Dispatchers.Default)

    /**
     * Segarkan katalog hanya bila sudah lebih tua dari [maksimalUsiaMs].
     * Mengembalikan [KATALOG_MASIH_SEGAR] bila pengunduhan dilewati, sehingga
     * membuka layar film tidak selalu menarik ulang berkas katalog penuh.
     */
    // --- Daftar tanpa menahan semuanya di memori -------------------------

    /** Apakah katalog memuat serial; dulu `movies.any { it.isSeries }`. */
    val adaSerial: Flow<Boolean> = dao.adaSerial().flowOn(Dispatchers.Default)

    /**
     * Ukuran halaman. 60 baris cukup untuk mengisi grid dua kolom beberapa
     * layar ke depan tanpa menahan banyak objek; [maxSize] membatasi berapa
     * yang ditahan sekaligus, dan itulah yang membuat memorinya berhenti
     * bergantung pada jumlah film.
     */
    private val setelanHalaman = PagingConfig(
        pageSize = 60,
        prefetchDistance = 30,
        initialLoadSize = 120,
        maxSize = 300,
        enablePlaceholders = false,
    )

    /**
     * Satu halaman grid, mengikuti filter yang sama dengan sebelumnya.
     * Formatnya persis seperti yang dipakai UI: "movie", "series", "rating",
     * "genre:<nama>", "serialgenre:<nama>", selain itu seluruh katalog.
     *
     * `favorites` TIDAK lewat sini: jumlah favorit kecil dan sudah punya
     * alirannya sendiri.
     */
    fun halaman(
        filter: String,
        kueri: String = "",
        tahun: Int? = null,
    ): Flow<PagingData<Movie>> {
        val cari = kueri.trim()
        return Pager(setelanHalaman) {
            when {
                filter == "rating" -> dao.halamanRating(cari, tahun)
                filter.startsWith("genre:") ->
                    dao.halamanGenre(filter.removePrefix("genre:"), 0, cari, tahun)
                filter.startsWith("serialgenre:") ->
                    dao.halamanGenre(filter.removePrefix("serialgenre:"), 1, cari, tahun)
                filter == "movie" -> dao.halamanUmum(cari, tahun, "movie")
                filter == "series" -> dao.halamanUmum(cari, tahun, "series")
                else -> dao.halamanUmum(cari, tahun, "")
            }
        }.flow.map { data -> data.map { it.toMovieRingkas(json) } }
    }

    /**
     * Jumlah yang COCOK dengan filter, bukan yang sudah dimuat Paging.
     * Dipakai label "N film" supaya angkanya tidak berubah sambil menggulir.
     */
    suspend fun jumlahCocok(filter: String, kueri: String = "", tahun: Int? = null): Int {
        val cari = kueri.trim()
        return runCatching {
            when {
                filter == "rating" -> dao.jumlahRating(cari, tahun)
                filter.startsWith("genre:") ->
                    dao.jumlahGenre(filter.removePrefix("genre:"), 0, cari, tahun)
                filter.startsWith("serialgenre:") ->
                    dao.jumlahGenre(filter.removePrefix("serialgenre:"), 1, cari, tahun)
                filter == "movie" -> dao.jumlahUmum(cari, tahun, "movie")
                filter == "series" -> dao.jumlahUmum(cari, tahun, "series")
                else -> dao.jumlahUmum(cari, tahun, "")
            }
        }.getOrDefault(0)
    }

    /** Jumlah film di katalog; menggantikan `movies.isNotEmpty()` di layar. */
    val jumlahFilm: Flow<Int> = dao.jumlahFilmAlir().flowOn(Dispatchers.Default)

    /**
     * Deret genre dari SQL. Satu kueri untuk daftar genre beserta jumlahnya,
     * lalu satu kueri berbatas per genre untuk isinya — bukan mengelompokkan
     * 51 ribu film di memori.
     */
    suspend fun deretGenre(serial: Boolean, batasDeret: Int, isiDeret: Int): List<DeretGenre> =
        withContext(Dispatchers.Default) {
            val penanda = if (serial) 1 else 0
            runCatching { dao.genreTeratas(penanda, batasDeret) }
                .getOrDefault(emptyList())
                .map { baris ->
                    DeretGenre(
                        genre = baris.genre,
                        film = runCatching { dao.filmPerGenre(baris.genre, penanda, isiDeret) }
                            .getOrDefault(emptyList())
                            .map { it.toMovieRingkas(json) },
                        jumlah = baris.jumlah,
                    )
                }
                .filter { it.film.isNotEmpty() }
        }

    suspend fun tahunTersedia(): List<Int> =
        runCatching { dao.tahunTersedia() }.getOrDefault(emptyList())

    suspend fun ratingTertinggi(batas: Int): List<Movie> =
        withContext(Dispatchers.Default) {
            runCatching { dao.ratingTertinggi(batas) }.getOrDefault(emptyList())
                .map { it.toMovieRingkas(json) }
        }

    suspend fun filmTahun(tahun: Int, batas: Int): List<Movie> =
        withContext(Dispatchers.Default) {
            runCatching { dao.filmTahun(tahun, batas) }.getOrDefault(emptyList())
                .map { it.toMovieRingkas(json) }
        }

    /** Satu film untuk pencarian bertitik; menggantikan peta 51 ribu entri. */
    suspend fun filmSatu(id: String): Movie? =
        runCatching { dao.movieSekali(id)?.toMovieRingkas(json) }.getOrNull()

    /**
     * SATU pintu untuk menyimpan film. Film dan baris genrenya ditulis
     * bersama, jadi keduanya tidak bisa melenceng.
     *
     * Baris genre ada karena deret genre di layar Film dulu dibangun dengan
     * mengelompokkan SELURUH daftar film di memori — daftar yang memuncak di
     * 101 MB pada Galaxy A36 yang batas heap-nya 256 MB. Sekarang
     * pengelompokannya dikerjakan SQL.
     */
    private suspend fun simpanFilm(daftar: List<Movie>, now: Long) {
        if (daftar.isEmpty()) return
        dao.upsertMovies(daftar.map { it.toEntity(json, now) })
        val genre = daftar.flatMap { film ->
            film.genres.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { MovieGenreEntity(movieId = film.id, genre = it) }
                .toList()
        }
        // Dipotong supaya satu transaksi tidak menahan ratusan ribu baris
        // sekaligus; alasan yang sama dengan upsert alias ketersediaan.
        if (genre.isNotEmpty()) genre.chunked(2_000).forEach { dao.upsertMovieGenres(it) }
    }

    suspend fun refreshIfStale(maksimalUsiaMs: Long = USIA_KATALOG_DEFAULT_MS): Int {
        val terakhir = runCatching { dao.lastMovieSync() }.getOrNull() ?: 0L
        if (terakhir > 0L && System.currentTimeMillis() - terakhir < maksimalUsiaMs) {
            return KATALOG_MASIH_SEGAR
        }
        return refresh()
    }

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
            simpanFilm(validMovies, now)
            dao.pruneMovies(now)
            // Baris genre milik film yang sudah terbuang ikut dibersihkan.
            dao.pruneMovieGenres()
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

        // Serial datang dari berkas terpisah dan masuk ke tabel yang sama.
        // Sections TIDAK disentuh: itu milik katalog film, dan menuliskannya
        // ulang di sini akan menghapus deret yang baru saja dibuat.
        val serial = runCatching { api.fetchSeriesCatalog() }.getOrNull()
        val jumlahSerial = serial?.movies
            ?.filter { it.id.isNotBlank() && it.title.isNotBlank() }
            ?.map { it.toMovie() }
            ?.also { daftar ->
                if (daftar.isNotEmpty()) {
                    simpanFilm(daftar, now)
                }
            }
            ?.size ?: 0

        // Film Indonesia: berkas ketiga, masuk ke tabel yang sama. Sections
        // tidak disentuh, sama seperti serial.
        val indonesia = runCatching { api.fetchIndonesiaCatalog() }.getOrNull()
        val jumlahIndonesia = indonesia?.movies
            ?.filter { it.id.isNotBlank() && it.title.isNotBlank() }
            ?.map { it.toMovie() }
            ?.also { daftar ->
                if (daftar.isNotEmpty()) {
                    simpanFilm(daftar, now)
                }
            }
            ?.size ?: 0

        // Anime: berkas keempat, masuk ke tabel yang sama. Sections tidak
        // disentuh, sama seperti serial dan film Indonesia.
        val anime = runCatching { api.fetchAnimeCatalog() }.getOrNull()
        val jumlahAnime = anime?.movies
            ?.filter { it.id.isNotBlank() && it.title.isNotBlank() }
            ?.map { it.toMovie() }
            ?.also { daftar ->
                if (daftar.isNotEmpty()) {
                    simpanFilm(daftar, now)
                }
            }
            ?.size ?: 0

        return validMovies.size + jumlahSerial + jumlahIndonesia + jumlahAnime
    }

    /** Memperkaya sinopsis, trailer, dan daftar episode lengkap saat halaman detail dibuka. */
    suspend fun enrichIfNeeded(movie: Movie) {
        if (!movie.id.startsWith("lk21-")) return
        // Sinopsis di katalog dipotong (diakhiri "...") agar berkasnya muat di
        // batas 25 MiB Cloudflare Pages. Halaman detail melengkapinya di sini,
        // jadi pengguna tetap membaca sinopsis utuh.
        val terpotong = movie.overview.endsWith("...")
        if (!movie.isSeries && !terpotong &&
            !movie.overview.startsWith("Tonton ") && movie.overview.isNotBlank()
        ) return
        val detailedDto = api.fetchLk21Details(movie.id) ?: return
        val enriched = detailedDto.toMovie()
        simpanFilm(listOf(enriched), System.currentTimeMillis())
    }

    /** Mencari film/serial ke server LK21 dan menyimpannya ke database lokal agar langsung tampil di UI. */
    suspend fun searchRemoteAndCache(query: String) {
        val results = api.searchRemote(query)
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        val film = results
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .map { it.toMovie() }
        simpanFilm(film, now)
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
    sourceUrl = sourceUrl,
    serversJson = json.encodeToString(servers),
    updatedAt = updatedAt,
)

/**
 * Pemetaan RINGKAS untuk daftar besar (grid film, pencarian, favorit).
 *
 * Daftar ini menahan SELURUH katalog di memori sekaligus (StateFlow), dan
 * sejak anime masuk itu >51.000 film. Mengurai episodesJson/serversJson/
 * subtitlesJson untuk tiap film berarti puluhan ribu objek episode hidup di
 * memori sekaligus — terbukti mematikan aplikasi dengan OutOfMemoryError di
 * Galaxy A36 (heap 256 MB) saat sinkron katalog keempat.
 *
 * Grid, pencarian, dan favorit TIDAK pernah membaca episodes/servers/
 * subtitles — itu hanya dipakai layar detail & pemutar, yang memuat satu film
 * lewat movie(id) dengan toMovie() penuh. Genre tetap diurai karena deret
 * genre dibangun dari situ. "Lanjutkan menonton" menurunkan pemilik episode
 * dari id-nya (lihat pemilikEpisode di MoviesScreen), jadi tidak butuh daftar
 * episode di memori.
 */
private fun MovieEntity.toMovieRingkas(json: Json) = Movie(
    id = id,
    isSeries = type == "series",
    episodes = emptyList(),
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
    subtitles = emptyList(),
    license = license,
    sourceUrl = sourceUrl,
    servers = emptyList(),
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
    sourceUrl = sourceUrl,
    servers = runCatching { json.decodeFromString<List<MovieServerDto>>(serversJson) }
        .getOrDefault(emptyList()),
)