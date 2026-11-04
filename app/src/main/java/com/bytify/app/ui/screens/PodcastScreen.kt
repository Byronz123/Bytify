package com.bytify.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytify.app.data.db.EpisodeProgressEntity
import com.bytify.app.data.remote.Episode
import com.bytify.app.data.remote.Podcast
import com.bytify.app.data.remote.PodcastFeed
import com.bytify.app.ui.AppState
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.SongMenuRequest
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.Artwork
import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.ui.theme.LocalCel
import com.bytify.app.ui.theme.cel
import com.bytify.app.util.formatDuration
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Putar episode; lanjutkan dari posisi terakhir bila belum selesai.
 * Antrean berisi episode-episode berikutnya di daftar yang sama.
 */
fun AppState.playEpisode(episode: Episode, list: List<Episode>) {
    container.appScope.launch {
        val saved = container.podcasts.progressOf(episode.id)
        val start = saved?.takeIf { !it.finished && it.positionMs > 10_000 }?.positionMs ?: 0L
        val index = list.indexOf(episode).coerceAtLeast(0)
        player.playSongs(list.map { it.toSong() }, index, radio = false, startPositionMs = start)
        if (start > 0) toast("Melanjutkan dari ${formatDuration(start / 1000)}")
    }
}

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale("id", "ID"))

@Composable
fun EpisodeRow(episode: Episode, list: List<Episode>, showPodcast: Boolean = true) {
    val app = appState()
    val current by app.player.state.collectAsState()
    val progressMap by app.container.podcasts.progress.collectAsState(initial = emptyMap())
    val progress: EpisodeProgressEntity? = progressMap[episode.id]
    val isCurrent = current.song?.id == episode.id
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { app.playEpisode(episode, list) }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp)) {
            Artwork(episode.imageUrl, Modifier.size(56.dp).cel(shape = RoundedCornerShape(12.dp), stroke = 1.5.dp, shadow = 0.dp), RoundedCornerShape(12.dp))
            if (isCurrent) {
                Box(
                    Modifier.size(56.dp).cel(BytifyColors.Navy.copy(alpha = 0.6f), RoundedCornerShape(12.dp), stroke = 1.5.dp, shadow = 0.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.GraphicEq, null, tint = BytifyColors.Gold) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (showPodcast) {
                Text(episode.podcastTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                episode.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = listOfNotNull(
                    episode.publishedAt.takeIf { it > 0 }?.let { dateFormat.format(it) },
                    when {
                        progress?.finished == true -> "Selesai"
                        progress != null && progress.positionMs > 10_000 && episode.durationSec != null ->
                            "Sisa ${formatDuration((episode.durationSec - progress.positionMs / 1000).coerceAtLeast(0))}"
                        else -> episode.durationSec?.let(::formatDuration)
                    },
                ).joinToString(" • ")
                if (progress?.finished == true) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress != null && !progress.finished && progress.durationMs > 0 && progress.positionMs > 10_000) {
                LinearProgressIndicator(
                    progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.6f).height(3.dp).clip(CircleShape),
                    color = BytifyColors.Gold,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        IconButton(onClick = { app.showMenu(SongMenuRequest(episode.toSong())) }) {
            Icon(Icons.Rounded.MoreVert, "Opsi episode", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PodcastScreen(id: Long) {
    val app = appState()
    val repo = app.container.podcasts
    val scope = rememberCoroutineScope()
    val subscriptions by repo.subscriptions.collectAsState(initial = emptyList())
    val subscribed = subscriptions.any { it.id == id }
    var attempt by remember { mutableIntStateOf(0) }
    var feed by remember { mutableStateOf<PodcastFeed?>(null) }
    var failed by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(id, attempt) {
        failed = false
        val result = runCatching {
            val podcast: Podcast = subscriptions.firstOrNull { it.id == id } ?: repo.podcast(id) ?: error("Podcast tidak ditemukan")
            repo.feed(podcast, refresh = attempt > 0)
        }
        feed = result.getOrNull()
        failed = result.isFailure
        if (subscribed) repo.markOpened(id)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Podcast", style = MaterialTheme.typography.titleLarge)
            }
        }
        val data = feed
        when {
            failed -> item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Podcast gagal dimuat. Periksa koneksi internet.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = { attempt++ }) { Text("Coba lagi") }
                }
            }
            data == null -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            else -> {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(data.podcast.artworkUrl, Modifier.size(200.dp).cel(shape = RoundedCornerShape(22.dp), stroke = 2.5.dp, shadow = 6.dp), RoundedCornerShape(22.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(data.podcast.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(data.podcast.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Text("${data.episodes.size} episode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { data.episodes.firstOrNull()?.let { app.playEpisode(it, data.episodes) } },
                                enabled = data.episodes.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BytifyColors.Gold, contentColor = BytifyColors.Navy),
                                border = BorderStroke(2.dp, LocalCel.current.line),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Episode terbaru")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        if (subscribed) repo.unsubscribe(id) else repo.subscribe(data.podcast)
                                        app.toast(if (subscribed) "Berhenti berlangganan" else "Berlangganan ${data.podcast.title}")
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(2.dp, LocalCel.current.line),
                            ) {
                                Icon(if (subscribed) Icons.Rounded.Check else Icons.Rounded.Add, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (subscribed) "Berlangganan" else "Ikuti")
                            }
                        }
                        if (data.description.isNotBlank()) {
                            Text(
                                data.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 16.dp).animateContentSize().clickable { expanded = !expanded },
                            )
                        }
                    }
                }
                item {
                    Text("Episode", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
                }
                if (data.episodes.isEmpty()) {
                    item {
                        Text("Belum ada episode yang bisa diputar.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    }
                }
                val visible = if (showAll) data.episodes else data.episodes.take(PAGE)
                items(visible, key = { it.id }) { EpisodeRow(it, data.episodes, showPodcast = false) }
                if (!showAll && data.episodes.size > PAGE) {
                    item {
                        TextButton(onClick = { showAll = true }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Tampilkan semua ${data.episodes.size} episode")
                        }
                    }
                }
            }
        }
    }
}

private const val PAGE = 30
