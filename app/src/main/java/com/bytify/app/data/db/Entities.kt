package com.bytify.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bytify.app.data.model.Song

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSec: Long?,
    val likedAt: Long? = null,
) {
    fun toSong() = Song(id, title, artist, thumbnailUrl, durationSec)
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("songId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val position: Int,
)

/**
 * Alarm musik. [days] adalah bitmask Senin (bit 0) .. Minggu (bit 6); 0 berarti
 * sekali saja. [source]: `liked`, `downloaded`, `recent`, atau `playlist:<id>`.
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val source: String = "liked",
    val sourceLabel: String = "Lagu yang disukai",
    val shuffle: Boolean = true,
    val enabled: Boolean = true,
)

/** Satu sesi pemutaran lagu; dasar untuk Riwayat, Statistik, dan Recap. */
@Entity(tableName = "play_events", indices = [Index("songId"), Index("timestamp")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val timestamp: Long,
    val playTimeMs: Long,
)

data class HistoryRow(
    @Embedded(prefix = "s_") val song: SongEntity,
    val timestamp: Long,
    val eventId: Long,
)

data class SongStat(
    @Embedded val song: SongEntity,
    val plays: Int,
    val totalMs: Long,
)

data class ArtistStat(
    val artist: String,
    val thumbnailUrl: String?,
    val plays: Int,
    val totalMs: Long,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val songCount: Int,
    val thumbnails: String?,
) {
    val thumbnailList: List<String> get() = thumbnails?.split('|')?.filter { it.isNotBlank() }.orEmpty()
}
