package com.bytify.app.data.db

import com.bytify.app.data.model.MediaKind
import androidx.room.Embedded
import androidx.room.ColumnInfo
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

/**
 * Katalog lagu: kumpulan lagu dari tangga Apple Music banyak negara, dipakai
 * sebagai kumpulan kandidat untuk rekomendasi. Terpisah dari tabel `songs`
 * (yang hanya berisi lagu yang pernah disentuh pengguna) supaya statistik dan
 * daftar suka tidak tercemar ribuan baris katalog.
 *
 * [id] memakai skema yang sama dengan [Song.pending] ("search:Judul - Artis"),
 * jadi lagu katalog dan lagu di riwayat putar punya id identik.
 */
@Entity(tableName = "catalog_songs", indices = [Index("artistKey"), Index("rank")])
data class CatalogSongEntity(
    /**
     * Kunci anti-duplikat "judul|artis" yang sudah dinormalkan. Dipakai sebagai
     * kunci utama agar satu lagu yang datang dari beberapa sumber (tangga Apple
     * dan pencarian YouTube) hanya tersimpan sekali.
     */
    @PrimaryKey val kunci: String,
    /** Id yang diputar: "search:Judul - Artis" (Apple) atau id video YouTube. */
    val id: String,
    val title: String,
    val artist: String,
    /** Artis utama yang sudah dinormalkan, untuk mencocokkan selera. */
    val artistKey: String,
    val thumbnailUrl: String?,
    /** Peringkat terbaik di tangga mana pun (1 = puncak): sinyal popularitas. */
    val rank: Int,
    /** Asal data: `apple` (tangga) atau `youtube` (pencarian). */
    val sumber: String,
    val updatedAt: Long,
) {
    fun toSong() = Song(id = id, title = title, artist = artist, thumbnailUrl = thumbnailUrl)
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

/** Metadata film yang di-cache untuk katalog offline dan detail film. */
@Entity(tableName = "movies")
data class MovieEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "movie") val type: String = "movie",
    @ColumnInfo(defaultValue = "[]") val episodesJson: String = "[]",
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseYear: Int?,
    val durationMin: Int?,
    val genresJson: String,
    val rating: Double?,
    val language: String?,
    val videoUrl: String?,
    val trailerUrl: String?,
    val subtitlesJson: String,
    val license: String?,
    /** Halaman sumber film; dipakai sebagai Referer saat memuat embed player. */
    @ColumnInfo(defaultValue = "") val sourceUrl: String? = null,
    /** Daftar server player alternatif, disimpan sebagai JSON. */
    @ColumnInfo(defaultValue = "[]") val serversJson: String = "[]",
    val updatedAt: Long,
)

/** Status favorit film disimpan terpisah dari favorit musik. */
@Entity(tableName = "movie_favorites")
data class MovieFavoriteEntity(
    @PrimaryKey val movieId: String,
    val savedAt: Long,
)

/** Posisi terakhir tontonan; digunakan oleh fitur lanjut menonton. */
@Entity(tableName = "movie_history")
data class MovieHistoryEntity(
    @PrimaryKey val movieId: String,
    val title: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** Grup katalog dari field `sections` dalam respons JSON. */
@Entity(tableName = "movie_sections")
data class MovieSectionEntity(
    @PrimaryKey val title: String,
    val movieIdsJson: String,
    val updatedAt: Long,
)


/** Kanal live TV dari katalog server; URL hanya berasal dari penyedia yang berizin. */
@Entity(tableName = "live_channels", indices = [Index(value = ["category"]), Index(value = ["isAvailable"])])
data class LiveChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val logoUrl: String?,
    val category: String,
    val country: String,
    val language: String,
    val streamUrl: String,
    val streamType: String,
    val isAvailable: Boolean,
    val statusMessage: String?,
    val checkedAtEpochMs: Long,
    val license: String?,
    val updatedAt: Long,
)

/** Favorit kanal disimpan terpisah dari favorit musik dan film. */
@Entity(tableName = "live_favorites")
data class LiveFavoriteEntity(
    @PrimaryKey val channelId: String,
    val savedAt: Long,
)

/**
 * Ketersediaan RESMI satu judul di Indonesia (data JustWatch lewat TMDB).
 *
 * Tabel ini SENGAJA berdiri sendiri, bukan kolom baru di [MovieEntity]. Dua
 * alasannya:
 *
 * 1. Isinya bukan sesuatu yang bisa diputar di dalam aplikasi — ia menunjuk ke
 *    layanan sah dan dibuka di luar lewat intent. Menaruhnya di `movies` akan
 *    membuat pemutar menyangka ada yang bisa dimuat.
 * 2. `movies` dibaca utuh ke memori oleh StateFlow daftar besar. Menambah
 *    puluhan ribu baris lagi ke sana adalah jalan yang sudah pernah
 *    mematikan aplikasi dengan OutOfMemoryError di Galaxy A36.
 *
 * Penjodohannya lewat [tmdbId], yang diturunkan dari id film ("tmdb-<id>"),
 * jadi film Indonesia yang sudah ada di katalog pun ikut dapat chip-nya tanpa
 * perlu kolom baru di `movies`.
 */
@Entity(
    tableName = "watch_titles",
    indices = [Index(value = ["tmdbId"]), Index(value = ["type"])],
)
data class WatchTitleEntity(
    @PrimaryKey val id: String,
    /** Kunci penjodohan dengan katalog film yang sudah ada. */
    val tmdbId: Int,
    /** "movie" atau "tv"; TMDB memakai ruang id terpisah untuk keduanya. */
    val type: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val overview: String,
    val genresJson: String,
    val rating: Double?,
    val runtimeMin: Int?,
    /**
     * Peta jenis langganan -> daftar id penyedia, misalnya
     * `{"flatrate":[8,159],"rent":[2]}`. Disimpan sebagai JSON karena jumlah
     * jenisnya tetap kecil dan hanya dibaca di halaman detail.
     */
    val watchJson: String,
    /** Tautan resmi halaman tonton; dibuka di luar aplikasi. */
    val watchLink: String?,
    val updatedAt: Long,
)

/** Kamus penyedia sah: id -> nama & logo, agar namanya tidak diulang per judul. */
@Entity(tableName = "watch_providers")
data class WatchProviderEntity(
    @PrimaryKey val providerId: Int,
    val name: String,
    val logoPath: String?,
    /** Urutan tampil dari TMDB; makin kecil makin diutamakan. */
    val priority: Int?,
)

/**
 * Jembatan id katalog -> TMDB id, hasil pencocokan KETAT di tmdb_match.py.
 *
 * Perlu karena 25.186 film LK21 memakai id siput tanpa TMDB id, sementara
 * lapisan ketersediaan dan subtitle keduanya dikunci TMDB id. Tanpa jembatan
 * ini, keduanya tidak pernah menjangkau katalog utama.
 *
 * Hanya kecocokan yang judul ternormalkannya SAMA PERSIS dan tahunnya cocok
 * (±1) yang masuk ke sini; yang ambigu sengaja tidak dipetakan, karena salah
 * pasang lebih buruk daripada tidak pasang.
 */
@Entity(tableName = "watch_aliases")
data class WatchAliasEntity(
    @PrimaryKey val movieId: String,
    val tmdbId: Int,
    val type: String,
)

