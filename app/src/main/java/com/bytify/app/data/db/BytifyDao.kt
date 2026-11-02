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
           durationSec = COALESCE(:durationSec, durationSec) WHERE id = :id""",
    )
    suspend fun updateSongMeta(id: String, title: String, artist: String, thumbnailUrl: String?, durationSec: Long?)

    /** Simpan metadata lagu tanpa menimpa status suka. */
    @Transaction
    suspend fun saveSong(song: SongEntity) {
        if (insertSongIfAbsent(song) == -1L) {
            updateSongMeta(song.id, song.title, song.artist, song.thumbnailUrl, song.durationSec)
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
