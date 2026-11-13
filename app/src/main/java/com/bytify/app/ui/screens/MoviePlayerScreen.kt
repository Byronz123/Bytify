package com.bytify.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
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
import com.bytify.app.data.movie.isJunkMediaUrl
import com.bytify.app.ui.ModeFloating
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.VideoStage
import com.bytify.app.ui.components.VideoWindowEffect
import com.bytify.app.ui.components.friendlyMessage
import com.bytify.app.ui.components.isLandscape
import com.bytify.app.ui.components.toggleFullscreen
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    val path = Uri.parse(url).path.orEmpty().lowercase()
    if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mkv")) return false
    if (isJunkMediaUrl(lower)) return false
    return lower.contains("/api/lk21/resolve/") ||
        lower.contains("/iframe") ||
        lower.contains("embed") ||
        lower.contains("videonode.de") ||
        lower.contains("hydrax") ||
        lower.contains("turbovid") ||
        lower.contains("castlehoud") ||
        lower.contains("filemoon") ||
        lower.contains("streamwish") ||
        lower.contains("vidhide") ||
        lower.contains("dood")
}

private val AD_DOMAINS = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
    "googletagmanager.com", "popads.net", "popcash.net", "popunder.net", "propellerads.com",
    "propellerclick.com", "adsterra.com", "exoclick.com", "exosrv.com", "juicyads.com",
    "onclickads.net", "hilltopads.com", "hilltopads.net", "clickadu.com", "trafficjunky.com",
    "trafficfactory.biz", "a-ads.com", "adcash.com", "ad-maven.com", "admaven.com", "monetag.com",
    "outbrain.com", "taboola.com", "revcontent.com", "mgid.com", "histats.com", "statcounter.com",
    "hotjar.com", "scorecardresearch.com", "quantserve.com", "notifpush.com",
    "pushnotificationads.com", "donasi.assetsy.de",
)

/**
 * Player videonode menolak dimuat sebagai dokumen utama dan mengalihkan ke
 * beranda situs sumber. Di situs aslinya ia hidup di dalam iframe, sehingga
 * permintaannya membawa Referer halaman film dan Sec-Fetch-Dest: iframe.
 * Pembungkus ini meniru konteks itu persis.
 */
private const val EMBED_TAG = "BytifyEmbed"

/**
 * Mengirim sentuhan ke WebView lewat pipeline input Android. Gerbang
 * "Klik Di Mana Saja untuk Memulai Film" hanya lolos oleh sentuhan sungguhan:
 * klik yang dibuat JavaScript punya isTrusted=false dan diabaikan player.
 * Dihentikan begitu film terdeteksi berjalan.
 */
private val WAKTU_SUBTITLE = Regex(
    """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
)

/**
 * Ubah berkas SRT/WebVTT menjadi larik JSON {a: mulai, b: selesai, t: teks}
 * dalam detik. Dipakai karena cue harus disisipkan sebagai data ke dalam
 * dokumen player (lintas-origin, jadi tidak bisa dibaca dari luar).
 */
private fun subtitleKeJson(isi: String): String {
    val cue = StringBuilder("[")
    var pertama = true
    val blok = isi.replace("\r\n", "\n").replace("\uFEFF", "").split(Regex("""\n\s*\n"""))
    for (bagian in blok) {
        val cocok = WAKTU_SUBTITLE.find(bagian) ?: continue
        val g = cocok.groupValues
        fun detik(jam: String, menit: String, dtk: String, mili: String): Double =
            jam.toInt() * 3600 + menit.toInt() * 60 + dtk.toInt() + ("0." + mili.padEnd(3, '0')).toDouble()
        val mulai = detik(g[1], g[2], g[3], g[4])
        val selesai = detik(g[5], g[6], g[7], g[8])
        val teks = bagian.substring(cocok.range.last + 1)
            .trim()
            .replace(Regex("<[^>]+>"), "")
            .lines().joinToString("\n") { it.trim() }
            .trim()
        if (teks.isEmpty()) continue
        val aman = teks.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        if (!pertama) cue.append(',')
        cue.append("{\"a\":").append(mulai).append(",\"b\":").append(selesai)
            .append(",\"t\":\"").append(aman).append("\"}")
        pertama = false
    }
    return cue.append(']').toString()
}

private fun ketukOtomatis(web: WebView, sudahMain: AtomicBoolean, percobaan: Int = 0) {
    if (percobaan >= 4 || sudahMain.get()) return
    web.postDelayed({
        if (!sudahMain.get() && web.width > 0 && web.height > 0) {
            val x = web.width / 2f
            val y = web.height / 2f
            val saat = SystemClock.uptimeMillis()
            val turun = MotionEvent.obtain(saat, saat, MotionEvent.ACTION_DOWN, x, y, 0)
            val naik = MotionEvent.obtain(saat, saat + 70, MotionEvent.ACTION_UP, x, y, 0)
            runCatching {
                web.dispatchTouchEvent(turun)
                web.dispatchTouchEvent(naik)
            }
            turun.recycle()
            naik.recycle()
            ketukOtomatis(web, sudahMain, percobaan + 1)
        }
    }, if (percobaan == 0) 2500L else 3000L)
}

private const val EMBED_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private val embedHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

/**
 * Disuntikkan ke dalam dokumen player (karena dokumennya kita sajikan sendiri,
 * skrip ini berjalan satu origin dengan player).
 *
 * Gerbang "Klik Di Mana Saja untuk Memulai Film" baru terbuka bila popunder
 * iklan berhasil dibuka. Aplikasi memblokir popup, sehingga window.open
 * mengembalikan null dan player menganggap gagal lalu menahan film. Di sini
 * window.open diganti objek jendela tiruan supaya player menganggap iklannya
 * sudah tampil, lalu klik sintetis dikirim untuk melewati gerbangnya.
 */
private const val GATE_BYPASS_JS = """
<script>(function(){
  var jendelaTiruan = {
    closed:false, opener:null, name:'', focus:function(){}, blur:function(){},
    close:function(){ this.closed = true; }, postMessage:function(){},
    moveTo:function(){}, resizeTo:function(){},
    document:{ write:function(){}, writeln:function(){}, close:function(){}, body:{} },
    location:{ href:'about:blank', replace:function(){}, assign:function(){} }
  };
  try {
    Object.defineProperty(window, 'open', {
      value: function(){ return jendelaTiruan; }, writable:false, configurable:false
    });
  } catch (e) { window.open = function(){ return jendelaTiruan; }; }

  function buangLapisanGerbang(){
    var simpul = document.querySelectorAll('div,section,span,a,p');
    for (var i = 0; i < simpul.length; i++) {
      var el = simpul[i];
      var teks = el.textContent || '';
      if (el.querySelector('video')) continue;
      if (/Klik Di Mana Saja|CONTAINS ADS|Memulai Film/i.test(teks) && teks.length < 400) {
        el.style.setProperty('display','none','important');
      }
    }
  }

  function dorong(){
    try {
      buangLapisanGerbang();
      var sasaran = document.querySelector('video') || document.body;
      ['mousedown','mouseup','click'].forEach(function(jenis){
        sasaran.dispatchEvent(new MouseEvent(jenis, {bubbles:true, cancelable:true, view:window}));
      });
      var v = document.querySelector('video');
      if (v && v.paused) { var pr = v.play(); if (pr && pr.catch) pr.catch(function(){}); }
    } catch (e) {}
  }

  [600, 1600, 3000, 5000, 8000].forEach(function(ms){ setTimeout(dorong, ms); });
  document.addEventListener('DOMContentLoaded', dorong);

  // Telusuri instance hls.js untuk mengetahui pilihan resolusi yang tersedia.
  function cariHls(){
    if (window.hls && window.hls.levels) return window.hls;
    for (var k in window) {
      try {
        var o = window[k];
        if (o && typeof o === 'object' && o.levels && o.levels.length &&
            typeof o.currentLevel !== 'undefined') { return o; }
      } catch (e) {}
    }
    return null;
  }
  function laporKualitas(){
    try {
      var h = cariHls();
      if (!h) { console.log('kualitas: instance hls tidak ditemukan'); return; }
      var daftar = h.levels.map(function(l){ return (l.height || '?') + 'p'; }).join(', ');
      console.log('kualitas: jumlah=' + h.levels.length + ' [' + daftar + '] aktif=' + h.currentLevel);
    } catch (e) { console.log('kualitas: gagal ' + e); }
  }
  [7000, 12000].forEach(function(ms){ setTimeout(laporKualitas, ms); });

  // Begitu film benar-benar berjalan, beri tahu aplikasi supaya ketukan
  // otomatis dihentikan (agar tidak malah menjeda film).
  function pantau(){
    try {
      var v = document.querySelector('video');
      if (v && !v.paused && v.currentTime > 0.3) { console.log('BYTIFY_MAIN'); return; }
    } catch (e) {}
    setTimeout(pantau, 700);
  }
  pantau();
  setInterval(buangLapisanGerbang, 800);

  // ---- Penampil subtitle ----
  // Cue dikirim dari aplikasi lewat postMessage karena dokumen ini lintas
  // origin: aplikasi mengambil berkas .srt dengan OkHttp (bebas CORS),
  // menguraikannya, lalu mengirim hasilnya ke sini.
  var cueSub = [];
  var kotakSub = null;
  var dokumenSub = document;
  function siapkanKotak(){
    if (kotakSub && kotakSub.isConnected && kotakSub.ownerDocument === dokumenSub) return kotakSub;
    kotakSub = dokumenSub.createElement('div');
    kotakSub.id = 'bytify-sub';
    kotakSub.style.cssText =
      'position:fixed;left:0;right:0;bottom:7%;z-index:2147483647;text-align:center;' +
      'pointer-events:none;white-space:pre-line;padding:0 5%;' +
      'font:600 4.4vw/1.35 system-ui,-apple-system,Roboto,sans-serif;color:#fff;' +
      'text-shadow:0 2px 4px #000,0 0 10px #000,0 0 3px #000;';
    (dokumenSub.body || dokumenSub.documentElement).appendChild(kotakSub);
    return kotakSub;
  }
  // Player menaruh <video> di frame bersarang. Bila frame itu satu origin,
  // elemennya bisa dijangkau langsung dari sini; bila tidak, frame tersebut
  // menerima cue lewat postMessage dan menggambar sendiri.
  function cariVideo(){
    var v = document.querySelector('video');
    if (v) return v;
    for (var i = 0; i < window.frames.length; i++) {
      try {
        var d = window.frames[i].document;
        var vv = d.querySelector('video');
        if (vv) { dokumenSub = d; return vv; }
      } catch (e) {}
    }
    return null;
  }
  function gambarSub(){
    if (!cueSub.length) { if (kotakSub) kotakSub.textContent = ''; return; }
    var v = cariVideo();
    if (!v) return;
    var t = v.currentTime, teks = '';
    for (var i = 0; i < cueSub.length; i++) {
      if (t >= cueSub[i].a && t <= cueSub[i].b) { teks = cueSub[i].t; break; }
    }
    var k = siapkanKotak();
    if (k.textContent !== teks) k.textContent = teks;
  }
  window.addEventListener('message', function(ev){
    try {
      if (ev.data && ev.data.bytifySub) {
        cueSub = ev.data.bytifySub;
        var alamatFrame = [].slice.call(document.querySelectorAll('iframe'))
          .map(function(f){ return (f.src || 'kosong').slice(0, 70); }).join(' | ');
        console.log('subtitle diterima: ' + cueSub.length + ' baris; video di sini=' +
          document.querySelectorAll('video').length + ' frame anak=' + window.frames.length +
          ' [' + alamatFrame + ']');
        gambarSub();
        // Player kadang menaruh <video> di frame bersarang; teruskan ke sana.
        for (var i = 0; i < window.frames.length; i++) {
          try { window.frames[i].postMessage(ev.data, '*'); } catch (e) {}
        }
      }
    } catch (e) {}
  });
  setInterval(gambarSub, 150);
})();</script>
"""

private fun embedWrapperHtml(url: String): String = """
<!DOCTYPE html>
<html>
<head><meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
/* Posisi absolut dipakai karena tinggi persen tidak teresolusi di dalam
   WebView dan membuat iframe setinggi 0 piksel (layar jadi hitam). */
iframe{position:absolute;top:0;left:0;right:0;bottom:0;width:100%;height:100vh;border:0;display:block}</style>
<script>
// Jaring pengaman: kalau viewport baru mendapat tinggi setelah halaman dimuat,
// ukuran iframe disesuaikan ulang supaya player tidak tertinggal setinggi 0.
function bytifyFit() {
  var f = document.querySelector('iframe');
  var h = window.innerHeight || document.documentElement.clientHeight || 0;
  if (f && h > 0 && f.offsetHeight !== h) { f.style.height = h + 'px'; }
}
window.addEventListener('resize', bytifyFit);
window.addEventListener('load', bytifyFit);
setInterval(bytifyFit, 400);

// Jembatan subtitle: aplikasi memanggil fungsi ini, lalu cue diteruskan ke
// iframe player. Dikirim berulang sebentar karena iframe mungkin belum siap.
var cueTertunda = null, sisaKirim = 0;
window.bytifyKirimSub = function(cue){
  cueTertunda = cue; sisaKirim = 8; teruskanSub();
};
function teruskanSub(){
  if (!cueTertunda || sisaKirim <= 0) return;
  var f = document.querySelector('iframe');
  try { if (f && f.contentWindow) f.contentWindow.postMessage({bytifySub: cueTertunda}, '*'); } catch (e) {}
  sisaKirim--;
  setTimeout(teruskanSub, 1200);
}
</script></head>
<body><iframe src="$url" allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
 allowfullscreen scrolling="no"></iframe></body>
</html>
""".trimIndent()

/** Domain terdaftar (dua label terakhir), mis. "videonode.de" dari "cdn.videonode.de". */
private fun baseDomainOf(url: String): String? = runCatching {
    Uri.parse(url).host?.lowercase()?.split('.')?.takeLast(2)?.joinToString(".")
}.getOrNull()

private val AD_PATH_MARKERS = listOf("/ads/", "/advert/", "/banner/", "/popup/", "/popunder")

private fun isBlockedRequest(lowerUrl: String): Boolean =
    AD_DOMAINS.any { lowerUrl.contains(it) } || isJunkMediaUrl(lowerUrl) || AD_PATH_MARKERS.any { lowerUrl.contains(it) }

private val EMBED_CLEANUP_JS = """
(function(){
  if (window.__bytifyClean) return; window.__bytifyClean = true;
  var keep = function(el){ return el.tagName==='VIDEO' || el.querySelector && el.querySelector('video') ||
    /(^|\s)(video-js|jwplayer|plyr|vjs-[\w-]+)(\s|$)/.test(el.className||'') || el.id==='player'; };
  var sels = ['[class*="popup"]','[id*="popup"]','[class*="overlay"]:not([class*="vjs"]):not([class*="jw"]):not([class*="plyr"])',
    '[class*="banner"]','[id*="banner"]','[class*="advert"]','[id*="advert"]','[class*="sponsor"]',
    '[class*="judi"]','[id*="judi"]','.ads','#ads','.ad-container','iframe[src*="ads"]','iframe[src*="banner"]'];
  function clean(){
    sels.forEach(function(s){ try{ document.querySelectorAll(s).forEach(function(el){ if(!keep(el)) el.remove(); }); }catch(e){} });
    document.querySelectorAll('body *').forEach(function(el){
      var st = getComputedStyle(el);
      if (st.position==='fixed' && parseInt(st.zIndex||'0') > 999 && !keep(el)) el.style.setProperty('display','none','important');
    });
  }
  window.open = function(){ return null; };
  clean();
  try {
    var fr = document.querySelector('iframe');
    console.log('probe: iframe=' + (!!fr) + ' w=' + (fr ? fr.offsetWidth : 0) +
      ' h=' + (fr ? fr.offsetHeight : 0) + ' src=' + (fr ? fr.src : '-') +
      ' anakBody=' + document.body.children.length);
  } catch (e) { console.log('probe gagal: ' + e); }
  new MutationObserver(function(){ clean(); }).observe(document.documentElement, {childList:true, subtree:true});
})();
""".trimIndent()

private fun mediaMimeType(url: String): String? = when (Uri.parse(url).path.orEmpty().substringAfterLast('.', "").lowercase()) {
    "m3u8" -> MimeTypes.APPLICATION_M3U8
    "mp4", "m4v" -> MimeTypes.VIDEO_MP4
    "mpd" -> MimeTypes.APPLICATION_MPD
    else -> null
}

@Composable
private fun MovieVideoContent(movie: Movie, episode: EpisodeDto?, onSelectEpisode: (EpisodeDto) -> Unit) {
    val app = appState()
    val context = LocalContext.current
    val repository = app.container.movies
    val lifecycleOwner = LocalLifecycleOwner.current
    val fullscreen = isLandscape()
    val floating by ModeFloating.aktif.collectAsState()
    DisposableEffect(Unit) {
        ModeFloating.daftarkanVideo()
        onDispose { ModeFloating.lepaskanVideo() }
    }
    val playKey = episode?.id ?: movie.id
    val videoUrl = episode?.videoUrl ?: movie.videoUrl.orEmpty()
    val subtitles = episode?.subtitles?.takeIf { it.isNotEmpty() } ?: if (episode == null) movie.subtitles else emptyList()
    var buffering by remember(playKey) { mutableStateOf(true) }
    var playbackError by remember(playKey) { mutableStateOf<String?>(null) }
    var useEmbedPlayer by remember(playKey, videoUrl) { mutableStateOf(isEmbedStreamUrl(videoUrl)) }
    // Server player aktif. Berpindah server adalah cara situs sumber menyediakan
    // resolusi lain, jadi pergantiannya dibuat seketika tanpa dialog apa pun.
    val serverList = remember(movie.id) { movie.servers.filter { it.url.isNotBlank() } }
    var activeEmbedUrl by remember(playKey, videoUrl) { mutableStateOf(videoUrl) }
    // Subtitle aktif untuk mode embed. Null berarti mati.
    var activeSubUrl by remember(playKey) { mutableStateOf<String?>(null) }
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
                .setMimeType(mediaMimeType(videoUrl))
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
                    videoUrl.contains("videonode.de") ||
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

    // Di jendela floating hanya gambarnya yang tersisa; daftar episode dan
    // kontrol tidak terbaca pada jendela sekecil itu.
    if (floating) {
        if (useEmbedPlayer) {
            key(activeEmbedUrl) {
                EmbedVideoStage(
                    videoUrl = activeEmbedUrl,
                    fullscreen = true,
                    referer = movie.sourceUrl,
                    subtitleUrl = activeSubUrl,
                    onToggleFullscreen = {},
                )
            }
        } else {
            VideoStage(
                exoPlayer,
                fullscreen = true,
                buffering = buffering,
                error = playbackError,
                onRetry = retry,
                showSubtitleButton = false,
            )
        }
        return
    }

    if (fullscreen) {
        if (useEmbedPlayer) {
            key(activeEmbedUrl) {
                EmbedVideoStage(
                    videoUrl = activeEmbedUrl,
                    fullscreen = true,
                    referer = movie.sourceUrl,
                    subtitleUrl = activeSubUrl,
                    onToggleFullscreen = { toggleFullscreen(context, false) },
                )
            }
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
            key(activeEmbedUrl) {
                EmbedVideoStage(
                    videoUrl = activeEmbedUrl,
                    fullscreen = false,
                    referer = movie.sourceUrl,
                    subtitleUrl = activeSubUrl,
                    onToggleFullscreen = { toggleFullscreen(context, true) },
                )
            }
        } else {
            VideoStage(exoPlayer, fullscreen = false, buffering = buffering, error = playbackError, onRetry = retry)
        }

        if (useEmbedPlayer && serverList.size > 1) {
            Text(
                "Kualitas / Server",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                serverList.forEach { pilihan ->
                    val aktif = pilihan.url == activeEmbedUrl
                    FilterChip(
                        selected = aktif,
                        onClick = { if (!aktif) activeEmbedUrl = pilihan.url },
                        label = { Text(pilihan.server.uppercase().ifBlank { "SERVER" }) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = Color.LightGray,
                            selectedContainerColor = BytifyColors.Gold,
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
        }

        if (useEmbedPlayer && subtitles.isNotEmpty()) {
            Text(
                "Subtitle",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pilihan = listOf<Pair<String, String?>>("Mati" to null) +
                    subtitles.map { (it.label.ifBlank { it.lang.uppercase() }) to it.url }
                pilihan.forEach { (nama, alamat) ->
                    val aktif = alamat == activeSubUrl
                    FilterChip(
                        selected = aktif,
                        onClick = { activeSubUrl = alamat },
                        label = { Text(nama) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = Color.LightGray,
                            selectedContainerColor = BytifyColors.Gold,
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
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
    referer: String? = null,
    subtitleUrl: String? = null,
) {
    // Player embed (videonode dan sejenisnya) menolak permintaan tanpa Referer
    // dan mengalihkannya ke beranda situs sumber. Kirim halaman film sebagai
    // Referer, lalu tahan navigasi yang keluar dari domain player.
    val sudahMain = remember(videoUrl) { AtomicBoolean(false) }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    // Berkas subtitle diambil dengan OkHttp (tidak terkena CORS), diurai jadi
    // cue, lalu dikirim ke dokumen player lewat jembatan postMessage.
    LaunchedEffect(subtitleUrl, webRef) {
        val web = webRef ?: return@LaunchedEffect
        val cue = if (subtitleUrl.isNullOrBlank()) {
            "[]"
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val minta = OkRequest.Builder().url(subtitleUrl)
                        .header("User-Agent", EMBED_USER_AGENT).build()
                    embedHttpClient.newCall(minta).execute().use { balasan ->
                        if (!balasan.isSuccessful) error("HTTP ${balasan.code}")
                        subtitleKeJson(balasan.body?.string().orEmpty())
                    }
                }.getOrElse {
                    Log.w(EMBED_TAG, "subtitle gagal diambil: ${it.message}")
                    "[]"
                }
            }
        }
        Log.i(EMBED_TAG, "kirim subtitle (${cue.length} bait json)")
        web.evaluateJavascript("window.bytifyKirimSub && window.bytifyKirimSub($cue);", null)
    }
    val allowedDomains = remember(videoUrl, referer) {
        setOfNotNull(baseDomainOf(videoUrl), referer?.let(::baseDomainOf))
    }
    val boxMod = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)

    Box(boxMod.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webRef = this
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
                        userAgentString = EMBED_USER_AGENT
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            val pesan = msg?.message().orEmpty()
                            if (pesan.contains("BYTIFY_MAIN")) {
                                sudahMain.set(true)
                                Log.i(EMBED_TAG, "film berjalan; ketukan otomatis dihentikan")
                            } else {
                                Log.i(EMBED_TAG, "konsol: $pesan")
                            }
                            return true
                        }

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
                            if (isBlockedRequest(reqUrl)) return true
                            // Pengalihan ke luar domain player berarti gerbang Referer gagal:
                            // tahan, supaya situs sumber tidak tampil menggantikan film.
                            if (request?.isForMainFrame == true && allowedDomains.isNotEmpty()) {
                                val target = baseDomainOf(reqUrl)
                                if (target != null && target !in allowedDomains) {
                                    Log.w(EMBED_TAG, "navigasi frame utama ditahan: $target")
                                    return true
                                }
                            }
                            if (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://")) return true
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty().lowercase()

                            // Dokumen utama dijawab dengan pembungkus iframe, sehingga
                            // halamannya benar-benar beralamat di situs film dan iframe
                            // di dalamnya mengirim Referer yang sah ke player.
                            // loadDataWithBaseURL tidak bisa dipakai di sini: dokumen
                            // hasil data: tidak mengirim Referer sama sekali.
                            if (request?.isForMainFrame == true && !referer.isNullOrBlank() &&
                                reqUrl == referer.lowercase()
                            ) {
                                return WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    ByteArrayInputStream(embedWrapperHtml(videoUrl).toByteArray()),
                                )
                            }

                            if (isBlockedRequest(reqUrl)) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            // Dokumen player diambil sendiri lalu disisipi skrip
                            // pelewat gerbang iklan. Itu satu-satunya cara, karena
                            // iframe lintas-origin tidak bisa disentuh dari luar.
                            // Disisipkan ke setiap dokumen HTML dari domain player
                            // (termasuk frame bersarang), supaya frame yang benar-benar
                            // memuat <video> ikut mendapat penampil subtitle.
                            // Header Accept membedakan permintaan dokumen dari potongan
                            // media, jadi segmen video tidak ikut diproksikan.
                            val mintaDokumen = request?.requestHeaders
                                ?.entries?.firstOrNull { it.key.equals("Accept", true) }
                                ?.value.orEmpty().contains("text/html", true)
                            if (request != null && request.method.equals("GET", true) &&
                                (reqUrl == videoUrl.lowercase() || mintaDokumen)
                            ) {
                                val alamat = request.url.toString()
                                val asal = if (reqUrl == videoUrl.lowercase()) referer else videoUrl
                                runCatching {
                                    val pembangun = OkRequest.Builder().url(alamat)
                                        .header("User-Agent", EMBED_USER_AGENT)
                                        .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                                    if (!asal.isNullOrBlank()) pembangun.header("Referer", asal)
                                    embedHttpClient.newCall(pembangun.build()).execute().use { balasan ->
                                        // Bila permintaan berakhir di luar domain player,
                                        // berarti player menolak dan mengalihkan ke situs
                                        // sumber. Jangan sisipkan apa pun ke halaman itu.
                                        val urlAkhir = balasan.request.url.toString()
                                        if (baseDomainOf(urlAkhir) != baseDomainOf(alamat)) {
                                            Log.w(EMBED_TAG, "player dialihkan ke $urlAkhir")
                                            return@runCatching null
                                        }
                                        val tipe = balasan.header("Content-Type").orEmpty()
                                        val badan = balasan.body?.string()
                                        if (!balasan.isSuccessful || badan.isNullOrBlank() ||
                                            !tipe.contains("html", true)
                                        ) {
                                            Log.w(EMBED_TAG, "player tidak disisipi (${balasan.code} $tipe)")
                                            return@runCatching null
                                        }
                                        val titik = badan.indexOf("</head>", ignoreCase = true)
                                        val disisipi = if (titik >= 0) {
                                            badan.substring(0, titik) + GATE_BYPASS_JS + badan.substring(titik)
                                        } else {
                                            GATE_BYPASS_JS + badan
                                        }
                                        Log.i(EMBED_TAG, "disisipi: $alamat (${badan.length} bait)")
                                        WebResourceResponse(
                                            "text/html",
                                            "UTF-8",
                                            ByteArrayInputStream(disisipi.toByteArray(Charsets.UTF_8)),
                                        )
                                    }
                                }.getOrElse {
                                    Log.w(EMBED_TAG, "gagal mengambil player: ${it.message}")
                                    null
                                }?.let { return it }
                            }

                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.i(EMBED_TAG, "selesai memuat: $url")
                            view?.evaluateJavascript(EMBED_CLEANUP_JS, null)
                            view?.let { ketukOtomatis(it, sudahMain) }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.w(
                                EMBED_TAG,
                                "galat ${if (request?.isForMainFrame == true) "UTAMA" else "sub"}: " +
                                    "${request?.url} -> ${error?.description}",
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            Log.w(EMBED_TAG, "HTTP ${errorResponse?.statusCode} pada ${request?.url}")
                        }
                    }
                    // Alamat halaman film dimuat, lalu dicegat di atas dan dijawab
                    // dengan pembungkus iframe berisi player.
                    //
                    // Pemuatan ditunda sampai view punya tinggi: di dalam factory
                    // Compose belum mengukur WebView, sehingga viewport-nya 0 piksel
                    // dan iframe player ikut setinggi 0 (layar tampak hitam).
                    val mulaiMuat = {
                        if (!referer.isNullOrBlank()) loadUrl(referer) else loadUrl(videoUrl)
                    }
                    if (height > 0) {
                        mulaiMuat()
                    } else {
                        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                            override fun onLayoutChange(
                                v: View,
                                left: Int, top: Int, right: Int, bottom: Int,
                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                            ) {
                                if (bottom - top > 0) {
                                    v.removeOnLayoutChangeListener(this)
                                    mulaiMuat()
                                }
                            }
                        })
                    }
                }
            },
            onRelease = { webView ->
                if (webRef === webView) webRef = null
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