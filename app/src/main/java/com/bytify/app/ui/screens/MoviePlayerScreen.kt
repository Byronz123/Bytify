package com.bytify.app.ui.screens

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.util.Log
import com.bytify.app.BuildConfig
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.rounded.Groups
import com.bytify.app.data.together.Tontonan
import com.bytify.app.data.together.TogetherState
import com.bytify.app.util.PESAN_SEDANG_MENELPON
import com.bytify.app.util.sedangMenelpon
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
import com.bytify.app.data.movie.MovieServerDto
import com.bytify.app.data.movie.MovieSubtitleDto
import com.bytify.app.data.movie.isJunkMediaUrl
import com.bytify.app.data.movie.anikaiIdDari
import com.bytify.app.data.movie.serverAnikai
import com.bytify.app.data.movie.serverTambahan
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        lower.contains("dood") ||
        // Penyedia berkunci TMDB id. vidcore.io/movie/<id> dan
        // vidlink.pro/movie/<id> tidak memuat kata "embed" di URL-nya, jadi
        // tanpa baris ini keduanya terbaca sebagai media langsung dan
        // ExoPlayer mencoba memutar dokumen HTML sebelum jatuh ke embed.
        lower.contains("vidcore") ||
        lower.contains("vidlink") ||
        lower.contains("vidsrc") ||
        lower.contains("autoembed") ||
        // Pemutar anime anikai beralamat /watch#<seal>... — tidak memuat kata
        // "embed" sama sekali, jadi tanpa baris ini ia terbaca sebagai media
        // langsung dan ExoPlayer mencoba memutar dokumen HTML.
        lower.contains("anikai")
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

/** Kirim satu ketukan sungguhan ke tengah WebView (isTrusted === true). */
private fun ketukTengah(web: WebView) {
    if (web.width <= 0 || web.height <= 0) return
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
}

/**
 * Mengetuk tengah halaman sampai filmnya berjalan.
 *
 * [sudahSiap] adalah REM-nya, dan tanpa rem itu ketukan merusak server yang
 * sehat: terlihat di Galaxy A36, menu subtitle anikai terbuka menutupi video
 * yang sedang berputar. Begitu ada frame yang melaporkan sumbernya menempel,
 * ketukan berhenti.
 *
 * [jedaAwal] dibedakan karena dua jalur punya kebutuhan berlawanan. Halaman
 * bergerbang iklan (LK21) harus diketuk cepat. Pemutar pihak ketiga yang
 * dimuat LANGSUNG hanya perlu diketuk kalau ia memang menunggu gestur —
 * terukur di peramban: `vidlink.pro/movie/27205` diam di `readyState 0` tanpa
 * `currentSrc`, dan baru setelah satu ketukan sungguhan ia membuat
 * `blob:` dan mencapai `readyState 4`. Jedanya dibuat lebih panjang supaya
 * server yang TIDAK butuh ketukan (anikai mencapai SIAP di detik ke-3) sudah
 * menarik remnya sebelum ketukan pertama datang.
 */
private fun ketukOtomatis(
    web: WebView,
    sudahMain: AtomicBoolean,
    percobaan: Int = 0,
    sudahSiap: AtomicBoolean? = null,
    jedaAwal: Long = 2500L,
) {
    if (percobaan >= 8 || sudahMain.get() || sudahSiap?.get() == true) return
    web.postDelayed({
        if (!sudahMain.get() && sudahSiap?.get() != true && web.width > 0 && web.height > 0) {
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
            ketukOtomatis(web, sudahMain, percobaan + 1, sudahSiap, jedaAwal)
        }
    }, if (percobaan == 0) jedaAwal else 3000L)
}

internal const val EMBED_USER_AGENT =
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
  } catch (e) {
    // Penjaga DI DALAM penjaga, dan ini bukan kehati-hatian berlebih: blok
    // ini dulu mengulangi penugasan yang sama persis, sehingga pada halaman
    // yang membuat `window.open` read-only ia melempar LAGI — kali ini tanpa
    // yang menangkap. Terukur di Galaxy A36 pada veloflix:
    // "TypeError: Cannot assign to read only property 'open' of object
    // '#<Window>'", dan seluruh sisa GATE_BYPASS_JS tidak pernah dijalankan —
    // tanpa pemantau, tanpa penjaga tinggi, tanpa pelewat panduan. Servernya
    // lalu dibuang watchdog di detik ke-30 seolah-olah ia yang rusak.
    try { window.open = function(){ return jendelaTiruan; }; } catch (e2) {}
  }

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
      // `cariVideo()`, bukan `querySelector` biasa: pemantau memakai pencari
      // yang lebih kuat, dan memakai dua pencari berbeda membuat penjaga
      // tinggi mengurus elemen yang BUKAN yang sedang diputar. Terukur pada
      // veloflix: `BYTIFY_JALAN ... kotak=384x0` terus-menerus sementara
      // penjaga tinggi tidak pernah menyentuhnya.
      var v = cariVideo();
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

  // Banyak penyedia embed menjawab HTTP 200 dengan halaman "tidak ada sumber"
  // alih-alih galat. Tanpa deteksi ini server semacam itu baru dilewati setelah
  // ambang waktu penuh habis; dengan deteksi ini pergantiannya seketika.
  // Syaratnya KETAT — tidak ada <video> sama sekali DAN teks halamannya pendek
  // — supaya halaman yang kebetulan memuat kalimat itu tidak ikut tertuduh.
  function cekTakAdaSumber(){
    try {
      if (document.querySelector('video')) return;
      var t = (document.body ? document.body.innerText : '') || '';
      if (t.length > 2500) return;
      // Kalimat yang dipakai penyedia sungguhan ketika judulnya memang tidak
      // ada di katalog mereka. Diamati langsung:
      //   vidlink.pro/movie/1469930 -> "We Couldn't Find This Content ."
      // Film Indonesia sering tidak ada di penyedia internasional, dan tanpa
      // pola ini pemutar menunggu ambang penuh untuk sesuatu yang sudah
      // dijawab penyedianya dalam dua detik.
      if (/no server could play|no sources? found|no stream found|video not found|media not found|title not available|could ?n.?t find this content|content not found|not available in your region/i.test(t)) {
        console.log('BYTIFY_GAGAL');
      }
    } catch (e) {}
  }
  [2500, 4500, 8000, 12000, 18000].forEach(function(ms){ setTimeout(cekTakAdaSumber, ms); });

  // ---- Penjaga tinggi frame ----
  //
  // Jebakan yang sudah tercatat di keputusan 4, tetapi kali ini pada iframe
  // milik HALAMAN PENYEDIA, bukan pembungkus kita. Terukur di Galaxy A36 pada
  // pemutar anime anikai: videonya benar-benar berjalan (1920x1080, suaranya
  // terdengar) tetapi kotaknya 384x0 dan jendela frame-nya 384x0 — tinggi
  // persen di dalam frame itu tidak pernah teresolusi, jadi yang terlihat
  // layar hitam bersuara.
  //
  // Pembungkus kita punya bytifyFit() untuk ini, tetapi jalur LANGSUNG memang
  // tidak memakai pembungkus, sehingga tidak ada yang menetapkan tingginya.
  // Di sini tingginya dipaksa dalam PIKSEL dari dokumen induk, karena ukuran
  // sebuah iframe hanya bisa ditetapkan dari induknya.
  // Saklar percobaan: penjepit wadah dan pemaku video. Dibuat bisa dimatikan
  // supaya sebabnya bisa DIPISAH, bukan ditebak — layar hitam dengan suara
  // berjalan bisa datang dari aliran, dari tata letak, ATAU dari komposisi.
  // Diisi dari Kotlin saat dokumen ini disisipi: tiap host punya kamarnya
  // sendiri, jadi skrip yang sama berperilaku berbeda per penyedia.
  var BYTIFY_PROFIL = __PROFIL__;
  var BYTIFY_JEPIT = BYTIFY_PROFIL.jepit;
  function pastikanTinggi(){
    try {
      var h = window.innerHeight || document.documentElement.clientHeight || 0;
      if (h < 2) return;
      var de = document.documentElement, b = document.body;
      if (de && de.clientHeight < 2) de.style.minHeight = h + 'px';
      if (b && b.clientHeight < 2) b.style.minHeight = h + 'px';
      // Dokumen player DIPAKSA setinggi viewport, bukan hanya diberi
      // minHeight saat tingginya nol.
      //
      // Terlihat di Galaxy A36 pada vidcore: kontrol pemutar (mundur 10
      // detik, jeda, maju 10 detik, "Now Playing") tergambar DI ATAS kotak
      // video, bukan di tengahnya. Sebabnya dokumennya lebih tinggi daripada
      // kotak yang kita beri, sementara kontrolnya ditempatkan relatif
      // terhadap dokumen — jadi pusat dokumen jatuh di luar kotak. Menyamakan
      // tinggi dokumen dengan tinggi kotak membuat pusatnya kembali sama.
      if (BYTIFY_JEPIT) {
        if (de) { de.style.height = h + 'px'; de.style.overflow = 'hidden'; }
        if (b) {
          b.style.height = h + 'px';
          b.style.margin = '0';
          b.style.overflow = 'hidden';
        }
      }
      var fr = document.querySelectorAll('iframe');
      for (var i = 0; i < fr.length; i++) {
        if (fr[i].offsetHeight < 2 && fr[i].offsetWidth > 2) {
          fr[i].style.height = h + 'px';
          fr[i].style.minHeight = h + 'px';
        }
      }
      // Elemen video yang sudah punya isi tetapi tingginya 0 diberi tinggi
      // menurut rasio aslinya — kalau induknya sendiri yang tak bertinggi.
      var v = document.querySelector('video');
      if (v && v.videoWidth > 0 && v.clientHeight < 2 && v.clientWidth > 2) {
        v.style.height = Math.round(v.clientWidth * v.videoHeight / v.videoWidth) + 'px';
      }
      // VIDEO YANG DISEMBUNYIKAN pemutarnya sendiri dibuat terlihat lagi.
      //
      // Inilah sebab "ada suaranya tetapi gambarnya tidak bergerak", dan
      // terukur di Galaxy A36: `BYTIFY_JALAN t=4351.49 maju=3.00 rs=4
      // paused=false buf=4362.0 kotak=384x216@0,0 vis=visible op=0`. Aliran
      // sehat, ukuran benar, waktu maju 3 detik tiap 3 detik — hanya
      // opacity-nya NOL. Pemutar menyembunyikan videonya sambil menampilkan
      // poster atau gerbang iklannya sendiri, lalu lupa mengembalikannya.
      //
      // Syaratnya `readyState >= 2`: sebelum ada data, video yang disembunyikan
      // memang belum punya apa pun untuk ditampilkan, dan memaksanya hanya
      // menukar hitam dengan hitam.
      if (v && v.readyState >= 2 && BYTIFY_PROFIL.paksaTerlihat) {
        // Videonya DAN leluhurnya. Terukur pada veloflix: memaksa opacity
        // elemen videonya saja tidak cukup — `op` tetap terbaca 0 karena yang
        // disembunyikan pembungkusnya, dan pembungkus yang transparan membuat
        // video di dalamnya ikut tak terlihat betapapun jelasnya ia sendiri.
        var sasaran = v, lapis = 0;
        while (sasaran && lapis < 6) {
          var gs = getComputedStyle(sasaran);
          if (parseFloat(gs.opacity || '1') < 0.05) {
            sasaran.style.setProperty('animation', 'none', 'important');
            sasaran.style.setProperty('transition', 'none', 'important');
            sasaran.style.setProperty('opacity', '1', 'important');
          }
          if (gs.visibility === 'hidden') {
            sasaran.style.setProperty('visibility', 'visible', 'important');
          }
          if (gs.display === 'none') {
            sasaran.style.setProperty('display', 'block', 'important');
          }
          sasaran = sasaran.parentElement;
          lapis++;
        }
      }

      // WADAH yang lebih tinggi daripada viewport DIJEPIT ke tinggi viewport.
      //
      // Inilah sebab layar hitam yang sebenarnya, dan terukur: cinesrc
      // melaporkan `kotak=384x216@0,-90` — videonya diletakkan 90 piksel DI
      // ATAS kotak, jadi keluar layar meskipun `readyState 4` dan filmnya
      // berjalan. Pemutar modern memusatkan video di dalam wadahnya; kalau
      // wadah itu lebih tinggi daripada yang kita beri, pemusatan itu
      // mendorong video (dan kontrolnya) ke luar kotak.
      //
      // Menjepit wadahnya, bukan memaksa posisi videonya, menjaga tata letak
      // pemutar tetap utuh: kontrol tetap di tempatnya relatif terhadap video.
      if (v && BYTIFY_JEPIT) {
        var naik = v.parentElement, langkah = 0;
        while (naik && langkah < 8) {
          if (naik.offsetHeight > h + 4) {
            naik.style.height = h + 'px';
            naik.style.maxHeight = h + 'px';
          }
          naik = naik.parentElement;
          langkah++;
        }
        var kotak = v.getBoundingClientRect();
        // Kalau SESUDAH dijepit videonya masih keluar kotak, barulah posisinya
        // dipaksa. Urutan ini penting: menjepit wadah lebih lembut daripada
        // mencabut video dari tata letaknya.
        if (kotak.top < -1 || kotak.bottom > h + 1 || kotak.height < 2) {
          // `fixed`, bukan `absolute`: `absolute` masih mengikuti leluhur
          // berposisi, dan di vidlink leluhur itulah yang menggeser videonya.
          // Terukur di Galaxy A36: vidlink melapor `rs=4` dan `film berjalan`
          // sementara layarnya HITAM, dengan `kotak=384x216@0,18`.
          //
          // zIndex setinggi mungkin karena pemutar menaruh poster dan lapisan
          // kontrolnya DI ATAS video; begitu videonya dicabut dari tata letak,
          // lapisan itu menutupinya.
          v.style.position = 'fixed';
          v.style.top = '0';
          v.style.left = '0';
          v.style.width = '100vw';
          v.style.height = h + 'px';
          v.style.objectFit = 'contain';
          v.style.zIndex = '2147483646';
          v.style.background = '#000';
          if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
        }
      }
    } catch (e) {}
  }
  // Pemutar yang menulis ulang gayanya tiap render MENANG melawan interval:
  // 400 ms terlalu lambat, dan menurunkannya hanya memboroskan CPU. Pengamat
  // mutasi memulihkan sendiri setiap kali atributnya disentuh.
  function jagaTerlihat(){
    try {
      if (!BYTIFY_PROFIL.paksaTerlihat || typeof MutationObserver !== 'function') return;
      var v = cariVideo();
      if (!v || v.__bytifyDijaga) return;
      v.__bytifyDijaga = true;
      var pulihkan = function(){
        var n = v, l = 0;
        while (n && l < 6) {
          var g = getComputedStyle(n);
          if (parseFloat(g.opacity || '1') < 0.05) {
            // Animasi dan transisi CSS MENGALAHKAN gaya inline, jadi
            // menimpa opacity saja tidak cukup — terukur pada zxcstream di
            // dalam veloflix: `op` tetap 0 meski sudah dipaksa `!important`.
            n.style.setProperty('animation', 'none', 'important');
            n.style.setProperty('transition', 'none', 'important');
            n.style.setProperty('opacity', '1', 'important');
          }
          if (g.visibility === 'hidden') {
            n.style.setProperty('visibility', 'visible', 'important');
          }
          n = n.parentElement; l++;
        }
      };
      var pengamat = new MutationObserver(pulihkan);
      var sasaran = v, lapis = 0;
      while (sasaran && lapis < 6) {
        pengamat.observe(sasaran, { attributes: true, attributeFilter: ['style', 'class'] });
        sasaran = sasaran.parentElement; lapis++;
      }
      pulihkan();
    } catch (e) {}
  }
  setInterval(jagaTerlihat, 1000);

  setInterval(pastikanTinggi, 400);
  window.addEventListener('resize', pastikanTinggi);

  // ---- Sumber MENEMPEL tetapi tidak pernah membawa data ----
  //
  // Keadaan ketiga, di antara SIAP dan DIAM, dan yang paling membuang waktu.
  // Diamati pada cinesrc untuk film Indonesia `tmdb-1469930`: `currentSrc`
  // sudah berisi `blob:`, `networkState` 2 (sedang memuat), tetapi setelah
  // 36 detik `readyState` tetap 0 dan `buffered.length` tetap 0. Sumbernya
  // ada di katalog penyedia, alirannya tidak.
  //
  // Tanpa pemeriksaan ini server seperti itu menahan ambang penuh, padahal
  // jawabannya sudah pasti sejak detik ke-20: tidak ada satu bait pun yang
  // datang.
  // ---- Panduan veloflix: lima langkah yang menutupi pemutarnya ----
  //
  // veloflix membuka halaman tontonnya dengan modal "Panduan Menonton" berisi
  // lima langkah, dan selama modal itu ada videonya tidak terlihat sama
  // sekali. Ketukan otomatis justru MEMAJUKAN panduannya, bukan memutar film.
  //
  // Dibatasi ke host veloflix, dan hanya menekan tombol tutup atau "Lanjut"
  // milik panduan itu — bukan pembersih umum seperti EMBED_CLEANUP_JS, yang
  // justru merusak kontrol pemutar modern (3n).
  var cobaPanduan = 0;
  function lewatiPanduan(){
    try {
      if (!BYTIFY_PROFIL.lewatiPanduan) return;
      if (cobaPanduan++ > 20) return;
      var tombol = [].slice.call(document.querySelectorAll('button'));
      for (var i = 0; i < tombol.length; i++) {
        var t = (tombol[i].textContent || '').trim();
        if (t === '×' || t === '✕' || t === '✖') { tombol[i].click(); return; }
      }
      for (var j = 0; j < tombol.length; j++) {
        var u = (tombol[j].textContent || '').trim();
        if (/^(lanjut|next|selesai|mulai|lewati)$/i.test(u)) { tombol[j].click(); return; }
      }
    } catch (e) {}
  }
  setInterval(lewatiPanduan, 900);

  var lapornyaGagal = false;
  function cekSumberMandek(){
    try {
      if (lapornyaGagal) return;
      var v = cariVideo();
      if (!v) return;
      var punyaSumber = !!(v.currentSrc || v.src ||
        (v.querySelector && v.querySelector('source')));
      if (!punyaSumber) return;
      if (v.readyState > 0 || (v.buffered && v.buffered.length > 0)) return;
      lapornyaGagal = true;
      console.log('BYTIFY_GAGAL');
    } catch (e) {}
  }
  [20000, 26000].forEach(function(ms){ setTimeout(cekSumberMandek, ms); });

  // ---- Deteksi KEMAJUAN, bukan sekadar "sudah berputar" ----
  //
  // Terukur pada penyedia sungguhan: vidlink merakit alirannya lewat
  // WebAssembly lalu menyerahkannya ke player sebagai blob: melalui Media
  // Source Extensions. Tidak pernah ada URL m3u8 atau mp4 yang bisa diamati
  // dari luar — jadi satu-satunya yang bisa dipantau keadaan elemen <video>
  // itu sendiri.
  //
  // Gunanya membedakan dua hal yang dulu sama-sama "belum berputar":
  //   SIAP = sumber sudah menempel / data mulai masuk -> server ini BEKERJA,
  //          beri waktu lebih daripada membuangnya saat ambang biasa lewat.
  //   DIAM = tidak ada <video> sama sekali -> tidak ada gunanya menunggu.
  var sudahSiap = false;
  function pantauKemajuan(){
    try {
      var v = cariVideo();
      if (v && !sudahSiap) {
        // Syaratnya DATA yang benar-benar masuk, bukan sekadar sumber yang
        // menempel. Terukur di Galaxy A36: framextv memasang src lalu diam
        // dengan readyState 0 karena backend-nya membalas HTTP 429 — dan
        // dengan syarat yang longgar, server mati seperti itu justru DIBERI
        // kesabaran 45 detik, dua kali berturut-turut. "Punya src" bukan
        // kemajuan; "punya data" baru kemajuan.
        var adaData = (v.readyState >= 2) ||
                      (v.buffered && v.buffered.length > 0);
        if (adaData) {
          sudahSiap = true;
          // Geometri ikut dilaporkan sekali. Layar hitam yang tetap
          // bersuara punya dua sebab yang sangat berbeda: elemen videonya
          // berukuran 0 piksel (masalah tata letak), atau ia punya ukuran
          // tetapi permukaannya tidak terkomposit (masalah perenderan).
          // Tanpa angka ini keduanya terlihat sama persis.
          var r = {w:0,h:0,x:0,y:0};
          try { var b = v.getBoundingClientRect(); r = {w:Math.round(b.width),h:Math.round(b.height),x:Math.round(b.left),y:Math.round(b.top)}; } catch (e) {}
          var st = {};
          try { var cs = getComputedStyle(v); st = {d:cs.display,vis:cs.visibility,op:cs.opacity}; } catch (e) {}
          console.log('BYTIFY_SIAP rs=' + v.readyState +
            ' vid=' + v.videoWidth + 'x' + v.videoHeight +
            ' kotak=' + r.w + 'x' + r.h + '@' + r.x + ',' + r.y +
            ' gaya=' + st.d + '/' + st.vis + '/' + st.op +
            ' layar=' + window.innerWidth + 'x' + window.innerHeight +
            ' frame=' + (window.top === window.self ? 'utama' : 'anak'));
        }
      }
    } catch (e) {}
    setTimeout(pantauKemajuan, 500);
  }
  pantauKemajuan();

  // Server yang bahkan belum membuat elemen <video> setelah sekian detik tidak
  // sedang menyangga apa pun — ia memang tidak punya sumber. Terukur: vidcore
  // berhenti di "FETCHING YOUR CONTENT..." lebih dari 20 detik untuk judul yang
  // vidlink putar dengan lancar.
  setTimeout(function(){
    try { if (!sudahSiap && !cariVideo()) console.log('BYTIFY_DIAM'); } catch (e) {}
  }, 9000);

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
  // ---- Trek subtitle MILIK SERVER ----
  //
  // Sumber subtitle ketiga, dan untuk film Indonesia sering satu-satunya:
  // OpenSubtitles tidak punya apa pun untuk rilis Indonesia baru (diukur:
  // `tmdb 1469930` hanya punya satu berkas, berbahasa Inggris; `tmdb 1525059`
  // dan `tmdb 1589663` nol), sementara aliran dari penyedia kerap sudah
  // membawa treknya sendiri di dalam manifes.
  //
  // Yang dilaporkan hanya trek bertipe subtitle/caption, dan hanya dari frame
  // yang benar-benar memegang videonya.
  // ---- Pemantau BERKALA: memisahkan "aliran macet" dari "gambar beku" ----
  //
  // Dua keluhan terdengar sama bagi penonton ("hitam", "tidak bergerak") tetapi
  // sebabnya berlawanan, dan hanya satu angka yang memisahkannya:
  // `currentTime`. Kalau ia MAJU sementara layar diam, alirannya sehat dan yang
  // rusak komposisinya; kalau ia DIAM, alirannya yang berhenti. Tanpa angka ini
  // perbaikan hanya tebakan.
  var waktuLalu = -1;
  function pantauBerkala(){
    try {
      var v = cariVideo();
      if (!v) return;
      var g = getComputedStyle(v);
      var k = v.getBoundingClientRect();
      var maju = (v.currentTime - waktuLalu);
      waktuLalu = v.currentTime;
      console.log('BYTIFY_JALAN t=' + v.currentTime.toFixed(2) +
        ' maju=' + maju.toFixed(2) +
        ' rs=' + v.readyState + ' paused=' + v.paused +
        ' buf=' + (v.buffered.length ? v.buffered.end(v.buffered.length - 1).toFixed(1) : '0') +
        ' kotak=' + Math.round(k.width) + 'x' + Math.round(k.height) +
        '@' + Math.round(k.left) + ',' + Math.round(k.top) +
        ' pos=' + g.position + ' vis=' + g.visibility + ' op=' + g.opacity +
        ' tf=' + (g.transform === 'none' ? 'none' : 'ada') +
        ' frame=' + (window.top === window ? 'utama' : 'anak') +
        // Host dokumennya IKUT dilaporkan: tanpa itu laporan dari WebView
        // server sebelumnya — yang belum sempat dibuang — terbaca sebagai
        // bukti bahwa server yang BARU dipilih berjalan.
        ' host=' + location.hostname);
    } catch (e) {}
  }
  setInterval(pantauBerkala, 3000);

  var trekTerlapor = '';
  function lihatTrek(){
    try {
      var v = cariVideo();
      if (!v || !v.textTracks) return;
      var keluar = [];
      for (var i = 0; i < v.textTracks.length; i++) {
        var t = v.textTracks[i];
        if (t.kind !== 'subtitles' && t.kind !== 'captions') continue;
        keluar.push({i: i, lang: t.language || '', label: t.label || ''});
      }
      if (!keluar.length) return;
      var sidik = JSON.stringify(keluar);
      if (sidik === trekTerlapor) return;
      trekTerlapor = sidik;
      console.log('BYTIFY_TREK:' + sidik);
    } catch (e) {}
  }
  setInterval(lihatTrek, 1500);

  function pakaiTrek(nomor){
    try {
      var v = cariVideo();
      if (!v || !v.textTracks) return;
      for (var i = 0; i < v.textTracks.length; i++) {
        v.textTracks[i].mode = (i === nomor) ? 'showing' : 'disabled';
      }
    } catch (e) {}
  }

  window.addEventListener('message', function(ev){
    try {
      if (ev.data && typeof ev.data.bytifyTrek === 'number') {
        pakaiTrek(ev.data.bytifyTrek);
        for (var j = 0; j < window.frames.length; j++) {
          try { window.frames[j].postMessage(ev.data, '*'); } catch (e) {}
        }
      }
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
  try { window.open = function(){ return null; }; } catch (e) {}
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

/**
 * Satu server yang boleh dicoba pemutar, lengkap dengan Referer-nya.
 *
 * Referer ikut di sini — bukan satu nilai untuk seluruh film — karena
 * syaratnya berbeda menurut ASAL server: embed LK21 menolak permintaan yang
 * tidak membawa halaman filmnya dan mengalihkan ke beranda, sementara embed
 * pihak ketiga berkunci TMDB id memakai alamatnya sendiri.
 */
/**
 * Satu trek subtitle yang dibawa ALIRAN SERVER, bukan katalog atau
 * OpenSubtitles.
 *
 * Nomornya indeks di `video.textTracks`, jadi hanya berlaku selama dokumen
 * pemutarnya hidup — tidak disimpan dan tidak diterbitkan.
 */
@Serializable
data class TrekServer(
    val i: Int = 0,
    val lang: String = "",
    val label: String = "",
)

private data class PilihanServer(
    val nama: String,
    val url: String,
    val referer: String?,
)

/**
 * Berapa lama satu server ditunggu sebelum dianggap gagal dan dilewati.
 *
 * Sengaja longgar: `ketukOtomatis()` melewati gerbang iklan dengan ketukan
 * beruntun sampai sekitar 23 detik, jadi ambang yang terlalu rapat justru
 * meninggalkan server yang sebetulnya sedang berhasil. Kegagalan yang bisa
 * dikenali cepat (pengalihan keluar domain, atau halaman melaporkan tak punya
 * sumber) tidak menunggu ambang ini.
 */
private const val BATAS_TUNGGU_MS = 18_000L

/**
 * Ambang yang dipakai begitu server melaporkan sumbernya sudah menempel
 * (`BYTIFY_SIAP`).
 *
 * Membedakan keduanya penting karena dua kegagalan yang dulu terlihat sama
 * sebetulnya berlawanan: server yang tidak punya sumber sama sekali harus
 * ditinggalkan secepatnya, sedangkan server yang sudah menempelkan sumber dan
 * sedang menyangga justru paling rugi kalau dibuang — itu satu-satunya yang
 * benar-benar akan memutar film.
 */
private const val BATAS_SABAR_MS = 45_000L

/**
 * Ambang untuk server yang dimuat LANGSUNG (pemutar pihak ketiga).
 *
 * Lebih longgar daripada [BATAS_TUNGGU_MS] karena jamnya tidak sebanding:
 * pemutar seperti vidlink dan cinesrc baru membuat sumbernya SETELAH ada
 * ketukan, dan ketukan pertama di jalur ini sengaja ditunda 6 detik supaya
 * tidak mengganggu server yang berjalan sendiri. Terukur di Galaxy A36:
 * cinesrc dibuang pada detik ke-18 dengan `siap=false, kemajuan=false`,
 * padahal ia hanya punya 12 detik sejak diketuk. 30 detik memberi jendela
 * yang sepadan tanpa menahan server yang benar-benar mati terlalu lama.
 */
private const val BATAS_KETUK_MS = 30_000L

/**
 * Disuntikkan DARI KOTLIN ke dokumen utama yang tidak kita proksikan.
 *
 * Halaman seperti veloflix tidak boleh diambil ulang lewat OkHttp (sesinya
 * rusak tanpa cookie), sehingga `GATE_BYPASS_JS` tidak pernah masuk ke sana.
 * Akibatnya tidak ada yang memberi tinggi pada iframe pemutarnya, dan terukur
 * di Galaxy A36 videonya berjalan dengan `kotak=384x0` — ada suaranya, tidak
 * ada gambarnya.
 *
 * `evaluateJavascript` tidak terpengaruh interception, jadi inilah jalan
 * masuknya. Isinya sengaja minimal: tinggi iframe, dan tombol panduan.
 */
private const val SUNTIK_LUAR_JS = """
    (function(){
      try {
        var h = window.innerHeight || document.documentElement.clientHeight || 0;
        if (h > 2) {
          var fr = document.querySelectorAll('iframe');
          for (var i = 0; i < fr.length; i++) {
            if (fr[i].offsetWidth > 2 && fr[i].offsetHeight < h - 2) {
              fr[i].style.height = h + 'px';
              fr[i].style.minHeight = h + 'px';
            }
          }
          if (document.body) document.body.style.margin = '0';
        }
        if (/veloflix/i.test(location.hostname)) {
          var tb = [].slice.call(document.querySelectorAll('button'));
          for (var j = 0; j < tb.length; j++) {
            var t = (tb[j].textContent || '').trim();
            if (t === '\u00d7' || t === '\u2715') { tb[j].click(); return; }
          }
          for (var k = 0; k < tb.length; k++) {
            var u = (tb[k].textContent || '').trim();
            if (/^(lanjut|next|selesai|mulai|lewati)$/i.test(u)) { tb[k].click(); return; }
          }
        }
      } catch (e) {}
    })();
"""

/**
 * KAMAR SENDIRI untuk tiap penyedia.
 *
 * Alasannya didapat dengan cara yang mahal: tiap kali satu server diperbaiki,
 * perbaikannya berlaku untuk SEMUA server, dan yang tadinya sehat ikut rusak.
 * Pembersih iklan LK21 merusak kontrol pemutar modern; ketukan otomatis yang
 * dibutuhkan vidlink membuka menu subtitle anikai di atas video yang sedang
 * berjalan; proksi dokumen yang wajib untuk embed LK21 mematikan sesi veloflix.
 *
 * Jadi perilaku itu TIDAK lagi global. Tiap host memilih kamarnya sendiri di
 * sini, dan bawaannya konservatif: penyedia yang belum dikenal mendapat
 * perlakuan paling netral, bukan paling agresif.
 */
private data class ProfilServer(
    /** Ambil dokumennya sendiri lewat OkHttp lalu sisipi skrip. */
    val proksiDokumen: Boolean = true,
    /** Jalankan pembersih gerbang iklan. Hanya aman untuk halaman LK21. */
    val pembersihIklan: Boolean = false,
    /** Ketukan otomatis, dan jedanya. 0 berarti tidak diketuk sama sekali. */
    val jedaKetukMs: Long = 6000L,
    /** Jepit tinggi dokumen dan wadah video ke tinggi viewport. */
    val jepitWadah: Boolean = true,
    /** Paksa video yang disembunyikan pemutarnya sendiri agar terlihat. */
    val paksaTerlihat: Boolean = true,
    /** Tekan tombol panduan bertingkat milik situsnya. */
    val lewatiPanduan: Boolean = false,
    /** Ambang watchdog sebelum ada tanda kemajuan apa pun. */
    val ambangMs: Long = BATAS_KETUK_MS,
)

/**
 * Bawaan untuk penyedia yang belum punya kamar sendiri: proksi + sisipan,
 * tanpa pembersih iklan, ketukan setelah 6 detik.
 */
private val PROFIL_BAWAAN = ProfilServer()

private val PROFIL: List<Pair<String, ProfilServer>> = listOf(
    // veloflix: aplikasi Next.js bersesi. Dokumennya TIDAK boleh diproksikan —
    // tanpa cookie WebView ia memuat ulang dirinya tanpa henti dan iframe
    // pemutarnya tidak pernah muncul. Panduan lima langkahnya menutupi video,
    // jadi tombolnya ditekan sendiri.
    "veloflix.my.id" to ProfilServer(
        proksiDokumen = false,
        lewatiPanduan = true,
        jedaKetukMs = 0L,
        ambangMs = 45_000L,
    ),
    // zxcstream: frame pemutar DI DALAM veloflix. Tetap disisipi, karena hanya
    // skrip yang bisa melaporkan BYTIFY_SIAP — tanpa itu servernya dibuang
    // watchdog betapapun sehatnya.
    "zxcstream.cc" to ProfilServer(jedaKetukMs = 0L, ambangMs = 45_000L),
    // anikai: mencapai SIAP di detik ke-3 dan tidak butuh ketukan; mengetuknya
    // justru membuka menu subtitlenya di atas video yang sedang berjalan.
    "anikai.org" to ProfilServer(jedaKetukMs = 0L),
    "megaplay.buzz" to ProfilServer(jedaKetukMs = 0L),
    // --- Enam penyedia baru, masing-masing kamarnya sendiri ---
    //
    // Dipisah sejak awal, bukan setelah bertabrakan: perilaku yang cocok untuk
    // satu penyedia hampir selalu merusak penyedia lain, dan itu sudah terbukti
    // berkali-kali di sesi-sesi sebelumnya.
    "player.cinezo.live" to ProfilServer(jedaKetukMs = 6000L),
    "player.vidzee.wtf" to ProfilServer(jedaKetukMs = 6000L),
    "vaplayer.ru" to ProfilServer(jedaKetukMs = 6000L),
    "vsembed.su" to ProfilServer(jedaKetukMs = 6000L),
    // Dua yang berupa SITUS, bukan pemutar: mereka memuat pemutar lain di
    // dalam iframe, jadi jangan diketuk cepat-cepat (ketukan di situs induk
    // menekan tombolnya sendiri, bukan tombol pemutarnya) dan beri waktu lebih
    // karena ada satu lapisan jaringan tambahan.
    "holaflix.cc" to ProfilServer(jedaKetukMs = 9000L, ambangMs = 45_000L),
    "novashow.live" to ProfilServer(jedaKetukMs = 9000L, ambangMs = 45_000L),

    // Pemutar yang baru membuat sumbernya SETELAH ada gestur.
    "vidlink.pro" to ProfilServer(jedaKetukMs = 6000L),
    "cinesrc.st" to ProfilServer(jedaKetukMs = 6000L),
    "vidcore.io" to ProfilServer(jedaKetukMs = 6000L),
)

/** Kamar untuk [url]; bawaannya dipakai bila hostnya belum dikenal. */
/**
 * Menanam kamar [profil] ke dalam salinan skrip untuk satu dokumen.
 *
 * Satu berkas skrip, banyak perilaku: tanpa ini tiap perbaikan per penyedia
 * harus jadi cabang `if (hostname)` di dalam skrip, dan itulah yang dulu
 * membuat perbaikan satu server merusak server lain.
 */
private fun skripUntuk(profil: ProfilServer): String = GATE_BYPASS_JS.replace(
    "__PROFIL__",
    "{jepit:${profil.jepitWadah}," +
        "paksaTerlihat:${profil.paksaTerlihat}," +
        "lewatiPanduan:${profil.lewatiPanduan}}",
)

private fun profilUntuk(url: String?): ProfilServer {
    val alamat = url.orEmpty().lowercase()
    return PROFIL.firstOrNull { alamat.contains(it.first) }?.second ?: PROFIL_BAWAAN
}

/** Host yang dokumennya TIDAK boleh diambil ulang lewat OkHttp (lihat di bawah). */
private val TANPA_PROKSI = listOf("veloflix.my.id")

/**
 * Host yang pada pengukuran terakhir tidak memulangkan apa pun.
 *
 * Dipakai hanya untuk URUTAN, bukan untuk membuang. Jalankan sapuan uji lagi
 * sebelum menambah atau mengurangi daftar ini — satu judul yang gagal bukan
 * bukti penyedianya mati (3v).
 */
private val HOST_MATI = listOf(
    "framextv",
    "vidsrc.cc",
    // 0/3 film di Galaxy A36: terpilih (dibuktikan siaran uji) tetapi tidak
    // satu pun memulangkan sumber.
    "vaplayer.ru",
    // 0/3 film, dan sebabnya terlihat dari luar: `novashow.live/player?id=`
    // menjawab HTTP 200 dengan NOL bait. Halamannya kosong, jadi tidak ada
    // yang bisa dimuat.
    "novashow.live",
)

// CATATAN STATUS veloflix (belum selesai, jangan dianggap beres):
// aliran dan tata letaknya sudah benar — `BYTIFY_JALAN t=710.59 maju=3.00
// rs=4 buf=824.5 kotak=384x216@0,0 op=1` — tetapi layarnya MASIH hitam.
// Yang sudah terbukti BUKAN sebabnya: tinggi iframe (sudah 216), opacity
// video dan leluhurnya (sudah 1), animasi CSS (sudah dimatikan), skrip yang
// mati (sudah diperbaiki), dan proksi dokumen (sudah dimatikan untuk host
// ini). Tersangka berikutnya ada di dokumen LUAR veloflix yang memang tidak
// kita sisipi: posisi iframe-nya di halaman, atau lapisan panduannya yang
// masih menutupi. Ukur dari sana, jangan mengulang yang sudah dicoret.

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
    // Nonton Bareng: host mengumumkan judul & episode yang dibuka. Posisi putar
    // tidak ikut dikirim — pemutarnya iframe pihak ketiga, garis waktunya tidak
    // bisa dibaca maupun disetel aplikasi. Yang disinkronkan adalah pilihan
    // tontonan dan aba-aba mulai.
    val together = app.container.together
    val statusRoom by together.state.collectAsState()
    val hostRoom = statusRoom is TogetherState.InRoom && !together.isGuest
    val floating by ModeFloating.aktif.collectAsState()

    val playKey = episode?.id ?: movie.id
    LaunchedEffect(movie.id, episode?.id, hostRoom) {
        if (hostRoom) {
            together.bagikanTontonan(
                Tontonan("film", movie.id, episodeId = episode?.id, judul = movie.title),
            )
        }
    }
    val videoUrl = episode?.videoUrl ?: movie.videoUrl.orEmpty()
    val subtitlesKatalog = episode?.subtitles?.takeIf { it.isNotEmpty() }
        ?: if (episode == null) movie.subtitles else emptyList()

    // Subtitle tambahan dari OpenSubtitles, lewat proxy backend (kunci API ada
    // di server, tidak di APK). Hasilnya ditambahkan ke daftar yang SAMA, jadi
    // seluruh jalur di bawah ini tidak berubah sama sekali: konfigurasi
    // ExoPlayer, pipa cue embed lewat postMessage, dan menu pemilih.
    //
    // URL-nya menunjuk ke /api/subtitles/file?fileId=..., yang baru mengalihkan
    // ke tautan OpenSubtitles saat benar-benar diunduh — jadi kuota tidak
    // terpakai hanya karena daftarnya tampil.
    //
    // Gagal berarti daftar kosong: subtitle itu tambahan, dan pemutaran tidak
    // boleh ikut gagal karenanya.
    var subtitlesJaring by remember(playKey) { mutableStateOf<List<MovieSubtitleDto>>(emptyList()) }
    LaunchedEffect(playKey) {
        val ket = app.container.ketersediaan
        // ANIME punya jalurnya sendiri, dan harus: id katalognya "anikai-",
        // bukan "anilist-", sehingga tabel `watch_aliases` tidak bisa
        // menjangkaunya sama sekali. TMDB id dan nomor musimnya datang dari
        // lapisan server anime, yang memang membawa keduanya.
        val idAnikai = anikaiIdDari(movie.id)
        if (idAnikai != null) {
            val entri = runCatching { repository.serverAnime() }.getOrNull()
                ?.titles?.get(idAnikai.toString())
            val tmdbAnime = entri?.tmdbId ?: return@LaunchedEffect
            // Anime berepisode tunggal adalah FILM, dan film tidak punya
            // musim. Memintanya sebagai "episode" memulangkan daftar kosong,
            // jadi 326 judul seperti itu tidak akan pernah dapat subtitle
            // kalau tipenya tidak dibedakan.
            if (entri.tmdbType == "movie") {
                subtitlesJaring = app.container.movieCatalogApi.fetchSubtitles(
                    tmdbId = tmdbAnime,
                    type = "movie",
                )
                Log.i(
                    EMBED_TAG,
                    "subtitle anime (film): tmdb=$tmdbAnime " +
                        "hasil=${subtitlesJaring.size} " +
                        "bahasa=${subtitlesJaring.map { it.lang }}",
                )
                return@LaunchedEffect
            }
            // Tanpa nomor musim yang pasti, permintaan per-episode akan
            // memulangkan subtitle MUSIM YANG SALAH. Lebih baik tidak ada
            // subtitle daripada subtitle musim lain.
            val musimAnime = entri.season ?: return@LaunchedEffect
            subtitlesJaring = app.container.movieCatalogApi.fetchSubtitles(
                tmdbId = tmdbAnime,
                type = "episode",
                season = musimAnime,
                episode = episode?.episode ?: 1,
            )
            Log.i(
                EMBED_TAG,
                "subtitle anime: tmdb=$tmdbAnime musim=$musimAnime " +
                    "ep=${episode?.episode} hasil=${subtitlesJaring.size} " +
                    "bahasa=${subtitlesJaring.map { it.lang }}",
            )
            return@LaunchedEffect
        }

        val acuan = runCatching { ket.tmdbUntuk(movie.id) }.getOrNull() ?: return@LaunchedEffect
        val (tmdbId, tipeTmdb) = acuan
        val perEpisode = episode != null && tipeTmdb == "tv"

        // Serial yang berbagi entri TMDB dengan musim lain dilewati: episode
        // kita semuanya bernomor musim 1, jadi permintaannya akan memulangkan
        // subtitle musim yang salah. Chip ketersediaannya tetap tampil —
        // "ada di Netflix" benar untuk seluruh musim.
        if (perEpisode && !ket.amanSubtitlePerEpisode(movie.id, tmdbId, tipeTmdb)) {
            return@LaunchedEffect
        }

        subtitlesJaring = app.container.movieCatalogApi.fetchSubtitles(
            tmdbId = tmdbId,
            type = if (perEpisode) "episode" else "movie",
            season = episode?.season,
            episode = episode?.episode,
        )
    }
    // Subtitle dari katalog diutamakan; yang dari jaringan hanya menambah
    // bahasa yang belum ada, supaya tidak ada dua entri untuk bahasa sama.
    val subtitles = remember(subtitlesKatalog, subtitlesJaring) {
        val sudahAda = subtitlesKatalog.map { it.lang.lowercase() }.toSet()
        subtitlesKatalog + subtitlesJaring.filterNot { it.lang.lowercase() in sudahAda }
    }
    var buffering by remember(playKey) { mutableStateOf(true) }
    var playbackError by remember(playKey) { mutableStateOf<String?>(null) }
    var useEmbedPlayer by remember(playKey, videoUrl) { mutableStateOf(isEmbedStreamUrl(videoUrl)) }

    // Server tambahan yang DITURUNKAN dari TMDB id judul ini. Tidak diterbitkan
    // sebagai berkas: URL-nya deterministik dari satu bilangan yang sudah ada di
    // tabel watch_aliases (lihat ServerTambahan.kt).
    //
    // Untuk serial, penomoran musimnya harus tidak ambigu. Penjaganya dipakai
    // ULANG dari subtitle — masalahnya identik: katalog menomori semua episode
    // sebagai musim 1, jadi untuk judul yang berbagi satu entri TMDB dengan
    // musim lain, "musim 1 episode 5" menunjuk episode yang SALAH. Memutar
    // episode yang salah lebih buruk daripada tidak punya server cadangan.
    var serverEkstra by remember(playKey) { mutableStateOf<List<MovieServerDto>>(emptyList()) }
    LaunchedEffect(playKey) {
        // Anime ditangani lebih dulu dan TIDAK lewat jalur TMDB id.
        //
        // Katalognya sudah berisi server anikai untuk episode yang tersimpan;
        // lapisan ini yang melayani episode HASIL PELENGKAPAN, karena ia
        // membawa seal-nya dan bisa membentuk alamat episode berapa pun.
        val idAnikai = anikaiIdDari(movie.id)
        if (idAnikai != null) {
            val katalog = runCatching { repository.serverAnime() }.getOrNull()
            serverEkstra = listOfNotNull(
                katalog?.let { serverAnikai(it, idAnikai, episode?.episode ?: 1) },
            )
            Log.i(
                EMBED_TAG,
                "server anime: anikai=$idAnikai lapisan=${katalog?.titles?.size ?: -1} " +
                    "hasil=${serverEkstra.size}",
            )
            return@LaunchedEffect
        }

        val acuan = runCatching { app.container.ketersediaan.tmdbUntuk(movie.id) }.getOrNull()
            ?: return@LaunchedEffect
        val (tmdbId, tipe) = acuan
        serverEkstra = if (tipe == "tv") {
            val ep = episode ?: return@LaunchedEffect
            val aman = runCatching {
                app.container.ketersediaan.amanSubtitlePerEpisode(movie.id, tmdbId, tipe)
            }.getOrDefault(false)
            if (!aman) return@LaunchedEffect
            serverTambahan(tmdbId, tipe, ep.season, ep.episode)
        } else {
            serverTambahan(tmdbId, "movie")
        }
    }

    // Daftar server yang DITELUSURI pemutar, urut dari yang paling dipercaya.
    // Server katalog lebih dulu karena URL-nya hasil panen dari halaman film
    // yang nyata; server turunan menyusul sebagai cadangan.
    //
    // Refer­er-nya berbeda menurut ASAL server, bukan menurut domain: server
    // katalog LK21 mensyaratkan halaman film sebagai Referer (tanpa itu embed
    // mengalihkan ke beranda), sementara embed pihak ketiga memakai alamatnya
    // sendiri — pola yang sama yang sudah terbukti pada katalog film Indonesia.
    val kandidat = remember(movie.id, videoUrl, movie.servers, serverEkstra, movie.sourceUrl) {
        val hasil = mutableListOf<PilihanServer>()
        val terlihat = mutableSetOf<String>()
        fun tambah(nama: String, url: String, referer: String?) {
            if (url.isBlank() || !terlihat.add(url)) return
            hasil += PilihanServer(nama.ifBlank { "server" }, url, referer)
        }
        // Nama server yang sedang dipakai diambil dari katalog kalau ada, bukan
        // dilabeli "utama" begitu saja — supaya ia ikut terhitung sebagai
        // keluarga penyedia yang sama pada penyusunan di bawah.
        // Untuk ANIME, server anikai didahulukan sebelum server katalog.
        //
        // Terukur di Galaxy A36, bukan preferensi: server katalog anime adalah
        // framextv, dan keempat "cermin"-nya ternyata satu server yang sama
        // (semua dialihkan ke framextv.cc, backend api.framextv.tech yang
        // membalas HTTP 429). Dua anime yang diuji — Jujutsu Kaisen dan
        // Renegade Immortal — sama-sama gagal di sana sampai ambang waktu
        // habis, sementara anikai memutar keduanya. Jujutsu Kaisen mulai
        // berjalan 6 detik setelah tombol ditekan.
        //
        // framextv TIDAK dibuang, hanya turun urutan: ia tetap dicoba kalau
        // anikai gagal, dan 2.320 judul anikai yang tidak terjodohkan tetap
        // mengandalkannya.
        val anime = movie.id.startsWith("anikai-")
        val dahulukanEkstra = anime && serverEkstra.isNotEmpty()
        if (dahulukanEkstra) {
            serverEkstra.forEach { tambah(it.server, it.url, it.url) }
        }
        // Nama server yang sedang dipakai diambil dari katalog kalau ada, bukan
        // dilabeli "utama" begitu saja — supaya ia ikut terhitung sebagai
        // keluarga penyedia yang sama pada penyusunan di bawah.
        tambah(
            movie.servers.firstOrNull { it.url == videoUrl }?.server ?: "utama",
            videoUrl,
            movie.sourceUrl,
        )
        movie.servers.forEach { tambah(it.server, it.url, movie.sourceUrl) }
        if (!dahulukanEkstra) {
            serverEkstra.forEach { tambah(it.server, it.url, it.url) }
        }

        // Cermin dari penyedia yang SAMA didorong ke belakang antrean.
        //
        // Terukur di Galaxy A36: keempat "cermin" framextv (xyz, cc, pro, tech)
        // sama-sama DIALIHKAN ke framextv.cc dan memakai satu backend yang sama
        // (api.framextv.tech, yang membalas HTTP 429). Jadi mereka bukan cermin
        // yang bisa saling menggantikan, melainkan satu server yang dicoba
        // empat kali — membuang sekitar 54 detik sebelum penyedia yang
        // BENAR-BENAR berbeda sempat dicoba, sekaligus ikut memancing rate
        // limit itu sendiri.
        //
        // Dikelompokkan menurut NAMA, bukan domain: server LK21 p2p, hydrax,
        // dan turbovip sama-sama berada di videonode.de tetapi benar-benar
        // pemutar yang berbeda, dan mengelompokkannya menurut domain akan
        // merusak jalur LK21 yang sudah terbukti.
        //
        // Tidak dibuang, hanya diturunkan: kalau semua yang lain gagal, cermin
        // itu tetap dicoba di akhir.
        val keluarga = mutableSetOf<String>()
        val pertama = mutableListOf<PilihanServer>()
        val cermin = mutableListOf<PilihanServer>()
        hasil.forEach { pilihan ->
            if (keluarga.add(pilihan.nama.substringBefore('-'))) {
                pertama += pilihan
            } else {
                cermin += pilihan
            }
        }
        // Host yang TERUKUR mati diturunkan ke paling belakang.
        //
        // Diuji di Galaxy A36 pada tiga judul: `framextv` tidak pernah
        // memulangkan satu bait pun (backend 429) dan `vidsrc.cc` origin-nya
        // 522. Keduanya tetap DIPASANG — origin bisa pulih, dan membuangnya
        // berarti harus menerbitkan APK baru ketika itu terjadi — tetapi
        // menaruhnya di depan berarti penonton menunggu ambang penuh untuk
        // sesuatu yang sudah diketahui tidak menjawab.
        val semua = pertama + cermin
        val (mati, hidup) = semua.partition { pilihan ->
            HOST_MATI.any { pilihan.url.contains(it, ignoreCase = true) }
        }
        hidup + mati
    }

    // Indeks server yang sedang dicoba. Berpindah server juga cara situs sumber
    // menyediakan resolusi lain, jadi pergantian manual tetap seketika.
    var indeksServer by remember(playKey, videoUrl) { mutableStateOf(0) }
    var serverHabis by remember(playKey, videoUrl) { mutableStateOf(false) }
    // Server yang sudah menempelkan sumber ke elemen video. Dipakai watchdog
    // untuk memilih ambang sabar alih-alih ambang biasa.
    var serverSiap by remember(playKey, videoUrl) { mutableStateOf(false) }
    // Server sedang merakit pemutarnya (frame bersarang sudah dimuat), walau
    // sumbernya belum menempel. Bukti kemajuan, bukan bukti keberhasilan.
    var serverKemajuan by remember(playKey, videoUrl) { mutableStateOf(false) }
    // Trek subtitle yang dibawa server yang sedang aktif. Dikosongkan tiap
    // ganti server, karena indeksnya hanya berlaku di dokumen pemutar itu.
    var trekServer by remember(playKey, videoUrl) { mutableStateOf<List<TrekServer>>(emptyList()) }
    var trekAktif by remember(playKey, videoUrl) { mutableStateOf<Int?>(null) }
    val serverAktif = kandidat.getOrNull(indeksServer)
    val activeEmbedUrl = serverAktif?.url ?: videoUrl
    val refererAktif = serverAktif?.referer ?: movie.sourceUrl
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

    // Jendela floating Android TIDAK menerima sentuhan: hanya tombol sistem
    // yang bisa ditekan. Kalau pemutar embed masih menunggu ketukan — misalnya
    // dialog "lanjutkan atau mulai dari awal" — jendela kecilnya akan membeku
    // selamanya, karena dialog itu mustahil ditekan dari sana. Jadi mode
    // floating baru diizinkan setelah videonya BENAR-BENAR berjalan: untuk
    // embed saat halaman melaporkan BYTIFY_MAIN, untuk pemutar langsung saat
    // ExoPlayer benar-benar memutar.
    var videoBerjalan by remember(playKey) { mutableStateOf(false) }
    /** Alamat server yang laporan gagalnya SUDAH dipakai memajukan server. */
    var sudahDilewati by remember(playKey) { mutableStateOf<String?>(null) }
    /** Pengguna memilih servernya sendiri; jangan dipindahkan otomatis. */
    var pilihanManual by remember(playKey, videoUrl) { mutableStateOf(false) }

    // --- Jalur UJI: memilih server lewat siaran, hanya di build debug ---
    //
    // Menguji belasan server lewat antarmuka ternyata tidak bisa dipercaya:
    // `uiautomator dump` gagal diam-diam selama video berjalan (ia butuh
    // antarmuka yang diam) sehingga daftar chip terbaca basi, ketukan pada
    // koordinat tetap melewatkan chip yang lebarnya berbeda, dan navigasi
    // fokus tidak selalu mendarat di deret yang benar. Akibatnya server yang
    // TIDAK PERNAH dimuat terlaporkan sebagai "tidak bekerja" — kesimpulan
    // yang salah dan berbahaya.
    //
    // Siaran ini memilih server MENURUT NAMANYA, jadi pengujian tidak lagi
    // bergantung pada tata letak. Dipagari `BuildConfig.DEBUG`: di build
    // rilis receiver-nya tidak pernah didaftarkan sama sekali.
    val konteksUji = LocalContext.current
    if (BuildConfig.DEBUG) {
        DisposableEffect(kandidat) {
            val penerima = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val nama = intent?.getStringExtra("nama").orEmpty().lowercase()
                    if (nama.isBlank()) return
                    val urutan = kandidat.indexOfFirst {
                        it.nama.lowercase().contains(nama) || it.url.lowercase().contains(nama)
                    }
                    Log.i(EMBED_TAG, "uji: pilih '$nama' -> indeks $urutan dari ${kandidat.size}")
                    if (urutan >= 0) {
                        serverHabis = false
                        pilihanManual = true
                        indeksServer = urutan
                    }
                }
            }
            val saringan = IntentFilter("com.bytify.app.UJI_SERVER")
            ContextCompat.registerReceiver(
                konteksUji, penerima, saringan, ContextCompat.RECEIVER_EXPORTED,
            )
            onDispose { runCatching { konteksUji.unregisterReceiver(penerima) } }
        }
    }

    DisposableEffect(videoBerjalan) {
        if (videoBerjalan) ModeFloating.daftarkanVideo() else ModeFloating.lepaskanVideo()
        onDispose { ModeFloating.lepaskanVideo() }
    }

    // ---- Failover otomatis antar server embed ----
    //
    // Sebelum ini satu-satunya fallback adalah ExoPlayer -> embed. Kalau embed
    // yang gagal, layar tinggal hitam dan pengguna harus menebak sendiri bahwa
    // ada chip server lain yang perlu ditekan. Sekarang pemutar menelusuri
    // [kandidat] sampai ada yang benar-benar berputar.
    //
    // Sinyal BERHASIL sudah ada sejak lama: halaman melaporkan BYTIFY_MAIN
    // begitu <video> betul-betul berjalan. Yang ditambahkan sinyal GAGAL.
    val majuServer: (String?) -> Unit = { dariUrl ->
        Log.i(
            EMBED_TAG,
            "pindah server diminta oleh ${dariUrl ?: "watchdog"}; " +
                "aktif=$activeEmbedUrl indeks=$indeksServer/${kandidat.size}",
        )
        // Laporan dari WebView yang sudah ditinggalkan diabaikan. Tanpa penjaga
        // ini satu kegagalan basi bisa melompati server yang baru dipasang,
        // dan daftar kandidat terkuras tanpa pernah dicoba sungguhan.
        // Satu alamat hanya boleh memajukan server SEKALI. Laporan gagal
        // datang berpasangan (onReceivedError lalu onReceivedHttpError) dan
        // keduanya tiba sebelum recomposition, sehingga penjaga
        // `dariUrl == activeEmbedUrl` saja masih meloloskan keduanya dan dua
        // server terlompati dalam 11 ms.
        if (pilihanManual) {
            Log.i(EMBED_TAG, "server dipilih manual; perpindahan otomatis dilewati")
        } else if ((dariUrl == null || dariUrl == activeEmbedUrl) && dariUrl != sudahDilewati) {
            sudahDilewati = dariUrl
            if (indeksServer + 1 < kandidat.size) indeksServer += 1 else serverHabis = true
        }
    }
    // WebViewClient dibuat SEKALI per server, jadi lambda yang ditangkapnya
    // membeku bersama nilai yang dibacanya — masalah yang sama dengan
    // pointerInput di BottomBars.kt. Dibaca lewat State supaya selalu terkini.
    val majuTerkini by rememberUpdatedState(majuServer)

    // Kuncinya ALAMAT server yang aktif, bukan `kandidat.size`.
    //
    // Server turunan datang BELAKANGAN — diturunkan dari `watch_aliases` lewat
    // pembacaan database, jadi daftarnya bertambah beberapa detik setelah
    // pemutar terbuka. Dengan `kandidat.size` sebagai kunci, kedatangan itu
    // memulai ulang watchdog dan MENGHAPUS penanda "sudah berjalan" untuk
    // server yang saat itu sedang memutar film. Terukur di Galaxy A36:
    // Inception berjalan di server 1 pada detik ke-5, lalu dibuang pada detik
    // ke-18 dan pemutar menelusuri sampai server ke-6 yang layarnya hitam.
    //
    // Alamat aktif hanya berubah kalau servernya memang berganti, jadi
    // penandanya tidak pernah hilang karena daftar bertambah.
    LaunchedEffect(playKey, useEmbedPlayer, activeEmbedUrl) {
        if (!useEmbedPlayer || kandidat.isEmpty()) return@LaunchedEffect
        // Server baru berarti belum berjalan dan belum siap. Tanpa reset ini,
        // server yang sempat berhasil membuat watchdog bungkam untuk server
        // penggantinya.
        videoBerjalan = false
        serverSiap = false
        serverKemajuan = false
        trekServer = emptyList()
        trekAktif = null

        // Ambangnya dibaca ULANG tiap putaran, bukan ditetapkan di awal:
        // sebuah server bisa melaporkan sumbernya menempel di detik ke-15, dan
        // sejak saat itu ia pantas ditunggu sampai BATAS_SABAR_MS, bukan
        // dibuang karena keputusan yang diambil sebelum laporannya datang.
        val mulai = System.currentTimeMillis()
        while (!videoBerjalan) {
            delay(500)
            if (videoBerjalan) break
            val lewat = System.currentTimeMillis() - mulai
            // Server yang dimuat langsung menunggu ketukan, jadi jamnya
            // berjalan lebih lambat. Aturannya sama persis dengan yang dipakai
            // EmbedVideoStage untuk memutuskan jalur `langsung`.
            val acuan = serverAktif?.referer
            val langsungAktif = acuan.isNullOrBlank() ||
                acuan.equals(serverAktif?.url, ignoreCase = true)
            val batas = when {
                serverSiap || serverKemajuan -> BATAS_SABAR_MS
                langsungAktif -> profilUntuk(serverAktif?.url).ambangMs
                else -> BATAS_TUNGGU_MS
            }
            if (lewat >= batas) {
                Log.w(
                    EMBED_TAG,
                    "server '${serverAktif?.nama}' tak berjalan dalam $lewat ms " +
                        "(siap=$serverSiap, kemajuan=$serverKemajuan, ambang=$batas); dilewati",
                )
                majuServer(activeEmbedUrl)
                break
            }
        }
    }
    // Penandanya adalah NIAT memutar (playWhenReady), bukan isPlaying.
    // isPlaying berkedip mati-nyala setiap kali aliran menyangga — terbukti di
    // log: "terdaftar" lalu "dilepas" 16 ms kemudian — sehingga jendela
    // floating nyaris tidak pernah sempat diizinkan. playWhenReady bertahan
    // melintasi buffering dan hanya padam kalau pemutaran benar-benar dijeda.
    // Tombol jeda/putar di jendela floating. Untuk pemutar langsung ia menukar
    // playWhenReady; untuk embed ia mengirim ketukan sungguhan ke tengah
    // WebView, karena pemutar di dalam iframe hanya mengenal sentuhan.
    var webEmbed by remember(playKey) { mutableStateOf<WebView?>(null) }
    LaunchedEffect(useEmbedPlayer, playKey) {
        if (useEmbedPlayer) {
            videoBerjalan = false
            while (true) {
                val web = webEmbed
                if (web != null) {
                    ModeFloating.pasangKendali(videoBerjalan) { ketukTengah(web) }
                }
                delay(500)
            }
        }
        while (true) {
            videoBerjalan = exoPlayer.playWhenReady
            ModeFloating.pasangKendali(exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = !exoPlayer.playWhenReady
            }
            delay(500)
        }
    }

    // Selama panggilan, aplikasi telepon memegang fokus audio secara eksklusif
    // dan pemutar mana pun yang berkelakuan baik akan bungkam. Larangannya
    // tidak boleh dilanggar — tetapi membiarkannya tanpa keterangan membuat
    // pemutar terlihat rusak, dan itu yang diperbaiki di sini.
    val konteksPanggilan = LocalContext.current
    LaunchedEffect(Unit) {
        var sebelumnya = false
        while (true) {
            val kini = sedangMenelpon(konteksPanggilan)
            if (kini && !sebelumnya) app.toast(PESAN_SEDANG_MENELPON)
            sebelumnya = kini
            delay(1500)
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
                    referer = refererAktif,
                    subtitleUrl = activeSubUrl,
                    onToggleFullscreen = {},
                    onMulaiBerjalan = { videoBerjalan = true },
                    onGagal = { majuTerkini(it) },
                    onSiap = { serverSiap = true },
                    onKemajuan = { serverKemajuan = true },
                    onTrek = { trekServer = it },
                    onWebView = { webEmbed = it },
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
                    referer = refererAktif,
                    subtitleUrl = activeSubUrl,
                    onToggleFullscreen = { toggleFullscreen(context, false) },
                    onMulaiBerjalan = { videoBerjalan = true },
                    onGagal = { majuTerkini(it) },
                    onSiap = { serverSiap = true },
                    onKemajuan = { serverKemajuan = true },
                    onTrek = { trekServer = it },
                    onWebView = { webEmbed = it },
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
            if (hostRoom) {
                IconButton(onClick = { together.mulaiBareng() }) {
                    Icon(Icons.Rounded.Groups, contentDescription = "Beri aba-aba nonton bareng", tint = BytifyColors.Gold)
                }
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
                    referer = refererAktif,
                    subtitleUrl = activeSubUrl,
                    onToggleFullscreen = { toggleFullscreen(context, true) },
                    onMulaiBerjalan = { videoBerjalan = true },
                    onGagal = { majuTerkini(it) },
                    onSiap = { serverSiap = true },
                    onKemajuan = { serverKemajuan = true },
                    onTrek = { trekServer = it },
                    onWebView = { webEmbed = it },
                )
            }
        } else {
            VideoStage(exoPlayer, fullscreen = false, buffering = buffering, error = playbackError, onRetry = retry)
        }

        if (useEmbedPlayer && kandidat.size > 1) {
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
                kandidat.forEachIndexed { urutan, pilihan ->
                    val aktif = urutan == indeksServer
                    FilterChip(
                        selected = aktif,
                        onClick = {
                            if (!aktif) {
                                serverHabis = false
                                // Pilihan MANUAL mematikan perpindahan otomatis.
                                //
                                // Penelusuran otomatis ada untuk menemukan server
                                // yang bekerja ketika pengguna belum menyatakan
                                // apa pun. Begitu ia menekan sebuah chip, ia
                                // sudah menyatakannya — dan memindahkannya
                                // diam-diam ke server lain membuat pilihannya
                                // tidak berarti. Terukur saat menguji: chip yang
                                // ditekan melaporkan host server LAIN, karena
                                // watchdog sudah memindahkannya sebelum server
                                // itu sempat menempelkan sumbernya.
                                pilihanManual = true
                                indeksServer = urutan
                            }
                        },
                        label = { Text(pilihan.nama.uppercase()) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = Color.LightGray,
                            selectedContainerColor = BytifyColors.Gold,
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
            // Keterangan failover. Tanpa ini pergantian server otomatis terlihat
            // seperti pemutar yang berkedip sendiri tanpa sebab yang jelas.
            Text(
                when {
                    serverHabis ->
                        "Seluruh ${kandidat.size} server sudah dicoba dan tidak ada yang berputar. " +
                            "Ketuk salah satu chip untuk mencoba lagi."
                    videoBerjalan -> "Server ${indeksServer + 1} dari ${kandidat.size} berjalan."
                    serverSiap ->
                        "Server ${indeksServer + 1} dari ${kandidat.size} sudah dapat sumbernya, " +
                            "sedang menyiapkan video."
                    else ->
                        "Mencoba server ${indeksServer + 1} dari ${kandidat.size}. " +
                            "Kalau tidak berputar, Bytify berpindah sendiri."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (serverHabis) Color(0xFFFF8A80) else Color.Gray,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
            )
        }

        if (useEmbedPlayer && (subtitles.isNotEmpty() || trekServer.isNotEmpty())) {
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
                    val aktif = alamat == activeSubUrl && trekAktif == null
                    FilterChip(
                        selected = aktif,
                        onClick = {
                            activeSubUrl = alamat
                            // Mematikan trek server saat memilih subtitle kita
                            // sendiri; dua lapisan teks sekaligus tidak terbaca.
                            if (trekAktif != null) {
                                trekAktif = null
                                webEmbed?.evaluateJavascript(
                                    "window.postMessage({bytifyTrek: -1}, '*')", null,
                                )
                            }
                        },
                        label = { Text(nama) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = Color.LightGray,
                            selectedContainerColor = BytifyColors.Gold,
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
                // Trek yang dibawa ALIRAN SERVER. Untuk film Indonesia ini
                // sering satu-satunya subtitle yang ada: OpenSubtitles belum
                // punya entri untuk rilis Indonesia baru (terukur: 0 berkas
                // untuk `tmdb 1525059` dan `tmdb 1589663`), sementara
                // alirannya sendiri kerap sudah membawa treknya.
                trekServer.forEach { trek ->
                    val nama = trek.label.ifBlank { trek.lang.uppercase() }
                        .ifBlank { "Trek ${trek.i + 1}" }
                    FilterChip(
                        selected = trekAktif == trek.i,
                        onClick = {
                            trekAktif = trek.i
                            activeSubUrl = null
                            webEmbed?.evaluateJavascript(
                                "window.postMessage({bytifyTrek: ${trek.i}}, '*')", null,
                            )
                        },
                        label = { Text("$nama (server)") },
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
    onMulaiBerjalan: () -> Unit = {},
    onWebView: (WebView) -> Unit = {},
    /** Dipanggil dengan [videoUrl] bila server ini jelas tidak bisa dipakai. */
    onGagal: (String) -> Unit = {},
    /** Sumber sudah menempel di elemen video; server ini layak ditunggu. */
    onSiap: () -> Unit = {},
    /** Frame pemutar bersarang sudah dimuat; server ini sedang bekerja. */
    onKemajuan: () -> Unit = {},
    /** Trek subtitle yang dibawa server ini sendiri. */
    onTrek: (List<TrekServer>) -> Unit = {},
) {
    // Player embed (videonode dan sejenisnya) menolak permintaan tanpa Referer
    // dan mengalihkannya ke beranda situs sumber. Kirim halaman film sebagai
    // Referer, lalu tahan navigasi yang keluar dari domain player.
    val sudahMain = remember(videoUrl) { AtomicBoolean(false) }

    // Alamat server TANPA fragmen.
    //
    // Peramban tidak pernah mengirim fragmen dalam permintaan, jadi
    // membandingkan URL permintaan dengan [videoUrl] apa adanya TIDAK PERNAH
    // cocok untuk server yang kuncinya ada di fragmen — anikai memakai
    // `/watch#<seal>.<episode>.<server>.<audio>`. Akibatnya terukur di Galaxy
    // A36: dokumen UTAMA anikai dikira frame bersarang, sehingga pengalihan
    // dan galat jaringan pada halaman itu tidak pernah dilaporkan gagal.
    val urlUtama = remember(videoUrl) { videoUrl.substringBefore('#').lowercase() }

    // Apakah ADA frame bersarang yang dokumennya sudah kita sisipi.
    //
    // Pada anikai elemen <video> hidup di dalam iframe megaplay, bukan di
    // dokumen luar — jadi dokumen luar SELALU melapor "tak ada video" dan
    // ambang 18 detik membuang server yang sebetulnya sedang merakit pemutar.
    // Terukur: anikai melekatkan sumbernya pada detik ke-33.
    val adaFrameDalam = remember(videoUrl) { AtomicBoolean(false) }

    // Apakah dokumen UTAMA server ini sudah kita ambil dan sisipi sendiri.
    //
    // Kalau sudah, server itu jelas menjawab dan isinya HTML pemutar — jadi
    // galat jaringan berikutnya pada alamat yang sama adalah permintaan kembar
    // atau yang dibatalkan, bukan server yang mati. Terukur di Galaxy A36:
    // cinesrc dibuang oleh laporan seperti itu meskipun dokumennya sudah
    // terkirim utuh (19.711 bait). Sejak itu hanya watchdog yang boleh
    // membuangnya.
    val dokumenUtamaDisisipi = remember(videoUrl) { AtomicBoolean(false) }

    // Apakah ADA frame yang sudah melaporkan sumbernya menempel.
    //
    // Perlu dilacak di sini, bukan di dalam skrip, karena GATE_BYPASS_JS
    // disuntikkan ke SETIAP dokumen termasuk frame bersarang — jadi tiap frame
    // punya penanda sendiri. Terukur di perangkat: frame dalam melaporkan
    // BYTIFY_SIAP, lalu empat detik kemudian frame LUAR (yang memang tidak
    // pernah memuat video) melaporkan BYTIFY_DIAM dan server yang sebetulnya
    // sudah dapat sumber ikut dibuang. Bukti positif mengalahkan ketiadaan
    // bukti, jadi DIAM diabaikan setelah SIAP.
    val sudahSiap = remember(videoUrl) { AtomicBoolean(false) }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    // Apakah kegagalan pada [alamat] berarti SERVER INI yang gagal.
    //
    // Dua penjaga sekaligus. Pertama, alamatnya harus persis alamat server —
    // halaman player memuat belasan permintaan lain (iklan, pelacak, font) dan
    // yang gagal di antaranya tidak berarti apa-apa. Kedua, begitu film sudah
    // berjalan laporan apa pun diabaikan: setelah video jalan, permintaan yang
    // gagal tidak boleh membuat pemutar berpindah server dan memulai dari nol.
    val langsung = referer.isNullOrBlank() || referer.equals(videoUrl, ignoreCase = true)

    val gagalUrlIni: (String?, Boolean) -> Boolean = { alamat, frameUtama ->
        // Di jalur LANGSUNG, servernya ADALAH frame utama, jadi hanya galat
        // frame utama yang berarti server itu gagal. Tanpa syarat ini satu
        // permintaan gagal ke alamat yang sama — pemutar pihak ketiga memuat
        // dokumennya sendiri lebih dari sekali — membuang server yang sedang
        // bekerja. Terukur di Galaxy A36: cinesrc dilewati DUA KALI dalam 11 ms
        // oleh jalur ini, sebelum sempat menempelkan sumbernya.
        //
        // Di jalur pembungkus, servernya hidup di dalam iframe sehingga domain
        // yang mati muncul sebagai galat SUB-frame; di sana URL-nya yang
        // menentukan, bukan jenis framenya.
        !sudahMain.get() && !sudahSiap.get() && !adaFrameDalam.get() &&
            !dokumenUtamaDisisipi.get() &&
            alamat != null && alamat.substringBefore('#').equals(urlUtama, ignoreCase = true) &&
            (!langsung || frameUtama)
    }

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
        // Jembatan bytifyKirimSub hanya ada di pembungkus iframe. Tanpa
        // pembungkus (jalur langsung), cue dikirim ke dokumennya sendiri —
        // penampil subtitle di GATE_BYPASS_JS mendengarkan pesan yang sama
        // dan meneruskannya ke frame anak.
        web.evaluateJavascript(
            "(window.bytifyKirimSub ? window.bytifyKirimSub($cue) " +
                ": window.postMessage({bytifySub: $cue}, '*'));",
            null,
        )
    }
    val allowedDomains = remember(videoUrl, referer) {
        setOfNotNull(baseDomainOf(videoUrl), referer?.let(::baseDomainOf))
    }

    /**
     * Apakah server ini dimuat sebagai HALAMAN UTAMA, bukan di dalam iframe.
     *
     * Pembungkus iframe ada untuk satu alasan: embed LK21 menolak permintaan
     * yang tidak membawa halaman filmnya sebagai Referer. Server pihak ketiga
     * berkunci TMDB id tidak punya syarat itu — Referer-nya alamatnya sendiri —
     * sehingga membungkusnya justru membuat keadaan yang TIDAK pernah terjadi
     * di situs aslinya: pemutar berjalan di dalam frame.
     *
     * Itu yang membuat judul yang berputar di cinevo/123movies gagal di sini.
     * Terbukti di browser: vidlink memutar Inception dengan lancar ketika
     * dibuka sebagai halaman biasa. Jadi untuk server seperti itu alamatnya
     * dimuat apa adanya, persis seperti yang dilakukan situs asalnya.
     */
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
                                onMulaiBerjalan()
                                Log.i(EMBED_TAG, "film berjalan; ketukan otomatis dihentikan")
                            } else if (pesan.contains("BYTIFY_SIAP")) {
                                // Sumber sudah menempel di elemen video. Server
                                // ini bekerja walau filmnya belum bergerak,
                                // jadi pemutar menunggunya lebih lama.
                                Log.i(EMBED_TAG, "server menyiapkan sumber: $pesan")
                                sudahSiap.set(true)
                                onSiap()
                            } else if (pesan.contains("BYTIFY_TREK:")) {
                                // Trek subtitle milik server. Untuk film
                                // Indonesia ini sering satu-satunya sumber,
                                // karena OpenSubtitles belum punya apa pun
                                // untuk rilis baru.
                                val isi = pesan.substringAfter("BYTIFY_TREK:").trim()
                                val baris = runCatching {
                                    Json { ignoreUnknownKeys = true }
                                        .decodeFromString<List<TrekServer>>(isi)
                                }.getOrDefault(emptyList())
                                if (baris.isNotEmpty()) {
                                    Log.i(EMBED_TAG, "trek server: $isi")
                                    onTrek(baris)
                                }
                            } else if (pesan.contains("BYTIFY_DIAM")) {
                                if (!sudahMain.get() && !sudahSiap.get() &&
                                    !adaFrameDalam.get()
                                ) {
                                    Log.w(EMBED_TAG, "tidak ada elemen video sama sekali: $videoUrl")
                                    onGagal(videoUrl)
                                }
                            } else if (pesan.contains("BYTIFY_GAGAL")) {
                                // Halaman player sendiri melaporkan tak punya
                                // sumber untuk judul ini. Tidak ada gunanya
                                // menunggu ambang waktu: langsung ke server
                                // berikutnya.
                                if (!sudahMain.get()) {
                                    Log.w(EMBED_TAG, "player melapor tanpa sumber: $videoUrl")
                                    onGagal(videoUrl)
                                }
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
                                    // Pengalihan keluar domain player BISA berarti
                                    // dua hal yang sangat berbeda, dan membedakannya
                                    // menentukan: gerbang Referer gagal (server
                                    // memang tidak akan berputar), ATAU sekadar
                                    // popunder iklan yang baru saja kita tahan.
                                    //
                                    // Pembedanya waktu: gerbang Referer gagal
                                    // SEBELUM ada sumber, sedangkan popunder muncul
                                    // kapan saja — sering justru setelah pemutar
                                    // hidup. Terukur di Galaxy A36: hydrax melapor
                                    // BYTIFY_SIAP, lalu 0,4 detik kemudian iklan
                                    // decafeligiblyhad.com ditahan, dan server yang
                                    // SUDAH punya sumber ikut dibuang.
                                    //
                                    // Kalau server ini ternyata benar-benar mati
                                    // setelah siap, watchdog BATAS_SABAR_MS tetap
                                    // menangkapnya — jadi penjaga ini tidak bisa
                                    // membuat pemutar menggantung.
                                    // Dokumen server yang SUDAH kita sisipi menutup
                                    // kemungkinan "gerbang Referer gagal": gerbang
                                    // itu gagal dengan mengalihkan permintaan
                                    // DOKUMENNYA, sebelum ada halaman pemutar sama
                                    // sekali. Kalau halamannya sudah terkirim utuh,
                                    // navigasi keluar yang kita tahan pasti iklan.
                                    // Terukur di Galaxy A36: cinesrc dibuang oleh
                                    // popunder `invl.me` enam detik setelah
                                    // dokumennya (19.711 bait) terkirim.
                                    if (!sudahMain.get() && !sudahSiap.get() &&
                                        !dokumenUtamaDisisipi.get()
                                    ) {
                                        onGagal(videoUrl)
                                    }
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
                            //
                            // Hanya untuk server yang MEMBUTUHKANNYA. Server pihak
                            // ketiga dimuat apa adanya sebagai halaman utama — lihat
                            // [langsung]; membungkusnya justru yang membuat judul
                            // yang berputar di situs asalnya gagal di sini.
                            if (!langsung && request?.isForMainFrame == true && !referer.isNullOrBlank() &&
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
                            // Situs BERSESI tidak boleh diproksikan.
                            //
                            // Dokumen yang kita ambil sendiri dengan OkHttp tidak
                            // membawa cookie WebView, jadi aplikasi yang
                            // menyiapkan sesinya di sisi klien gagal bootstrap
                            // dan MEMUAT ULANG dirinya terus-menerus. Terukur di
                            // Galaxy A36 pada veloflix: dokumennya disisipi
                            // berkali-kali dalam hitungan detik dan iframe
                            // `zxcstream.cc` tidak pernah muncul sama sekali,
                            // sehingga server itu dibuang watchdog seolah-olah
                            // rusak.
                            //
                            // Untuk host seperti ini WebView memuat sendiri —
                            // kehilangan penyuntikan kita, tetapi veloflix
                            // memang membawa subtitle Indonesianya sendiri.
                            // Hanya dokumen UTAMA-nya yang dibiarkan: frame
                            // pemutar di dalamnya (zxcstream) TETAP disisipi,
                            // karena tanpa skrip tidak ada yang bisa melaporkan
                            // BYTIFY_SIAP — dan server yang tidak bisa
                            // membuktikan dirinya bekerja selalu dibuang
                            // watchdog, betapapun sehatnya.
                            if (!profilUntuk(reqUrl).proksiDokumen) {
                                return super.shouldInterceptRequest(view, request)
                            }
                            val mintaDokumen = request?.requestHeaders
                                ?.entries?.firstOrNull { it.key.equals("Accept", true) }
                                ?.value.orEmpty().contains("text/html", true)
                            if (request != null && request.method.equals("GET", true) &&
                                (reqUrl == urlUtama || mintaDokumen)
                            ) {
                                val alamat = request.url.toString()
                                val asal = if (reqUrl == urlUtama) referer else videoUrl
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
                                            // Hanya dokumen player UTAMA yang
                                            // menandakan server gagal; frame
                                            // bersarang boleh dialihkan tanpa
                                            // berarti apa-apa. shouldIntercept
                                            // berjalan di utas latar, jadi
                                            // laporannya dikirim lewat post().
                                            if (reqUrl == urlUtama &&
                                                !sudahMain.get() && !sudahSiap.get()
                                            ) {
                                                view?.post { onGagal(videoUrl) }
                                            }
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
                                        val skrip = skripUntuk(profilUntuk(alamat))
                                        val titik = badan.indexOf("</head>", ignoreCase = true)
                                        val disisipi = if (titik >= 0) {
                                            badan.substring(0, titik) + skrip + badan.substring(titik)
                                        } else {
                                            skrip + badan
                                        }
                                        Log.i(EMBED_TAG, "disisipi: $alamat (${badan.length} bait)")
                                        // Frame bersarang yang kita sisipi adalah
                                        // BUKTI server ini sedang merakit pemutar.
                                        // Sejak saat itu ia pantas ditunggu selama
                                        // server yang sudah melekatkan sumber.
                                        if (!alamat.substringBefore('#')
                                                .equals(urlUtama, ignoreCase = true)
                                        ) {
                                            if (adaFrameDalam.compareAndSet(false, true)) {
                                                view?.post { onKemajuan() }
                                            }
                                        } else {
                                            dokumenUtamaDisisipi.set(true)
                                        }
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
                            view?.let(onWebView)
                            Log.i(EMBED_TAG, "selesai memuat: $url")
                            // Pembersih iklan hanya untuk halaman bergerbang iklan
                            // (LK21). Ia membuang elemen ber-class "overlay",
                            // "popup", dan "banner" — justru nama yang dipakai
                            // pemutar modern untuk KONTROLNYA SENDIRI, sehingga
                            // menjalankannya di sana merusak pemutar yang
                            // sebetulnya sehat.
                            val kamar = profilUntuk(url)
                            if (!langsung && kamar.pembersihIklan) {
                                view?.evaluateJavascript(EMBED_CLEANUP_JS, null)
                            }
                            // Ketukan otomatis berlaku di KEDUA jalur, tetapi
                            // dengan jeda berbeda. Dugaan lama bahwa pemutar
                            // pihak ketiga selalu berjalan sendiri SALAH:
                            // vidlink menunggu gestur sungguhan sebelum membuat
                            // sumbernya sama sekali. Yang benar-benar berbahaya
                            // bukan ketukannya, melainkan mengetuk pemutar yang
                            // sudah berjalan — dan itu dicegah oleh [sudahSiap].
                            // Dokumen yang tidak diproksikan tetap perlu diurus:
                            // tinggi iframe pemutarnya dan panduan veloflix.
                            if (!profilUntuk(url).proksiDokumen) {
                                val web = view
                                repeat(20) { ke ->
                                    web?.postDelayed({
                                        web.evaluateJavascript(SUNTIK_LUAR_JS, null)
                                    }, 700L * (ke + 1))
                                }
                            }
                            if (kamar.jedaKetukMs > 0L) {
                                view?.let {
                                    ketukOtomatis(it, sudahMain, 0, sudahSiap, kamar.jedaKetukMs)
                                }
                            }
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
                            // Server embed hidup di dalam IFRAME, jadi domain
                            // yang mati muncul sebagai galat sub-frame pada
                            // alamat server — bukan pada frame utama. Jadi yang
                            // dicocokkan URL-nya, bukan jenis framenya.
                            // Terukur: player.autoembed.cc tidak resolve sama
                            // sekali, dan tanpa jalur ini server seperti itu
                            // baru dilewati setelah 18 detik penuh.
                            if (gagalUrlIni(request?.url?.toString(), request?.isForMainFrame == true)) {
                                onGagal(videoUrl)
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            Log.w(EMBED_TAG, "HTTP ${errorResponse?.statusCode} pada ${request?.url}")
                            // Terukur: vidsrc.cc menjawab 522 (origin tumbang
                            // di belakang Cloudflare) untuk 60 dari 60 judul.
                            if (gagalUrlIni(request?.url?.toString(), request?.isForMainFrame == true)) {
                                onGagal(videoUrl)
                            }
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