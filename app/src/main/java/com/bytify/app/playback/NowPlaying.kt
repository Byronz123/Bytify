package com.bytify.app.playback

import android.graphics.Bitmap
import com.bytify.app.data.model.Song

/** Ringkasan pemutaran untuk widget layar utama & Dynamic Island. */
data class NowPlaying(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val artwork: Bitmap? = null,
)
