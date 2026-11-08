package com.bytify.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
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
import androidx.compose.ui.viewinterop.AndroidView
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
import com.bytify.app.ui.components.toggleFullscreen
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

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
        .setSelectionFlags(if (subtitle.lang.equals("id", true)) C.SELECTION_FLAG_DEFAULT else 0)
        .build()
}

private fun isEmbedStreamUrl(url: String): Boolean {
    val lower = url.lowercase()
    // Direct media → selalu gunakan ExoPlayer
    if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
        lower.endsWith(".m3u8") || lower.contains(".m3u8?") ||
        lower.contains("/hls/") || lower.contains("/master.m3u8")
    ) return false

    // Junk URL → biarkan ExoPlayer gagal, lalu tampilkan error
    if (lower.contains("stopjudi") || lower.contains("donasi.assetsy") ||
        lower.contains("judol") || lower.contains("slot-gacor") ||
        lower.contains("anti-judi")
    ) return false

    // Embed player → gunakan WebView
    return lower.contains("/api/lk21/resolve/") ||
        lower.contains("embed") ||
        lower.contains("videonode") ||
        lower.contains("hydrax") ||
        lower.contains("turbovid") ||
        lower.contains("castlehoud") ||
        lower.contains("filemoon") ||
        lower.contains("streamwish") ||
        lower.contains("vidhide") ||
        lower.contains("doodstream") ||
        lower.contains("iframe")
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
    var useEmbedPlayer by remember(playKey, videoUrl) { mutableStateOf(isEmbedStreamUrl(videoUrl)) }
    val nextEpisode = episode?.let { current -> movie.episodes.getOrNull(movie.episodes.indexOfFirst { it.id == current.id } + 1) }
    val latestNext by rememberUpdatedState(nextEpisode)

    VideoWindowEffect(fullscreen)
    BackHandler {
        if (fullscreen) toggleFullscreen(context, false) else app.navController.popBackStack()
    }

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

    LaunchedEffect(playKey, useEmbedPlayer) {
        app.container.player.pause()
        if (useEmbedPlayer) {
            exoPlayer.stop()
            repository.savePlaybackPosition(movie, 10_000L, durationOf().coerceAtLeast(60_000L), episode)
            return@LaunchedEffect
        }
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

    LaunchedEffect(playKey, useEmbedPlayer) {
        if (useEmbedPlayer) return@LaunchedEffect
        while (isActive) {
            delay(10_000L)
            if (exoPlayer.isPlaying) savePosition()
        }
    }

    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    DisposableEffect(exoPlayer, playKey) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) playbackError = null
                if (playbackState == Player.STATE_ENDED) {
                    val duration = durationOf()
                    savePosition(duration, duration)
                    latestNext?.let(onSelectEpisode)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                if (videoUrl.contains("/api/lk21/") ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                ) {
                    useEmbedPlayer = true
                } else {
                    playbackError = error.friendlyMessage(live = false)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            if (!useEmbedPlayer) savePosition()
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resume = exoPlayer.playWhenReady
                    if (!useEmbedPlayer) savePosition()
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_START -> if (resume && !useEmbedPlayer) exoPlayer.play()
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
        if (useEmbedPlayer) {
            EmbedVideoStage(videoUrl = videoUrl, fullscreen = true, onToggleFullscreen = { toggleFullscreen(context, false) })
        } else {
            VideoStage(exoPlayer, fullscreen = true, buffering = buffering, error = playbackError, onRetry = retry)
        }
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
                IconButton(onClick = { if (!useEmbedPlayer) savePosition(); onSelectEpisode(nextEpisode) }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Episode berikutnya", tint = Color.White)
                }
            }
        }

        if (useEmbedPlayer) {
            EmbedVideoStage(videoUrl = videoUrl, fullscreen = false, onToggleFullscreen = { toggleFullscreen(context, true) })
        } else {
            VideoStage(exoPlayer, fullscreen = false, buffering = buffering, error = playbackError, onRetry = retry)
        }

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
                            .clickable(enabled = !active) { if (!useEmbedPlayer) savePosition(); onSelectEpisode(ep) }
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
                if (useEmbedPlayer) "Mode pemutar embed aktif. Putar perangkat ke lanskap atau ketuk ikon layar penuh di pojok video."
                else if (subtitles.isEmpty()) "Subtitle belum tersedia dari sumber untuk judul ini."
                else "Pilih subtitle lewat tombol CC di pemutar. Posisi tontonan disimpan otomatis.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbedVideoStage(
    videoUrl: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    // ---- Daftar domain iklan yang komprehensif ----
    val adDomains = remember {
        setOf(
            // Ad Networks
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com",
            "popads.net", "popcash.net", "popunder.net",
            "propellerads.com", "propellerclick.com",
            "adsterra.com", "adsterratools.com",
            "exoclick.com", "exosrv.com",
            "juicyads.com", "onclickads.net",
            "hilltopads.com", "hilltopads.net",
            "clickadu.com", "vfrfrdc.com",
            "trafficjunky.com", "trafficfactory.biz",
            "a-ads.com", "adcash.com", "ad-maven.com",
            "monetag.com", "outbrain.com", "taboola.com",
            "revcontent.com", "mgid.com",
            // Tracking
            "histats.com", "statcounter.com", "hotjar.com",
            "facebook.net", "scorecardresearch.com", "quantserve.com",
            // Indonesian gambling/junk
            "donasi.assetsy.de",
            // Popup/redirect
            "notifpush.com", "pushnotificationads.com",
            "admaven.com",
        )
    }

    // ---- JavaScript untuk membersihkan elemen pengganggu ----
    val cleanupJs = remember {
        """
        (function() {
            'use strict';
            var sels = [
                '[class*="popup"]','[class*="overlay"]','[class*="modal"]',
                '[id*="popup"]','[id*="overlay"]','[id*="modal"]',
                '[class*="banner"]','[class*="advert"]','[class*="sponsor"]',
                '[id*="banner"]','[id*="advert"]','[id*="sponsor"]',
                '[class*="stopjudi"]','[class*="anti-judi"]','[class*="judol"]',
                '[id*="stopjudi"]','[id*="anti-judi"]','[id*="judol"]',
                '.ads','.ad-container','.ad-wrapper','.ad-box',
                '#ads','#ad-container','#ad-wrapper',
                '[style*="position: fixed"][style*="z-index"]',
                '[style*="position:fixed"][style*="z-index"]',
                'iframe[src*="ads"]','iframe[src*="banner"]',
                'iframe[src*="popup"]','iframe[src*="click"]'
            ];
            function clean() {
                sels.forEach(function(s) {
                    try {
                        document.querySelectorAll(s).forEach(function(el) {
                            if (el.tagName==='VIDEO'||el.id==='player'||
                                el.classList.contains('video-js')||
                                el.classList.contains('jwplayer')||
                                el.classList.contains('plyr')) return;
                            el.remove();
                        });
                    } catch(e) {}
                });
                document.querySelectorAll('*').forEach(function(el) {
                    var st = window.getComputedStyle(el);
                    if (st.position==='fixed' && parseInt(st.zIndex)>100 &&
                        el.tagName!=='VIDEO' && !el.querySelector('video')) {
                        el.style.display='none';
                    }
                });
            }
            window.open = function() { return null; };
            document.addEventListener('click', function(e) {
                var t = e.target;
                while(t) {
                    if (t.tagName==='VIDEO'||(t.classList&&(
                        t.classList.contains('video-js')||
                        t.classList.contains('jwplayer')||
                        t.classList.contains('plyr')||
                        t.classList.contains('vjs-control-bar')||
                        t.id==='player'))) return;
                    t = t.parentElement;
                }
            }, true);
            var vids = document.querySelectorAll('video');
            vids.forEach(function(v) {
                v.style.width='100%'; v.style.height='100%';
                v.style.objectFit='contain';
            });
            clean();
            setInterval(function() { clean(); window.open=function(){return null;}; }, 2000);
        })();
        """.trimIndent()
    }

    val boxMod = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)

    Box(boxMod.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadsImagesAutomatically = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        cacheMode = WebSettings.LOAD_DEFAULT
                        databaseEnabled = true
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val reqUrl = request?.url?.toString().orEmpty().lowercase()
                            if (adDomains.any { reqUrl.contains(it) }) return true
                            if (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://")) return true
                            if (reqUrl.contains("stopjudi") || reqUrl.contains("judol") ||
                                reqUrl.contains("slot-gacor") || reqUrl.contains("anti-judi") ||
                                reqUrl.contains("donasi.assetsy")
                            ) return true
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty().lowercase()
                            if (adDomains.any { reqUrl.contains(it) }) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            if (reqUrl.contains("stopjudi") || reqUrl.contains("donasi.assetsy") ||
                                reqUrl.contains("judol") || reqUrl.contains("anti-judi")
                            ) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            if (reqUrl.contains("/ads/") || reqUrl.contains("/advert/") ||
                                reqUrl.contains("/banner/") || reqUrl.contains("/popup/")
                            ) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(cleanupJs, null)
                        }
                    }
                    loadUrl(videoUrl)
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
            modifier = Modifier.fillMaxSize(),
        )

        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            Icon(
                if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = "Layar penuh",
                tint = Color.White,
            )
        }
    }
}