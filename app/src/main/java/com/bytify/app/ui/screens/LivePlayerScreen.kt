package com.bytify.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Groups
import com.bytify.app.data.together.Tontonan
import com.bytify.app.data.together.TogetherState
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bytify.app.data.live.LiveChannel
import com.bytify.app.ui.ModeFloating
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.VideoStage
import com.bytify.app.ui.components.VideoWindowEffect
import com.bytify.app.ui.components.friendlyMessage
import com.bytify.app.ui.components.isLandscape
import com.bytify.app.ui.components.toggleFullscreen

@Composable
fun LivePlayerScreen(channelId: String) {
    val app = appState()
    val channel by app.container.live.channel(channelId).collectAsState(initial = null)
    val item = channel
    // Pengguna boleh tetap mencoba kanal yang statusnya lama/offline.
    var forcePlay by rememberSaveable(channelId) { mutableStateOf(false) }

    if (item == null) {
        BackHandler { app.navController.popBackStack() }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Kanal tidak ditemukan di cache.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { app.navController.popBackStack() }, modifier = Modifier.padding(top = 12.dp)) { Text("Kembali") }
        }
        return
    }

    // Gerbang status DIHAPUS. Ambangnya 10 menit sementara katalog kanal
    // statis dan paling cepat disegarkan tiap tiga jam, jadi peringatan
    // "status sudah lama tidak diperiksa" muncul hampir setiap kali kanal
    // dibuka. Statusnya sendiri tidak pernah berarti: isAvailable selalu true
    // dari server, jadi gerbang itu hanya menghalangi tanpa menjawab apa pun.
    // Kalau alirannya memang mati, pemutar sudah menampilkan galat dan tombol
    // coba lagi — itu sinyal yang jujur.
    LiveVideoPlayer(item)
}

@Composable
private fun LiveVideoPlayer(channel: LiveChannel) {
    val app = appState()
    val context = LocalContext.current
    val fullscreen = isLandscape()
    // Selama layar ini hidup, sistem boleh menjatuhkan aplikasi ke jendela
    // floating saat pengguna keluar.
    val floating by ModeFloating.aktif.collectAsState()
    // Nonton Bareng: host mengumumkan kanal yang dibuka, tamu ikut berpindah.
    // Siaran langsung memang satu momen yang sama untuk semua penonton, jadi
    // untuk Live TV inilah sinkronisasi yang sesungguhnya — tanpa koreksi apa pun.
    val together = app.container.together
    val statusRoom by together.state.collectAsState()
    val hostRoom = statusRoom is TogetherState.InRoom && !together.isGuest
    LaunchedEffect(channel.id, hostRoom) {
        if (hostRoom) together.bagikanTontonan(Tontonan("live", channel.id, judul = channel.name))
    }

    var buffering by remember(channel.id) { mutableStateOf(true) }
    var playbackError by remember(channel.id) { mutableStateOf<String?>(null) }
    var isPlaying by remember(channel.id) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPlaybackError by rememberUpdatedState(playbackError)

    VideoWindowEffect(fullscreen)
    BackHandler { if (fullscreen) toggleFullscreen(context, false) else app.navController.popBackStack() }

    val player = remember(channel.id, channel.streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setMediaItem(
                MediaItem.Builder()
                    .setMediaId(channel.id)
                    .setUri(channel.streamUrl)
                    .setMimeType(if (channel.streamType == "hls") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
                    // Mulai sedekat mungkin dengan siaran langsung.
                    .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build())
                    .build(),
            )
            playWhenReady = true
            prepare()
        }
    }

    LaunchedEffect(player) { app.container.player.pause() } // audio musik tidak bercampur

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) playbackError = null
            }
            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                playbackError = error.friendlyMessage(live = true)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        var resumeAfterForeground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeAfterForeground = player.playWhenReady
                    player.pause()
                }
                Lifecycle.Event.ON_START -> {
                    // Siaran langsung: lompat kembali ke posisi live setelah aplikasi dibuka lagi.
                    if (resumeAfterForeground && latestPlaybackError == null) {
                        player.seekToDefaultPosition()
                        player.play()
                    }
                    resumeAfterForeground = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Jendela floating hanya diizinkan saat siaran benar-benar diputar; jendela
    // yang isinya layar "menyangga" atau dijeda tidak bisa disentuh untuk
    // diperbaiki, karena jendela floating Android tidak menerima sentuhan.
    // Penandanya adalah NIAT memutar (playWhenReady), bukan isPlaying.
    // isPlaying berkedip mati-nyala setiap kali aliran menyangga — terbukti di
    // log: "terdaftar" lalu "dilepas" 16 ms kemudian — sehingga jendela
    // floating nyaris tidak pernah sempat diizinkan. playWhenReady bertahan
    // melintasi buffering dan hanya padam kalau pemutaran benar-benar dijeda.
    var bolehFloating by remember(channel.id) { mutableStateOf(false) }
    // Apakah sumbernya memang siaran langsung, menurut pemutarnya sendiri.
    // Sebagian entri iptv-org ternyata berkas berdurasi, bukan aliran langsung;
    // menuliskan "LIVE" untuk keduanya berarti berbohong kepada pengguna, jadi
    // labelnya mengikuti kenyataan.
    var benarLive by remember(channel.id) { mutableStateOf(true) }
    LaunchedEffect(channel.id) {
        while (true) {
            bolehFloating = player.playWhenReady
            benarLive = player.isCurrentMediaItemLive ||
                player.duration == androidx.media3.common.C.TIME_UNSET
            delay(500)
        }
    }
    DisposableEffect(bolehFloating) {
        if (bolehFloating) ModeFloating.daftarkanVideo() else ModeFloating.lepaskanVideo()
        onDispose { ModeFloating.lepaskanVideo() }
    }

    val retry = {
        playbackError = null
        buffering = true
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }

    // Di jendela floating hanya gambarnya yang tersisa: judul, tombol, dan
    // keterangan tidak akan terbaca pada jendela sekecil itu dan hanya memakan
    // ruang gambar.
    if (floating) {
        VideoStage(
            player,
            fullscreen = true,
            buffering = buffering,
            error = playbackError,
            onRetry = retry,
            showSubtitleButton = false,
            live = benarLive,
        )
        return
    }

    if (fullscreen) {
        VideoStage(player, fullscreen = true, buffering = buffering, error = playbackError, onRetry = retry, live = benarLive)
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                Text("${channel.category} · ${channel.country}", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
            }
            if (hostRoom) {
                IconButton(onClick = { together.mulaiBareng() }) {
                    Icon(Icons.Rounded.Groups, contentDescription = "Beri aba-aba nonton bareng", tint = BytifyColors.Gold)
                }
            }
            IconButton(onClick = retry) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Muat ulang siaran", tint = Color.White)
            }
        }

        VideoStage(
            player,
            fullscreen = false,
            buffering = buffering,
            error = playbackError,
            onRetry = retry,
            showSubtitleButton = false,
            live = benarLive,
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = when {
                playbackError != null -> Color(0xFFE57373) to "Gangguan"
                buffering -> Color(0xFFFFC857) to "Menyangga…"
                isPlaying && benarLive -> Color(0xFFE53935) to "LIVE"
                isPlaying -> Color(0xFFFFC857) to "Rekaman"
                else -> Color.Gray to "Dijeda"
            }
            Spacer(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            channel.description.ifBlank { "Siaran langsung dari ${channel.license?.takeIf { it.isNotBlank() } ?: "penyedia kanal"}." },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            color = Color.LightGray,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Putar ponsel ke lanskap untuk layar penuh. Kualitas menyesuaikan koneksi internet.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
