package com.bytify.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.paging.PagingSource
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BytifyDao {
    // ---------- Lagu & favorit ----------
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun song(id: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongIfAbsent(song: SongEntity): Long

    @Query(
        """UPDATE songs SET title = :title, artist = :artist,
           thumbnailUrl = COALESCE(:thumbnailUrl, thumbnailUrl),
           durationSec = COALESCE(:durationSec, durationSec),
           streamUrl = COALESCE(:streamUrl, streamUrl), kind = COALESCE(:kind, kind) WHERE id = :id""",
    )
    suspend fun updateSongMeta(
        id: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        durationSec: Long?,
        streamUrl: String?,
        kind: String?,
    )

    /** Simpan metadata lagu tanpa menimpa status suka. */
    @Transaction
    suspend fun saveSong(song: SongEntity) {
        if (insertSongIfAbsent(song) == -1L) {
            updateSongMeta(song.id, song.title, song.artist, song.thumbnailUrl, song.durationSec, song.streamUrl, song.kind)
        }
    }

    @Query("UPDATE songs SET likedAt = :likedAt WHERE id = :id")
    suspend fun setLiked(id: String, likedAt: Long?)

    @Query("SELECT * FROM songs WHERE likedAt IS NOT NULL ORDER BY likedAt DESC")
    fun likedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    fun songsByIds(ids: List<String>): Flow<List<SongEntity>>

    @Query("SELECT id FROM songs WHERE likedAt IS NOT NULL")
    fun likedIds(): Flow<List<String>>

    // ---------- Riwayat ----------
    @Insert
    suspend fun insertEvent(event: PlayEventEntity): Long

    @Query("UPDATE play_events SET playTimeMs = :playTimeMs WHERE id = :id")
    suspend fun updateEventPlayTime(id: Long, playTimeMs: Long)

    @Query(
        """SELECT s.id AS s_id, s.title AS s_title, s.artist AS s_artist,
           s.thumbnailUrl AS s_thumbnailUrl, s.durationSec AS s_durationSec, s.likedAt AS s_likedAt,
           e.timestamp AS timestamp, e.id AS eventId
           FROM play_events e JOIN songs s ON s.id = e.songId
           ORDER BY e.timestamp DESC LIMIT :limit""",
    )
    fun history(limit: Int): Flow<List<HistoryRow>>

    @Query("DELETE FROM play_events WHERE id = :eventId")
    suspend fun deleteEvent(eventId: Long)

    @Query("DELETE FROM play_events")
    suspend fun clearHistory()

    /** Lagu unik yang terakhir diputar (Quick picks & Lanjutkan mendengarkan). */
    @Query(
        """SELECT s.* FROM songs s JOIN
           (SELECT songId, MAX(timestamp) AS lastPlayed FROM play_events GROUP BY songId) r
           ON r.songId = s.id ORDER BY r.lastPlayed DESC LIMIT :limit""",
    )
    fun recentSongs(limit: Int): Flow<List<SongEntity>>

    // ---------- Katalog lagu (kandidat rekomendasi) ----------
    @Upsert
    suspend fun upsertCatalogSongs(rows: List<CatalogSongEntity>)

    /**
     * Menyimpan hanya lagu yang kuncinya belum ada. Dipakai sumber pelengkap
     * (YouTube/Spotify) supaya tidak menimpa peringkat populer dari Apple.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCatalogSongsIfAbsent(rows: List<CatalogSongEntity>)

    @Query("SELECT * FROM catalog_songs ORDER BY rank ASC")
    fun catalogSongs(): Flow<List<CatalogSongEntity>>

    @Query("SELECT COUNT(*) FROM catalog_songs")
    suspend fun catalogSongCount(): Int

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM catalog_songs")
    suspend fun lastCatalogSongSync(): Long

    /** Id lagu yang baru diputar, untuk menekan pengulangan di rekomendasi. */
    @Query("SELECT DISTINCT songId FROM play_events ORDER BY timestamp DESC LIMIT :limit")
    fun recentlyPlayedIds(limit: Int): Flow<List<String>>

    // ---------- Statistik ----------
    @Query(
        """SELECT s.*, COUNT(e.id) AS plays, SUM(e.playTimeMs) AS totalMs
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp >= :since GROUP BY s.id
           ORDER BY totalMs DESC, plays DESC LIMIT :limit""",
    )
    fun topSongs(since: Long, limit: Int): Flow<List<SongStat>>

    @Query(
        """SELECT s.artist AS artist, MAX(s.thumbnailUrl) AS thumbnailUrl,
           COUNT(e.id) AS plays, SUM(e.playTimeMs) AS totalMs
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp >= :since GROUP BY s.artist
           ORDER BY totalMs DESC LIMIT :limit""",
    )
    fun topArtists(since: Long, limit: Int): Flow<List<ArtistStat>>

    @Query("SELECT COALESCE(SUM(playTimeMs), 0) FROM play_events WHERE timestamp >= :since")
    fun totalPlayTime(since: Long): Flow<Long>

    /** Lagu yang dulu sering diputar tapi tidak didengar sejak [before]. */
    @Query(
        """SELECT s.*, COUNT(e.id) AS plays, SUM(e.playTimeMs) AS totalMs
           FROM play_events e JOIN songs s ON s.id = e.songId
           GROUP BY s.id HAVING MAX(e.timestamp) < :before
           ORDER BY plays DESC LIMIT :limit""",
    )
    fun forgottenFavorites(before: Long, limit: Int): Flow<List<SongStat>>

    // ---------- Radio ----------
    @Query("SELECT * FROM radio_favorites ORDER BY savedAt DESC")
    fun radioFavorites(): Flow<List<RadioFavoriteEntity>>

    @Upsert
    suspend fun upsertRadioFavorite(station: RadioFavoriteEntity)

    @Query("DELETE FROM radio_favorites WHERE id = :id")
    suspend fun deleteRadioFavorite(id: String)

    // ---------- Podcast ----------
    @Query("SELECT * FROM podcast_subscriptions ORDER BY subscribedAt DESC")
    fun podcastSubscriptions(): Flow<List<PodcastSubscriptionEntity>>

    @Upsert
    suspend fun upsertPodcastSubscription(podcast: PodcastSubscriptionEntity)

    @Query("DELETE FROM podcast_subscriptions WHERE id = :id")
    suspend fun deletePodcastSubscription(id: Long)

    @Query("UPDATE podcast_subscriptions SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markPodcastOpened(id: Long, at: Long)

    @Upsert
    suspend fun upsertEpisodeProgress(progress: EpisodeProgressEntity)

    @Query("SELECT * FROM episode_progress WHERE episodeId = :id")
    suspend fun episodeProgress(id: String): EpisodeProgressEntity?

    @Query("SELECT * FROM episode_progress")
    fun allEpisodeProgress(): Flow<List<EpisodeProgressEntity>>

    // ---------- Alarm ----------
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun alarms(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun alarm(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun enabledAlarms(): List<AlarmEntity>

    @Upsert
    suspend fun upsertAlarm(alarm: AlarmEntity): Long

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarm(id: Long)

    // ---------- Film: katalog, favorit, riwayat ----------
    /** Waktu sinkronisasi katalog terakhir; null bila katalog masih kosong. */
    @Query("SELECT MAX(updatedAt) FROM movies")
    suspend fun lastMovieSync(): Long?

    @Query("SELECT * FROM movies ORDER BY updatedAt DESC, title COLLATE NOCASE")
    fun movies(): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    fun movie(id: String): Flow<MovieEntity?>

    /**
     * Versi sekali-ambil. Dipakai untuk pencarian bertitik dari id, yang
     * menggantikan peta 51 ribu entri hasil associateBy di MoviesScreen.
     */
    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    suspend fun movieSekali(id: String): MovieEntity?

    @Query(
        """SELECT * FROM movies
           WHERE title LIKE '%' || :query || '%' COLLATE NOCASE
              OR overview LIKE '%' || :query || '%' COLLATE NOCASE
              OR genresJson LIKE '%' || :query || '%' COLLATE NOCASE
           ORDER BY title COLLATE NOCASE""",
    )
    fun searchMovies(query: String): Flow<List<MovieEntity>>

    @Upsert
    suspend fun upsertMovies(movies: List<MovieEntity>)

    /** Hapus judul yang tidak lagi ada di katalog server, kecuali yang difavoritkan. */
    @Query("DELETE FROM movies WHERE updatedAt < :syncedAt AND id NOT IN (SELECT movieId FROM movie_favorites)")
    suspend fun pruneMovies(syncedAt: Long)

    /**
     * Membuang anime lama berid "anilist-".
     *
     * Katalog anime pindah ke ruang id anikai. Tanpa pembersihan ini, baris
     * dari sinkron sebelumnya tetap tinggal — `pruneMovies` hanya menyapu
     * setelah katalog FILM, sementara anime datang dari berkas terpisah. Yang
     * tersisa adalah judul ganda yang servernya framextv dan tidak bisa
     * diputar sama sekali.
     *
     * Favorit sengaja TIDAK dikecualikan di sini: barisnya memang tidak bisa
     * diputar lagi, jadi menahannya hanya menyisakan entri rusak.
     */
    @Query("DELETE FROM movies WHERE id LIKE 'anilist-%'")
    suspend fun hapusAnimeAniList(): Int

    @Query(
        """SELECT m.* FROM movies AS m
           INNER JOIN movie_favorites AS f ON f.movieId = m.id
           ORDER BY f.savedAt DESC""",
    )
    fun favoriteMovies(): Flow<List<MovieEntity>>

    @Query("SELECT movieId FROM movie_favorites ORDER BY savedAt DESC")
    fun movieFavoriteIds(): Flow<List<String>>

    @Upsert
    suspend fun upsertMovieFavorite(favorite: MovieFavoriteEntity)

    @Query("DELETE FROM movie_favorites WHERE movieId = :movieId")
    suspend fun deleteMovieFavorite(movieId: String)

    // updatedAt menyimpan urutan dari server (lihat MovieRepository.refresh).
    @Query("SELECT * FROM movie_sections ORDER BY updatedAt, title COLLATE NOCASE")
    fun movieSections(): Flow<List<MovieSectionEntity>>

    @Query("DELETE FROM movie_sections")
    suspend fun clearMovieSections()

    @Upsert
    suspend fun upsertMovieSections(sections: List<MovieSectionEntity>)

    @Transaction
    suspend fun replaceMovieSections(sections: List<MovieSectionEntity>) {
        clearMovieSections()
        if (sections.isNotEmpty()) upsertMovieSections(sections)
    }

    @Upsert
    suspend fun upsertMovieHistory(history: MovieHistoryEntity)

    @Query("SELECT * FROM movie_history ORDER BY updatedAt DESC")
    fun movieHistory(): Flow<List<MovieHistoryEntity>>

    @Query("SELECT * FROM movie_history WHERE movieId = :movieId LIMIT 1")
    suspend fun movieHistoryById(movieId: String): MovieHistoryEntity?

    // ---------- Live TV: katalog dinamis & favorit ----------
    @Query("SELECT * FROM live_channels ORDER BY isAvailable DESC, category COLLATE NOCASE, name COLLATE NOCASE")
    fun liveChannels(): Flow<List<LiveChannelEntity>>

    @Query("SELECT * FROM live_channels WHERE id = :id LIMIT 1")
    fun liveChannel(id: String): Flow<LiveChannelEntity?>

    @Query("SELECT * FROM live_channels WHERE isAvailable = 1 ORDER BY category COLLATE NOCASE, name COLLATE NOCASE")
    fun availableLiveChannels(): Flow<List<LiveChannelEntity>>

    @Query("SELECT channelId FROM live_favorites ORDER BY savedAt DESC")
    fun liveFavoriteIds(): Flow<List<String>>

    @Upsert
    suspend fun upsertLiveChannels(channels: List<LiveChannelEntity>)

    @Query("DELETE FROM live_channels")
    suspend fun clearLiveChannels()

    @Transaction
    suspend fun replaceLiveChannels(channels: List<LiveChannelEntity>) {
        clearLiveChannels()
        if (channels.isNotEmpty()) upsertLiveChannels(channels)
    }

    @Upsert
    suspend fun upsertLiveFavorite(favorite: LiveFavoriteEntity)

    @Query("DELETE FROM live_favorites WHERE channelId = :channelId")
    suspend fun deleteLiveFavorite(channelId: String)

    // ---------- Playlist ----------
    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearPlaylistSongs(id: Long)

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        clearPlaylistSongs(id)
        deletePlaylistRow(id)
    }

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT p.id AS id, p.name AS name,
           (SELECT COUNT(*) FROM playlist_songs c WHERE c.playlistId = p.id) AS songCount,
           (SELECT GROUP_CONCAT(t, '|') FROM (SELECT s2.thumbnailUrl AS t FROM playlist_songs ps2
              JOIN songs s2 ON s2.id = ps2.songId WHERE ps2.playlistId = p.id
              ORDER BY ps2.position LIMIT 4)) AS thumbnails
           FROM playlists p ORDER BY p.createdAt DESC""",
    )
    fun playlists(): Flow<List<PlaylistSummary>>

    @Query(
        """SELECT s.* FROM playlist_songs ps JOIN songs s ON s.id = ps.songId
           WHERE ps.playlistId = :id ORDER BY ps.position""",
    )
    fun playlistSongs(id: Long): Flow<List<SongEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :id")
    suspend fun nextPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSong(entry: PlaylistSongEntity): Long

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeFromPlaylist(playlistId: Long, songId: String)

    // --- Ketersediaan resmi (JustWatch lewat TMDB) -------------------------
    //
    // SENGAJA tidak ada Flow<List<WatchTitleEntity>> untuk seluruh tabel.
    // Tabel ini puluhan ribu baris, dan StateFlow yang menahan seluruh daftar
    // film di memori adalah yang pernah mematikan aplikasi dengan
    // OutOfMemoryError di Galaxy A36. Semua akses di bawah ini bertitik: satu
    // judul, atau sepotong daftar dengan LIMIT.

    @Upsert
    suspend fun upsertWatchTitles(rows: List<WatchTitleEntity>)

    @Upsert
    suspend fun upsertWatchProviders(rows: List<WatchProviderEntity>)

    /** Ketersediaan satu judul. TMDB memakai ruang id terpisah untuk movie & tv. */
    @Query("SELECT * FROM watch_titles WHERE tmdbId = :tmdbId AND type = :type LIMIT 1")
    suspend fun watchTitle(tmdbId: Int, type: String): WatchTitleEntity?

    /** Dipakai kalau tipenya belum diketahui; film lebih diutamakan. */
    @Query(
        """SELECT * FROM watch_titles WHERE tmdbId = :tmdbId
           ORDER BY CASE type WHEN 'movie' THEN 0 ELSE 1 END LIMIT 1""",
    )
    suspend fun watchTitleAnyType(tmdbId: Int): WatchTitleEntity?

    @Query("SELECT * FROM watch_providers WHERE providerId IN (:ids) ORDER BY priority ASC")
    suspend fun watchProviders(ids: List<Int>): List<WatchProviderEntity>

    @Query("SELECT * FROM watch_providers ORDER BY priority ASC")
    suspend fun semuaWatchProviders(): List<WatchProviderEntity>

    /** Sepotong daftar untuk deret "Tersedia Resmi"; selalu dengan batas. */
    @Query(
        """SELECT * FROM watch_titles
           ORDER BY (year IS NULL), year DESC, (rating IS NULL), rating DESC
           LIMIT :batas""",
    )
    suspend fun watchTerbaru(batas: Int): List<WatchTitleEntity>

    @Upsert
    suspend fun upsertWatchAliases(rows: List<WatchAliasEntity>)

    @Query("SELECT * FROM watch_aliases WHERE movieId = :movieId LIMIT 1")
    suspend fun watchAlias(movieId: String): WatchAliasEntity?

    /**
     * Berapa judul katalog yang menunjuk ke satu TMDB id yang sama.
     *
     * Dipakai untuk tahu apakah sebuah anime berbagi entri TMDB dengan musim
     * lain: AniList memecah per musim, TMDB menyatukannya, jadi 3.817 dari
     * 7.877 anime berbagi id. Tanpa kolom penanda, datanya sendiri yang
     * menjawab — tidak perlu migrasi baru.
     */
    @Query("SELECT COUNT(*) FROM watch_aliases WHERE tmdbId = :tmdbId AND type = :type")
    suspend fun jumlahAliasUntukTmdb(tmdbId: Int, type: String): Int

    @Query("SELECT COUNT(*) FROM watch_aliases")
    suspend fun jumlahWatchAlias(): Int

    @Query("SELECT COUNT(*) FROM watch_titles")
    suspend fun jumlahWatchTitle(): Int

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM watch_titles")
    suspend fun lastWatchSync(): Long

    // --- Daftar film tanpa menahan semuanya di memori --------------------
    //
    // Semua di bawah ini menggantikan operasi atas daftar UTUH di
    // MoviesScreen. Daftar 51.015 film memuncak di 101 MB pada Galaxy A36
    // yang batas heap-nya 256 MB, dan selama masih ada SATU pemakai yang
    // butuh daftar utuh, memori itu tetap terpakai — jadi kesembilan
    // pemakaiannya harus pindah, bukan sebagian.

    @Upsert
    suspend fun upsertMovieGenres(rows: List<MovieGenreEntity>)

    /** Buang baris genre milik film yang sudah tidak ada. */
    @Query("DELETE FROM movie_genres WHERE movieId NOT IN (SELECT id FROM movies)")
    suspend fun pruneMovieGenres()

    @Query("SELECT COUNT(*) FROM movie_genres")
    suspend fun jumlahMovieGenre(): Int

    /**
     * Genre beserta jumlah filmnya, hanya yang mencapai [batas].
     * Menggantikan pengelompokan seluruh daftar di memori.
     */
    @Query(
        """SELECT mg.genre AS genre, COUNT(*) AS jumlah
           FROM movie_genres mg JOIN movies m ON m.id = mg.movieId
           WHERE (:serial = 0 AND m.type <> 'series')
              OR (:serial = 1 AND m.type = 'series')
           GROUP BY mg.genre
           HAVING COUNT(*) >= :batas
           ORDER BY jumlah DESC, mg.genre COLLATE NOCASE""",
    )
    suspend fun genreTeratas(serial: Int, batas: Int): List<GenreJumlah>

    /**
     * Isi satu deret genre. Urutannya menyamai perbandingan lama:
     * tahun terbaru dulu, lalu rating tertinggi; nilai null jatuh ke belakang.
     */
    @Query(
        """SELECT m.* FROM movies m JOIN movie_genres mg ON mg.movieId = m.id
           WHERE mg.genre = :genre
             AND ((:serial = 0 AND m.type <> 'series')
               OR (:serial = 1 AND m.type = 'series'))
           ORDER BY (m.releaseYear IS NULL), m.releaseYear DESC,
                    (m.rating IS NULL), m.rating DESC
           LIMIT :batas""",
    )
    suspend fun filmPerGenre(genre: String, serial: Int, batas: Int): List<MovieEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM movies WHERE type = 'series')")
    fun adaSerial(): Flow<Boolean>

    @Query(
        """SELECT DISTINCT releaseYear FROM movies
           WHERE releaseYear IS NOT NULL ORDER BY releaseYear DESC""",
    )
    suspend fun tahunTersedia(): List<Int>

    @Query(
        """SELECT * FROM movies WHERE type <> 'series' AND rating > 0
           ORDER BY rating DESC, (releaseYear IS NULL), releaseYear DESC LIMIT :batas""",
    )
    suspend fun ratingTertinggi(batas: Int): List<MovieEntity>

    @Query(
        """SELECT * FROM movies WHERE type <> 'series' AND releaseYear = :tahun
           ORDER BY (rating IS NULL), rating DESC LIMIT :batas""",
    )
    suspend fun filmTahun(tahun: Int, batas: Int): List<MovieEntity>

    // --- Halaman untuk grid; tidak pernah memuat seluruh hasil -----------
    //
    // Urutannya dipertahankan sama dengan movies(): updatedAt DESC lalu judul,
    // supaya tampilan tidak berubah dari yang sudah dipakai pengguna.

    /**
     * Satu kueri berparameter untuk filter semua/Film/Serial, DIGABUNG dengan
     * pencarian dan saringan tahun. Dibuat berparameter daripada belasan
     * varian supaya urutan dan semantiknya tidak bisa melenceng antar varian.
     *
     * `tipe`: "" semua, "movie" bukan serial, "series" serial saja.
     * `tahun`: null berarti tanpa saringan tahun.
     *
     * Pencarian mencakup judul DAN sinopsis. Pencarian per-genre yang dulu ada
     * di memori TIDAK ditiru di sini — chip genre sudah menyediakan jalan yang
     * lebih tepat, dan menambah JOIN ke setiap halaman membuatnya mahal.
     */
    @Query(
        """SELECT * FROM movies
           WHERE (:kueri = '' OR title LIKE '%' || :kueri || '%'
                             OR overview LIKE '%' || :kueri || '%')
             AND (:tahun IS NULL OR releaseYear = :tahun)
             AND (:tipe = ''
               OR (:tipe = 'movie' AND type <> 'series')
               OR (:tipe = 'series' AND type = 'series'))
           ORDER BY updatedAt DESC, title COLLATE NOCASE""",
    )
    fun halamanUmum(kueri: String, tahun: Int?, tipe: String): PagingSource<Int, MovieEntity>

    @Query(
        """SELECT m.* FROM movies m JOIN movie_genres mg ON mg.movieId = m.id
           WHERE mg.genre = :genre
             AND ((:serial = 0 AND m.type <> 'series')
               OR (:serial = 1 AND m.type = 'series'))
             AND (:kueri = '' OR m.title LIKE '%' || :kueri || '%'
                              OR m.overview LIKE '%' || :kueri || '%')
             AND (:tahun IS NULL OR m.releaseYear = :tahun)
           ORDER BY m.updatedAt DESC, m.title COLLATE NOCASE""",
    )
    fun halamanGenre(
        genre: String,
        serial: Int,
        kueri: String,
        tahun: Int?,
    ): PagingSource<Int, MovieEntity>

    /** Urutannya beda (rating dulu), jadi tidak bisa digabung ke halamanUmum. */
    @Query(
        """SELECT * FROM movies
           WHERE type <> 'series' AND rating > 0
             AND (:kueri = '' OR title LIKE '%' || :kueri || '%'
                              OR overview LIKE '%' || :kueri || '%')
             AND (:tahun IS NULL OR releaseYear = :tahun)
           ORDER BY rating DESC, title COLLATE NOCASE""",
    )
    fun halamanRating(kueri: String, tahun: Int?): PagingSource<Int, MovieEntity>

    /** Jumlah film; menggantikan `movies.isNotEmpty()` di layar. */
    @Query("SELECT COUNT(*) FROM movies")
    fun jumlahFilmAlir(): Flow<Int>

    // Hitungan yang SEPADAN dengan ketiga kueri halaman di atas. Perlu karena
    // `itemCount` milik Paging hanya menghitung yang sudah dimuat, sementara
    // label "N film" harus menyebut seluruh yang cocok — kalau tidak, labelnya
    // berubah sendiri sambil pengguna menggulir.

    @Query(
        """SELECT COUNT(*) FROM movies
           WHERE (:kueri = '' OR title LIKE '%' || :kueri || '%'
                             OR overview LIKE '%' || :kueri || '%')
             AND (:tahun IS NULL OR releaseYear = :tahun)
             AND (:tipe = ''
               OR (:tipe = 'movie' AND type <> 'series')
               OR (:tipe = 'series' AND type = 'series'))""",
    )
    suspend fun jumlahUmum(kueri: String, tahun: Int?, tipe: String): Int

    @Query(
        """SELECT COUNT(*) FROM movies m JOIN movie_genres mg ON mg.movieId = m.id
           WHERE mg.genre = :genre
             AND ((:serial = 0 AND m.type <> 'series')
               OR (:serial = 1 AND m.type = 'series'))
             AND (:kueri = '' OR m.title LIKE '%' || :kueri || '%'
                              OR m.overview LIKE '%' || :kueri || '%')
             AND (:tahun IS NULL OR m.releaseYear = :tahun)""",
    )
    suspend fun jumlahGenre(genre: String, serial: Int, kueri: String, tahun: Int?): Int

    @Query(
        """SELECT COUNT(*) FROM movies
           WHERE type <> 'series' AND rating > 0
             AND (:kueri = '' OR title LIKE '%' || :kueri || '%'
                              OR overview LIKE '%' || :kueri || '%')
             AND (:tahun IS NULL OR releaseYear = :tahun)""",
    )
    suspend fun jumlahRating(kueri: String, tahun: Int?): Int

}
