package com.bytify.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bytify.app.ui.theme.BytifyColors

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Benar bila layar sedang lanskap; video lalu ditampilkan layar penuh. */
@Composable
fun isLandscape(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * Mengikuti rotasi sensor selama layar video terbuka, menyembunyikan status/navigation bar saat
 * lanskap, dan mengembalikan orientasi aplikasi (potret) ketika layar video ditutup.
 */
@Composable
fun VideoWindowEffect(fullscreen: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { activity.requestedOrientation = previous }
    }
    DisposableEffect(activity, fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Beralih potret ⇄ lanskap dari tombol layar penuh PlayerView. */
fun toggleFullscreen(context: Context, toFullscreen: Boolean) {
    val activity = context.findActivity() ?: return
    activity.requestedOrientation = if (toFullscreen) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

/** Pesan error pemutaran yang bisa dimengerti pengguna. */
fun PlaybackException.friendlyMessage(live: Boolean): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Koneksi internet terputus atau terlalu lambat."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        if (live) "Server siaran menolak permintaan (kanal mungkin sedang offline)." else "Server video menolak permintaan."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Video tidak ditemukan di server sumber."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Format stream tidak didukung pemutar."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Perangkat tidak bisa mendekode video ini."
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Siaran tertinggal dari posisi live. Coba muat ulang."
    else -> "Video gagal diputar ($errorCodeName)."
}

/**
 * Area video Media3: kontrol bawaan (seek, subtitle, layar penuh), indikator buffering,
 * dan panel error dengan tombol coba lagi. Mengisi layar saat [fullscreen].
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoStage(
    player: ExoPlayer,
    fullscreen: Boolean,
    buffering: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showSubtitleButton: Boolean = true,
    /**
     * Siaran langsung: garis waktu dan tombol lompat disembunyikan. Siaran
     * tidak punya durasi untuk digeser, dan menampilkannya membuat kanal TV
     * terasa seperti video rekaman biasa.
     */
    live: Boolean = false,
) {
    val context = LocalContext.current
    val box = if (fullscreen) modifier.fillMaxSize() else modifier.fillMaxWidth().aspectRatio(16f / 9f)
    Box(box.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShowSubtitleButton(showSubtitleButton)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(!live)
                    setShowRewindButton(!live)
                    keepScreenOn = true
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.setShowFastForwardButton(!live)
                view.setShowRewindButton(!live)
                view.setFullscreenButtonState(fullscreen)
                view.setFullscreenButtonClickListener { toFullscreen -> toggleFullscreen(context, toFullscreen) }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (buffering && error == null) CircularProgressIndicator(color = BytifyColors.Gold)
        if (error != null) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("  Coba lagi")
                }
            }
        }
    }
}
