package com.bytify.app.data.db

import com.bytify.app.data.model.MediaKind
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
    /** Alamat audio langsung untuk episode podcast (null untuk musik). */
    val streamUrl: String? = null,
    /** [MediaKind] dalam bentuk teks; null = musik. */
    val kind: String? = null,
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationSec = durationSec,
        streamUrl = streamUrl,
        kind = kind?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() } ?: MediaKind.MUSIC,
    )
}

/** Stasiun radio yang disimpan pengguna. */
@Entity(tableName = "radio_favorites")
data class RadioFavoriteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val streamUrl: String,
    val favicon: String?,
    val tags: String,
    val country: String,
    val hls: Boolean,
    val savedAt: Long = System.currentTimeMillis(),
)

/** Podcast yang diikuti. [lastOpenedAt] untuk menandai episode baru. */
@Entity(tableName = "podcast_subscriptions")
data class PodcastSubscriptionEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val feedUrl: String,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
)

/** Posisi terakhir sebuah episode, agar bisa dilanjutkan. */
@Entity(tableName = "episode_progress")
data class EpisodeProgressEntity(
    @PrimaryKey val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs - FINISHED_MARGIN_MS

    companion object {
        const val FINISHED_MARGIN_MS = 30_000L
    }
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
