package com.bytify.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.bytify.app.data.movie.EpisodeDto
import com.bytify.app.data.movie.Movie
import com.bytify.app.data.movie.MovieSubtitleDto
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.VideoStage
import com.bytify.app.ui.components.VideoWindowEffect
import com.bytify.app.ui.components.friendlyMessage
import com.bytify.app.ui.components.isLandscape
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun MoviePlayerScreen(movieId: String, episodeId: String?) {
    val app = appState()
    val movie by app.container.movies.movie(movieId).collectAsState(initial = null)
    val item = movie

    if (item == null || !item.isPlayable) {
        BackHandler { app.navController.popBackStack() }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (item == null) "Memuat film…" else "Video belum tersedia untuk judul ini.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { app.navController.popBackStack() }, modifier = Modifier.padding(top = 12.dp)) { Text("Kembali") }
        }
        return
    }
    // Episode yang dipilih bisa berganti tanpa keluar dari layar (episode berikutnya).
    var currentEpisodeId by rememberSaveable(movieId) {
        mutableStateOf(if (item.isSeries) (item.episode(episodeId) ?: item.episodes.firstOrNull())?.id else null)
    }
    MovieVideoContent(item, item.episode(currentEpisodeId), onSelectEpisode = { currentEpisodeId = it.id })
}

private fun subtitleConfigs(subtitles: List<MovieSubtitleDto>) = subtitles.mapNotNull { subtitle ->
    val uri = runCatching { Uri.parse(subtitle.url) }.getOrNull() ?: return@mapNotNull null
    val path = uri.path.orEmpty().lowercase()
    val mimeType = when {
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        path.endsWith(".ssa") || path.endsWith(".ass") -> MimeTypes.TEXT_SSA
        else -> MimeTypes.TEXT_VTT
    }
    MediaItem.SubtitleConfiguration.Builder(uri)
        .setMimeType(mimeType)
        .setLanguage(subtitle.lang.ifBlank { "und" })
        .setLabel(subtitle.label.ifBlank { subtitle.lang.ifBlank { "Subtitle" } })
        // Subtitle Indonesia otomatis aktif bila tersedia.
        .setSelectionFlags(if (subtitle.lang.equals("id", true)) C.SELECTION_FLAG_DEFAULT else 0)
        .build()
}

@Composable
private fun MovieVideoContent(movie: Movie, episode: EpisodeDto?, onSelectEpisode: (EpisodeDto) -> Unit) {
    val app = appState()
    val context = LocalContext.current
    val repository = app.container.movies
    val lifecycleOwner = LocalLifecycleOwner.current
    val fullscreen = isLandscape()
    val playKey = episode?.id ?: movie.id
    val videoUrl = episode?.videoUrl ?: movie.videoUrl.orEmpty()
    val subtitles = episode?.subtitles?.takeIf { it.isNotEmpty() } ?: if (episode == null) movie.subtitles else emptyList()
    var buffering by remember(playKey) { mutableStateOf(true) }
    var playbackError by remember(playKey) { mutableStateOf<String?>(null) }
    val nextEpisode = episode?.let { current -> movie.episodes.getOrNull(movie.episodes.indexOfFirst { it.id == current.id } + 1) }
    val latestNext by rememberUpdatedState(nextEpisode)

    VideoWindowEffect(fullscreen)
    BackHandler {
        // Saat lanskap, kembali dulu ke potret; tekan lagi untuk keluar.
        if (fullscreen) com.bytify.app.ui.components.toggleFullscreen(context, false) else app.navController.popBackStack()
    }

    // Satu ExoPlayer per layar; dilepas saat layar ditutup (lihat onDispose di bawah).
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage("id")
                .build()
        }
    }

    fun durationOf(): Long = exoPlayer.duration.takeIf { it > 0L }
        ?: ((episode?.durationMin ?: movie.durationMin)?.toLong()?.times(60_000L)) ?: 0L

    fun savePosition(position: Long = exoPlayer.currentPosition.coerceAtLeast(0L), duration: Long = durationOf()) {
        if (position <= 0L) return
        app.container.appScope.launch { repository.savePlaybackPosition(movie, position, duration, episode) }
    }

    // Ganti media saat episode berganti, lalu lanjutkan dari posisi terakhir.
    LaunchedEffect(playKey) {
        app.container.player.pause() // musik tidak bercampur dengan audio film
        exoPlayer.setMediaItem(
            MediaItem.Builder()
                .setMediaId(playKey)
                .setUri(videoUrl)
                .setSubtitleConfigurations(subtitleConfigs(subtitles))
                .build(),
        )
        exoPlayer.prepare()
        val saved = repository.playbackHistory(playKey)
        if (saved != null && saved.positionMs > 5_000L) {
            val nearEnd = saved.durationMs > 0L && saved.positionMs >= saved.durationMs - 15_000L
            if (!nearEnd) exoPlayer.seekTo(saved.positionMs)
        }
        exoPlayer.playWhenReady = true
    }

    // Simpan posisi berkala selama diputar.
    LaunchedEffect(playKey) {
        while (isActive) {
            delay(10_000L)
            if (exoPlayer.isPlaying) savePosition()
        }
    }

    // Dideklarasikan sebelum listener: efek dilepas terbalik, jadi posisi tersimpan dulu baru player dilepas.
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    DisposableEffect(exoPlayer, playKey) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) playbackError = null
                if (playbackState == Player.STATE_ENDED) {
                    val duration = durationOf()
                    savePosition(duration, duration)
                    latestNext?.let(onSelectEpisode) // putar episode berikutnya otomatis
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                playbackError = error.friendlyMessage(live = false)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            savePosition()
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resume = exoPlayer.playWhenReady
                    savePosition()
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_START -> if (resume) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val retry = {
        playbackError = null
        buffering = true
        exoPlayer.prepare()
        exoPlayer.play()
    }

    if (fullscreen) {
        VideoStage(exoPlayer, fullscreen = true, buffering = buffering, error = playbackError, onRetry = retry)
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(movie.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    episode?.let { "S${it.season} · E${it.episode} · ${it.title}" } ?: "Putar ke lanskap untuk layar penuh",
                    style = MaterialTheme.typography.labelSmall, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (nextEpisode != null) {
                IconButton(onClick = { savePosition(); onSelectEpisode(nextEpisode) }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Episode berikutnya", tint = Color.White)
                }
            }
        }

        VideoStage(exoPlayer, fullscreen = false, buffering = buffering, error = playbackError, onRetry = retry)

        if (movie.isSeries) {
            Text(
                "Episode",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 6.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(movie.episodes, key = { it.id }) { ep ->
                    val active = ep.id == episode?.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !active) { savePosition(); onSelectEpisode(ep) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow, null,
                            tint = if (active) BytifyColors.Gold else Color.Gray,
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                ep.title,
                                color = if (active) BytifyColors.Gold else Color.White,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull("S${ep.season} E${ep.episode}", ep.durationMin?.let { "$it mnt" }, if (ep.subtitles.isNotEmpty()) "CC" else null).joinToString(" · "),
                                color = Color.Gray, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                movie.title,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (subtitles.isEmpty()) "Subtitle belum tersedia dari sumber untuk judul ini."
                else "Pilih subtitle lewat tombol CC di pemutar. Posisi tontonan disimpan otomatis.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
