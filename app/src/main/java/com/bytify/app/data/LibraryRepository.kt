package com.bytify.app.data

import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.PlayEventEntity
import com.bytify.app.data.db.PlaylistEntity
import com.bytify.app.data.db.PlaylistSongEntity
import com.bytify.app.data.db.SongEntity
import com.bytify.app.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Koleksi lokal: favorit, playlist, riwayat & statistik putar. */
class LibraryRepository(private val dao: BytifyDao) {

    val likedSongs: Flow<List<Song>> = dao.likedSongs().map { list -> list.map { it.toSong() } }
    val likedIds: Flow<Set<String>> = dao.likedIds().map { it.toSet() }
    val playlists = dao.playlists()
    val history = dao.history(HISTORY_LIMIT)

    fun recentSongs(limit: Int): Flow<List<Song>> =
        dao.recentSongs(limit).map { list -> list.map { it.toSong() } }

    fun topSongs(since: Long, limit: Int = 50) = dao.topSongs(since, limit)
    fun topArtists(since: Long, limit: Int = 20) = dao.topArtists(since, limit)
    fun totalPlayTime(since: Long) = dao.totalPlayTime(since)
    fun forgottenFavorites(before: Long, limit: Int = 20) = dao.forgottenFavorites(before, limit)

    /** Lagu berdasarkan id, mempertahankan urutan [ids]. */
    fun songsByIds(ids: List<String>): Flow<List<Song>> = dao.songsByIds(ids).map { list ->
        val byId = list.associateBy { it.id }
        ids.mapNotNull { byId[it]?.toSong() }
    }

    fun playlist(id: Long) = dao.playlist(id)
    fun playlistSongs(id: Long): Flow<List<Song>> =
        dao.playlistSongs(id).map { list -> list.map { it.toSong() } }

    suspend fun saveSong(song: Song) = dao.saveSong(song.toEntity())

    suspend fun toggleLike(song: Song, liked: Boolean) {
        saveSong(song)
        dao.setLiked(song.id, if (liked) null else System.currentTimeMillis())
    }

    /** @return id entri riwayat, untuk memperbarui durasinya nanti. */
    suspend fun recordPlay(song: Song, startedAt: Long, playTimeMs: Long): Long {
        saveSong(song)
        return dao.insertEvent(PlayEventEntity(songId = song.id, timestamp = startedAt, playTimeMs = playTimeMs))
    }

    suspend fun updatePlayTime(eventId: Long, playTimeMs: Long) = dao.updateEventPlayTime(eventId, playTimeMs)

    suspend fun deleteHistoryEntry(eventId: Long) = dao.deleteEvent(eventId)
    suspend fun clearHistory() = dao.clearHistory()

    suspend fun createPlaylist(name: String): Long = dao.insertPlaylist(PlaylistEntity(name = name.trim()))
    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name.trim())
    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    /** @return false jika lagu sudah ada di playlist. */
    suspend fun addToPlaylist(playlistId: Long, song: Song): Boolean {
        saveSong(song)
        val position = dao.nextPosition(playlistId)
        return dao.insertPlaylistSong(PlaylistSongEntity(playlistId, song.id, position)) != -1L
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: String) =
        dao.removeFromPlaylist(playlistId, songId)

    private fun Song.toEntity() = SongEntity(id, title, artist, thumbnailUrl, durationSec)

    companion object {
        private const val HISTORY_LIMIT = 500
    }
}
