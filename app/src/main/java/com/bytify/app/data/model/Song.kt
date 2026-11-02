package com.bytify.app.data.model

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Lagu di Bytify. [id] adalah videoId YouTube, atau `search:<kueri>` untuk
 * lagu dari tangga lagu yang baru dicari ke YouTube saat diputar.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSec: Long? = null,
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uriFor(id))
        .setCustomCacheKey(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setExtras(Bundle().apply { durationSec?.let { putLong(EXTRA_DURATION, it) } })
                .build(),
        )
        .build()

    companion object {
        const val SEARCH_PREFIX = "search:"
        private const val EXTRA_DURATION = "durationSec"

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
            return Song(
                id = item.mediaId,
                title = meta.title?.toString().orEmpty(),
                artist = meta.artist?.toString().orEmpty(),
                thumbnailUrl = meta.artworkUri?.toString(),
                durationSec = meta.extras?.getLong(EXTRA_DURATION)?.takeIf { it > 0 },
            )
        }
    }
}

/** Satu baris lirik; [timeMs] null untuk lirik tanpa sinkronisasi. */
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)
