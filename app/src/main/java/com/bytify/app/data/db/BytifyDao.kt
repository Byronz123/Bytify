package com.bytify.app.data.db

import androidx.room.Dao
import androidx.room.Insert
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
}
