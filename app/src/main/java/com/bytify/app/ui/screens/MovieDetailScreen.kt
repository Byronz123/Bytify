package com.bytify.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.bytify.app.data.movie.KetersediaanResmi
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.launch

/**
 * Nama Indonesia untuk jenis langganan yang dipakai TMDB/JustWatch. Urutannya
 * di [URUT_JENIS] sengaja menaruh yang tidak menambah biaya bagi pengguna lebih
 * dulu: sudah termasuk langganan, lalu gratis, baru yang berbayar per judul.
 */
private val LABEL_JENIS = mapOf(
    "flatrate" to "Termasuk langganan",
    "free" to "Gratis",
    "ads" to "Gratis dengan iklan",
    "rent" to "Sewa",
    "buy" to "Beli",
)
private val URUT_JENIS = listOf("flatrate", "free", "ads", "rent", "buy")

/** Atribusi sumber katalog; dulu dikirim per film padahal nilainya seragam. */
private const val LISENSI_BAWAAN = "LK21 Stream Adapter"

private const val TRAILER_TAG = "BytifyTrailer"

/**
 * Origin yang dipakai halaman pembungkus trailer.
 *
 * Domain Bytify sendiri, dan memang domain yang benar-benar ada — meski
 * halamannya tidak pernah diambil dari jaringan (dijawab sendiri oleh
 * `shouldInterceptRequest`), origin inilah yang dilihat YouTube sebagai
 * penyemat.
 */
private const val ASAL_TRAILER = "https://bytify-api.pages.dev"

/**
 * Pembungkus beriframe untuk pemutar YouTube.
 *
 * Tinggi iframe dipaksa dengan posisi absolut, bukan persen: tinggi persen
 * tidak teresolusi di dalam WebView dan membuat iframe setinggi 0 piksel —
 * jebakan yang sama yang sudah tercatat untuk pemutar film.
 */
private fun halamanTrailer(alamat: String): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:#000;width:100%;height:100%;overflow:hidden}
iframe{position:absolute;top:0;left:0;right:0;bottom:0;width:100%;height:100vh;border:0;display:block}</style>
<script>
// Jaring pengaman tinggi iframe, sama seperti di pembungkus pemutar film.
// Tanpa ini iframe berakhir setinggi 0 piksel dan layar tampak hitam —
// persis jebakan yang sudah tercatat di CLAUDE.md, dan trailer sempat
// terkena justru karena memakai height:100% alih-alih 100vh + penyesuai ini.
function bytifyFit() {
  var f = document.querySelector('iframe');
  var h = window.innerHeight || document.documentElement.clientHeight || 0;
  if (f && h > 0 && f.offsetHeight !== h) { f.style.height = h + 'px'; }
}
window.addEventListener('resize', bytifyFit);
window.addEventListener('load', bytifyFit);
setInterval(bytifyFit, 400);
</script>
</head><body><iframe src="$alamat"
 allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
 allowfullscreen></iframe></body></html>
""".trimIndent()

/**
 * Id video YouTube dari berbagai bentuk tautan trailer yang ada di katalog.
 *
 * Terukur pada catalog.json: dari 20.400 film bertrailer, 20.095 memakai
 * `watch?v=`, 200 memakai `youtu.be/`, dan 105 bentuk youtube.com lain —
 * seluruhnya YouTube, jadi satu pengurai sudah cukup.
 *
 * Mengembalikan null untuk bentuk yang tidak dikenali, supaya pemanggilnya
 * bisa jatuh ke cara lama (membuka di aplikasi luar) alih-alih menyajikan
 * pemutar kosong.
 */
private fun idYouTube(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val id = when {
        host == "youtu.be" -> uri.pathSegments.firstOrNull()
        host.endsWith("youtube.com") -> when {
            uri.path?.startsWith("/embed/") == true -> uri.pathSegments.getOrNull(1)
            uri.path?.startsWith("/shorts/") == true -> uri.pathSegments.getOrNull(1)
            else -> uri.getQueryParameter("v")
        }
        else -> null
    }
    // Id YouTube hanya huruf, angka, garis bawah, dan tanda hubung. Memeriksanya
    // mencegah potongan jalur lain ikut tersisip ke URL embed.
    return id?.takeIf { kandidat ->
        kandidat.isNotBlank() && kandidat.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }
}

/**
 * Trailer diputar DI DALAM aplikasi lewat rute embed resmi YouTube.
 *
 * Dibuat terpisah dari `EmbedVideoStage` di pemutar film, dan itu disengaja:
 * pemutar film membawa pelewat gerbang iklan, penyuntik subtitle, dan ketukan
 * otomatis — semuanya tidak diperlukan di sini dan justru menambah risiko pada
 * jalur yang sudah terbukti. YouTube cukup dimuat apa adanya.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LapisanTrailer(idVideo: String, judul: String, onTutup: () -> Unit) {
    // Halaman PEMBUNGKUS beralamat di domain Bytify, bukan di youtube.com.
    //
    // Terukur di Galaxy A36, dan inilah kuncinya: ketika pembungkusnya sendiri
    // beralamat youtube.com, YouTube melihat dirinya menyemat dirinya dan
    // menolak dengan "Error code: 152 - 4" untuk SETIAP judul (Inception dan
    // Interstellar sama-sama ditolak, jadi ini sistematis, bukan batasan
    // per-video). Memuat /embed/<id> langsung juga ditolak, dengan 153.
    // Penyemat harus tampak sebagai pihak ketiga yang wajar — persis seperti
    // situs mana pun yang menanam pemutar YouTube.
    val alamatPembungkus = remember(idVideo) { "$ASAL_TRAILER/trailer?v=$idVideo" }
    val alamatEmbed = remember(idVideo) {
        "https://www.youtube.com/embed/$idVideo" +
            "?autoplay=1&playsinline=1&rel=0&modestbranding=1" +
            "&origin=" + java.net.URLEncoder.encode(ASAL_TRAILER, "UTF-8")
    }
    BackHandler(onBack = onTutup)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // Tanpa ini autoplay ditahan sampai ada sentuhan,
                            // dan trailer tampak macet di bingkai pertama.
                            mediaPlaybackRequiresUserGesture = false
                            // UA bawaan WebView membawa token "; wv)" dan
                            // sebagian penyedia memperlakukannya berbeda.
                            // Disamakan dengan pemutar film supaya kedua jalur
                            // menghadap layanan dengan identitas yang sama.
                            userAgentString = EMBED_USER_AGENT
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                Log.i(TRAILER_TAG, "konsol: ${msg?.message()}")
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                Log.i(TRAILER_TAG, "selesai memuat: $url")
                            }

                            /**
                             * Dokumen UTAMA dijawab sendiri dengan pembungkus
                             * beriframe, sehingga halamannya benar-benar
                             * beralamat di youtube.com dan iframe di dalamnya
                             * membawa Origin serta Referer yang sah.
                             *
                             * loadDataWithBaseURL TIDAK bisa dipakai di sini —
                             * sudah dicoba dan gagal di perangkat: Chromium
                             * memberi dokumen hasil data origin buram, jadi
                             * YouTube menolaknya tanpa satu pun pesan konsol
                             * maupun galat, dan yang tersisa hanya layar hitam.
                             *
                             * Hanya frame UTAMA yang dicegat; permintaan iframe
                             * ke alamat yang sama diteruskan apa adanya ke
                             * YouTube, kalau tidak pembungkusnya memanggil
                             * dirinya sendiri tanpa henti.
                             */
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): android.webkit.WebResourceResponse? {
                                if (request?.isForMainFrame != true) return null
                                if (request.url.toString() != alamatPembungkus) return null
                                return android.webkit.WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    java.io.ByteArrayInputStream(
                                        halamanTrailer(alamatEmbed).toByteArray(Charsets.UTF_8),
                                    ),
                                )
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                Log.w(
                                    TRAILER_TAG,
                                    "galat ${if (request?.isForMainFrame == true) "UTAMA" else "sub"}: " +
                                        "${request?.url} -> ${error?.description}",
                                )
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?,
                            ) {
                                Log.w(
                                    TRAILER_TAG,
                                    "HTTP ${errorResponse?.statusCode} pada ${request?.url}",
                                )
                            }
                        }
                        // Alamat SUNGGUHAN yang dimuat, bukan dokumen data:
                        // dengan begitu halamannya punya origin https yang sah.
                        // Isinya dijawab sendiri oleh shouldInterceptRequest di
                        // atas, jadi tidak ada permintaan jaringan untuk ini.
                        loadUrl(alamatPembungkus)
                    }
                },
                onRelease = { web ->
                    // about:blank dulu, baru destroy: tanpa itu suara trailer
                    // bisa terus berbunyi setelah lapisannya ditutup.
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    web.destroy()
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            IconButton(onClick = onTutup, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Rounded.Close, contentDescription = "Tutup trailer $judul", tint = Color.White)
            }
        }
    }
}

@Composable
fun MovieDetailScreen(movieId: String) {
    val app = appState()
    val repository = app.container.movies
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val movie by repository.movie(movieId).collectAsState(initial = null)
    val favoriteIds by repository.favoriteIds.collectAsState(initial = emptySet())

    val item = movie
    if (item == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Movie, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Film tidak ditemukan di katalog lokal.", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { app.navController.popBackStack() }, modifier = Modifier.padding(top = 12.dp)) {
                Text("Kembali")
            }
        }
        return
    }

    LaunchedEffect(item.id) {
        repository.enrichIfNeeded(item)
    }

    // Ketersediaan resmi dibaca BERTITIK per judul, bukan dari daftar penuh di
    // memori: tabelnya puluhan ribu baris dan menahannya sekaligus adalah
    // jalan yang pernah mematikan aplikasi dengan OutOfMemoryError di A36.
    var resmi by remember(item.id) { mutableStateOf<KetersediaanResmi?>(null) }
    LaunchedEffect(item.id) {
        resmi = runCatching { app.container.ketersediaan.ketersediaan(item.id) }.getOrNull()
    }

    // Trailer diputar di dalam aplikasi; id-nya diurai sekali di sini supaya
    // tombolnya tahu sejak awal apakah jalur embed bisa dipakai.
    val idTrailer = remember(item.trailerUrl) { idYouTube(item.trailerUrl) }
    var trailerDibuka by remember(item.id) { mutableStateOf(false) }

    val isFavorite = item.id in favoriteIds
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomInset.current + 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { app.navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
            }
            Text("Detail film", style = MaterialTheme.typography.titleLarge)
        }

        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = "Gambar ${item.title}",
            modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.headlineMedium)
                val meta = listOfNotNull(item.releaseYear?.toString(), item.durationMin?.let { "$it menit" })
                    .joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                scope.launch { repository.setFavorite(item, !isFavorite) }
            }) {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Hapus favorit" else "Tambah favorit",
                    tint = if (isFavorite) BytifyColors.GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.rating?.let { AssistChip(onClick = {}, label = { Text("★ ${"%.1f".format(java.util.Locale.ROOT, it)}") }) }
            item.language?.let { AssistChip(onClick = {}, label = { Text(it.uppercase().take(12)) }) }
            if (item.isSeries) AssistChip(onClick = {}, label = { Text("${item.episodes.size} episode") })
            if (item.subtitles.isNotEmpty() || item.episodes.any { it.subtitles.isNotEmpty() }) AssistChip(onClick = {}, label = { Text("CC") })
        }

        if (item.isPlayable) {
            Button(
                onClick = {
                    app.container.player.pause()
                    app.navigate(Routes.moviePlayer(item.id, item.episodes.firstOrNull()?.id))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(if (item.isSeries) "  Putar episode 1" else "  Putar film")
            }
        }

        resmi?.takeIf { !it.kosong }?.let { tersedia ->
            Text(
                "Tonton resmi di",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            val perJenis = tersedia.penyedia.groupBy { it.jenis }
            URUT_JENIS.forEach { jenis ->
                val daftar = perJenis[jenis] ?: return@forEach
                Text(
                    LABEL_JENIS[jenis] ?: jenis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 16.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    daftar.forEach { penyedia ->
                        AssistChip(
                            onClick = {
                                // Dibuka DI LUAR aplikasi: lapisan ini menunjuk
                                // ke layanan sah, bukan memutar apa pun sendiri.
                                val tujuan = tersedia.tautan
                                if (tujuan.isNullOrBlank()) {
                                    app.toast("Tautan resmi belum tersedia untuk judul ini.")
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tujuan)))
                                    }.onFailure { app.toast("Tidak ada aplikasi untuk membuka tautan.") }
                                }
                            },
                            label = { Text(penyedia.nama, maxLines = 1) },
                            leadingIcon = penyedia.logoUrl?.let { logo ->
                                {
                                    AsyncImage(
                                        model = logo,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            tersedia.atribusi?.let { atribusi ->
                Text(
                    atribusi,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (item.genres.isNotEmpty()) {
            Text("Genre", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text(item.genres.joinToString(" · "), modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("Sinopsis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        Text(
            item.overview.ifBlank { "Sinopsis belum tersedia." },
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        item.trailerUrl?.let { trailer ->
            Button(
                onClick = {
                    if (idTrailer != null) {
                        // Musik dijeda dulu, sama seperti saat film diputar:
                        // dua sumber suara sekaligus tidak pernah diinginkan.
                        app.container.player.pause()
                        trailerDibuka = true
                    } else {
                        // Bentuk tautan yang tidak dikenali tetap bisa dibuka,
                        // hanya saja lewat aplikasi luar seperti sebelumnya.
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailer))) }
                            .onFailure { app.toast("Tidak ada aplikasi untuk membuka trailer.") }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Icon(Icons.Rounded.Movie, null)
                Text("  Tonton trailer")
            }
        }

        if (item.subtitles.isNotEmpty()) {
            Text("Subtitle tersedia", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            item.subtitles.forEach { subtitle ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        "${subtitle.label.ifBlank { subtitle.lang }} · ${subtitle.lang}",
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        if (item.isSeries) {
            Text("Episode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            item.episodes.forEach { episode ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            app.container.player.pause()
                            app.navigate(Routes.moviePlayer(item.id, episode.id))
                        },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = BytifyColors.GoldDeep)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(episode.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                listOfNotNull("S${episode.season} E${episode.episode}", episode.durationMin?.let { "$it menit" }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Katalog tidak lagi mengirim license: nilainya sama untuk seluruh
        // katalog, jadi mengirimnya 25.135 kali hanya membuang ruang pada
        // berkas yang dibatasi 25 MiB. Atribusinya tetap ditampilkan di sini.
        (item.license ?: LISENSI_BAWAAN).let {
            Text(
                "Lisensi: $it",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Trailer digambar di jendela yang SAMA dengan halaman ini, bukan sebagai
    // Dialog. Catatan jujur: Dialog sempat dicurigai sebagai penyebab layar
    // hitam dan ternyata BUKAN — penyebabnya User-Agent (lihat di bawah).
    // Bentuk lapisan tetap dipertahankan karena tidak membuka jendela kedua
    // dan tombol Kembali bisa ditangani langsung dengan BackHandler.
        if (trailerDibuka && idTrailer != null) {
            LapisanTrailer(idTrailer, item.title) { trailerDibuka = false }
        }
    }
}