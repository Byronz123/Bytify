package com.bytify.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
private fun DialogTrailer(idVideo: String, judul: String, onTutup: () -> Unit) {
    Dialog(
        onDismissRequest = onTutup,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(Color.Black)) {
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
                        }
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        // YouTube menolak pemutar embed yang dimuat tanpa origin
                        // yang sah. Terukur di perangkat: memuat /embed/<id>
                        // sebagai alamat langsung memberi "Error 153 — terjadi
                        // error pada konfigurasi pemutar video". Dokumen kecil
                        // ini dimuat DENGAN base URL youtube.com, sehingga
                        // iframe di dalamnya berjalan pada origin yang diterima.
                        val halaman = """
                            <!DOCTYPE html><html><head>
                            <meta name="viewport" content="width=device-width,initial-scale=1">
                            <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
                            iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>
                            </head><body><iframe
                             src="https://www.youtube.com/embed/$idVideo?autoplay=1&playsinline=1&rel=0&modestbranding=1"
                             allow="autoplay; encrypted-media; picture-in-picture"
                             allowfullscreen></iframe></body></html>
                        """.trimIndent()
                        loadDataWithBaseURL("https://www.youtube.com", halaman, "text/html", "utf-8", null)
                    }
                },
                onRelease = { web ->
                    // about:blank dulu, baru destroy: tanpa itu suara trailer
                    // bisa terus berbunyi setelah dialognya ditutup.
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

        if (trailerDibuka && idTrailer != null) {
            DialogTrailer(idTrailer, item.title) { trailerDibuka = false }
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
}