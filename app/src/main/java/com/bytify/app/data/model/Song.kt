package com.bytify.app.data.model

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

/** Jenis isi yang diputar. */
enum class MediaKind { MUSIC, RADIO, EPISODE }

/**
 * Lagu / siaran / episode di Bytify.
 *
 * - Musik: [id] adalah videoId YouTube, atau `search:<kueri>` untuk lagu dari
 *   tangga lagu yang baru dicari ke YouTube saat diputar.
 * - Radio & episode podcast: [streamUrl] berisi alamat audio langsung.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSec: Long? = null,
    val streamUrl: String? = null,
    val kind: MediaKind = MediaKind.MUSIC,
    /** Stream radio berformat HLS (.m3u8). */
    val hls: Boolean = false,
) {
    val isLive: Boolean get() = kind == MediaKind.RADIO
    val isMusic: Boolean get() = kind == MediaKind.MUSIC

    /** URI yang dibuka pemutar: alamat langsung, atau `bytify://` untuk musik. */
    val playUri: Uri get() = streamUrl?.let(Uri::parse) ?: uriFor(id)

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(playUri)
        .setCustomCacheKey(id)
        .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setMediaType(
                    when (kind) {
                        MediaKind.MUSIC -> MediaMetadata.MEDIA_TYPE_MUSIC
                        MediaKind.RADIO -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
                        MediaKind.EPISODE -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                    },
                )
                .setExtras(
                    Bundle().apply {
                        durationSec?.let { putLong(EXTRA_DURATION, it) }
                        streamUrl?.let { putString(EXTRA_STREAM, it) }
                        putString(EXTRA_KIND, kind.name)
                        putBoolean(EXTRA_HLS, hls)
                    },
                )
                .build(),
        )
        .build()

    companion object {
        const val SEARCH_PREFIX = "search:"
        private const val EXTRA_DURATION = "durationSec"
        private const val EXTRA_STREAM = "streamUrl"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_HLS = "hls"

        fun uriFor(id: String): Uri = Uri.parse("bytify://song/${Uri.encode(id)}")

        fun idFromUri(uri: Uri): String = Uri.decode(uri.lastPathSegment.orEmpty())

        fun pending(title: String, artist: String, thumbnailUrl: String?) = Song(
            id = "$SEARCH_PREFIX$title - $artist",
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
        )

        fun fromMediaItem(item: MediaItem): Song {
            val meta = item.mediaMetadata
            val extras = meta.extras
            return Song(
                id = item.mediaId,
                title = meta.title?.toString().orEmpty(),
                artist = meta.artist?.toString().orEmpty(),
                thumbnailUrl = meta.artworkUri?.toString(),
                durationSec = extras?.getLong(EXTRA_DURATION)?.takeIf { it > 0 },
                streamUrl = extras?.getString(EXTRA_STREAM),
                kind = extras?.getString(EXTRA_KIND)?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
                    ?: MediaKind.MUSIC,
                hls = extras?.getBoolean(EXTRA_HLS) ?: false,
            )
        }

        /**
         * Bangun ulang item dari controller (yang tidak boleh mengirim URI):
         * pakai metadata yang dikirim untuk memulihkan alamat & jenisnya.
         */
        fun restore(item: MediaItem): MediaItem {
            val song = fromMediaItem(item)
            return item.buildUpon()
                .setUri(song.playUri)
                .setCustomCacheKey(item.mediaId)
                .apply { if (song.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
        }
    }
}

/** Satu baris lirik; [timeMs] null untuk lirik tanpa sinkronisasi. */
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)
