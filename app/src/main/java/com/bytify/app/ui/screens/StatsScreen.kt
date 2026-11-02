package com.bytify.app.ui.screens

import com.bytify.app.ui.theme.gloss
import com.bytify.app.ui.theme.cel
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytify.app.data.db.ArtistStat
import com.bytify.app.data.db.SongStat
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.Artwork
import com.bytify.app.ui.components.CollageArtwork
import com.bytify.app.ui.components.SectionHeader
import com.bytify.app.ui.components.SongCard
import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.util.currentYear
import com.bytify.app.util.daysAgo
import com.bytify.app.util.formatMs
import com.bytify.app.util.startOfYear

private val periods = listOf("1 minggu" to 7, "1 bulan" to 30, "3 bulan" to 90, "6 bulan" to 180, "1 tahun" to 365, "Semua" to -1)

@Composable
fun StatsScreen() {
    val app = appState()
    val library = app.container.library
    var period by rememberSaveable { mutableIntStateOf(0) }
    val days = periods[period].second
    val since = if (days < 0) 0L else daysAgo(days)
    val songs by remember(since) { library.topSongs(since, 50) }.collectAsState(initial = emptyList())
    val artists by remember(since) { library.topArtists(since, 20) }.collectAsState(initial = emptyList())
    val totalMs by remember(since) { library.totalPlayTime(since) }.collectAsState(initial = 0L)
    val weekly by remember { library.topSongs(daysAgo(7), 4) }.collectAsState(initial = emptyList())
    val monthly by remember { library.topSongs(daysAgo(30), 4) }.collectAsState(initial = emptyList())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Statistik", style = MaterialTheme.typography.titleLarge)
            }
            Row(
                Modifier
                    .padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .cel(BytifyColors.GoldLight, RoundedCornerShape(20.dp))
                    .gloss(0.25f)
                    .clickable { app.navigate(Routes.RECAP) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).cel(BytifyColors.Navy, CircleShape, shadow = 0.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.AutoGraph, null, tint = BytifyColors.Gold) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recap ${currentYear()} kamu", style = MaterialTheme.typography.titleMedium, color = BytifyColors.Navy)
                    Text("Lagu, artis & menit terbanyakmu", style = MaterialTheme.typography.bodyMedium, color = BytifyColors.Navy.copy(alpha = 0.75f))
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = BytifyColors.Navy)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(periods) { index, (label, _) ->
                    FilterChip(
                        selected = period == index,
                        onClick = { period = index },
                        label = { Text(label) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            Text(
                "${totalMs / 60_000} menit didengarkan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item {
            SectionHeader("Playlist otomatis")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AutoPlaylistCard("Teratas minggu ini", weekly) { app.navigate(Routes.collection("top:7")) }
                AutoPlaylistCard("Teratas bulan ini", monthly) { app.navigate(Routes.collection("top:30")) }
            }
        }

        item {
            SectionHeader(
                "${songs.size} lagu",
                action = if (songs.isNotEmpty()) {
                    { OutlinedButton(onClick = { app.play(songs.map { it.song.toSong() }) }, shape = CircleShape) { Text("Putar semua") } }
                } else null,
            )
            if (songs.isEmpty()) {
                Text(
                    "Belum ada lagu yang diputar pada periode ini.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(songs, key = { _, s -> s.song.id }) { index, stat ->
                        SongCard(
                            song = stat.song.toSong(),
                            title = "${index + 1}. ${stat.song.title}",
                            caption = "${stat.plays} kali • ${formatMs(stat.totalMs)}",
                            onClick = { app.play(songs.map { it.song.toSong() }, index) },
                        )
                    }
                }
            }
        }

        if (artists.isNotEmpty()) {
            item {
                SectionHeader("${artists.size} artis")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(artists, key = { _, a -> a.artist }) { index, artist ->
                        Column(
                            Modifier.width(120.dp).clickable { app.navigate(Routes.collection("artist:${artist.artist}")) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Artwork(artist.thumbnailUrl, Modifier.size(120.dp), CircleShape)
                            Spacer(Modifier.height(8.dp))
                            Text("${index + 1}. ${artist.artist}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${artist.plays} kali • ${formatMs(artist.totalMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoPlaylistCard(title: String, stats: List<SongStat>, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clickable(onClick = onClick)) {
        CollageArtwork(stats.map { it.song.thumbnailUrl }, size = 160.dp, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ===================================================================
// Recap: story layar penuh, maju otomatis tiap beberapa detik
// ===================================================================

private const val SLIDE_MS = 6_000

@Composable
fun RecapScreen() {
    val app = appState()
    val library = app.container.library
    val since = remember { startOfYear() }
    val songs by remember { library.topSongs(since, 5) }.collectAsState(initial = null)
    val artists by remember { library.topArtists(since, 5) }.collectAsState(initial = null)
    val totalMs by remember { library.totalPlayTime(since) }.collectAsState(initial = 0L)
    val year = currentYear()
    val close = { app.navController.popBackStack(); Unit }

    if (songs == null || artists == null) return
    val topSongs = songs.orEmpty()
    val topArtists = artists.orEmpty()

    if (topSongs.isEmpty()) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Recap $year", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("Belum ada riwayat dengar tahun ini. Putar beberapa lagu lalu kembali lagi.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = close) { Text("Tutup") }
        }
        return
    }

    val minutes = totalMs / 60_000
    val slides: List<@Composable () -> Unit> = listOf(
        { RecapIntro(year) },
        { RecapBig("Kamu mendengarkan selama", "$minutes", "menit") },
        { RecapHero("Lagu teratasmu", topSongs.first().song.title, topSongs.first().song.artist, topSongs.first().song.thumbnailUrl, "${topSongs.first().plays} kali diputar", null) },
        { RecapList("5 lagu teratasmu", topSongs.map { Triple(it.song.title, it.song.artist, it.song.thumbnailUrl) }) },
        { topArtists.firstOrNull()?.let { RecapHero("Artis teratasmu", it.artist, "${it.totalMs / 60_000} menit bersama", it.thumbnailUrl, "${it.plays} kali diputar", CircleShape) } },
        { RecapList("5 artis teratasmu", topArtists.map { Triple(it.artist, "${it.plays} kali diputar", it.thumbnailUrl) }, circle = true) },
        { RecapOutro(year, minutes, topSongs, topArtists) },
    )
    val gradients = listOf(
        listOf(BytifyColors.NavySoft, BytifyColors.NavyDeep),
        listOf(BytifyColors.GoldDeep, BytifyColors.Navy),
        listOf(Color(0xFF3F5A7A), BytifyColors.NavyDeep),
        listOf(Color(0xFF6B4E71), BytifyColors.NavyDeep),
        listOf(Color(0xFFA8643A), BytifyColors.NavyDeep),
        listOf(Color(0xFF4F6B5E), BytifyColors.NavyDeep),
        listOf(BytifyColors.NavySoft, BytifyColors.NavyDeep),
    )

    var index by rememberSaveable { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(index) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SLIDE_MS, easing = LinearEasing))
        if (index < slides.lastIndex) index++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradients[index]))
            .pointerInput(slides.size) {
                detectTapGestures { offset ->
                    index = if (offset.x < size.width / 3) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(slides.lastIndex)
                }
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slides.indices.forEach { i ->
                    LinearProgressIndicator(
                        progress = { if (i < index) 1f else if (i == index) progress.value else 0f },
                        modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("BYTIFY RECAP", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(start = 8.dp).weight(1f))
                IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Tutup", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                slides[index]()
            }
        }
    }
}

@Composable
private fun RecapIntro(year: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Recap", style = MaterialTheme.typography.displaySmall, color = Color.White)
        Text("$year", fontSize = 88.sp, style = MaterialTheme.typography.displaySmall, color = BytifyColors.Gold)
        Spacer(Modifier.height(12.dp))
        Text("Setahun bersama musikmu di Bytify.", color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun RecapBig(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(value, fontSize = 96.sp, style = MaterialTheme.typography.displaySmall, color = Color.White)
        Text(unit, style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun RecapHero(label: String, title: String, subtitle: String, image: String?, caption: String, shape: androidx.compose.ui.graphics.Shape?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Artwork(image, Modifier.size(240.dp), shape ?: RoundedCornerShape(20.dp))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(caption, style = MaterialTheme.typography.labelLarge, color = BytifyColors.Gold)
    }
}

@Composable
private fun RecapList(title: String, rows: List<Triple<String, String, String?>>, circle: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(20.dp))
        rows.forEachIndexed { i, (name, sub, image) ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.headlineSmall, color = BytifyColors.Gold, modifier = Modifier.width(36.dp))
                Artwork(image, Modifier.size(56.dp), if (circle) CircleShape else RoundedCornerShape(10.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RecapOutro(year: Int, minutes: Long, songs: List<SongStat>, artists: List<ArtistStat>) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Itu Recap-mu!", style = MaterialTheme.typography.displaySmall, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Terima kasih sudah mendengarkan bersama Bytify.", color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                val text = buildString {
                    appendLine("Recap Bytify $year 🎧")
                    appendLine("$minutes menit mendengarkan")
                    appendLine()
                    appendLine("Lagu teratas:")
                    songs.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.song.title} – ${s.song.artist}") }
                    if (artists.isNotEmpty()) {
                        appendLine()
                        appendLine("Artis teratas:")
                        artists.forEachIndexed { i, a -> appendLine("${i + 1}. ${a.artist}") }
                    }
                }
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                context.startActivity(Intent.createChooser(send, "Bagikan Recap"))
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = CircleShape,
        ) {
            Icon(Icons.Rounded.Share, null)
            Spacer(Modifier.width(8.dp))
            Text("Bagikan Recap")
        }
    }
}
