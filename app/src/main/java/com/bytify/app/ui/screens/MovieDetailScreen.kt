package com.bytify.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.launch

/** Atribusi sumber katalog; dulu dikirim per film padahal nilainya seragam. */
private const val LISENSI_BAWAAN = "LK21 Stream Adapter"

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
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailer))) }
                        .onFailure { app.toast("Tidak ada aplikasi untuk membuka trailer.") }
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
}