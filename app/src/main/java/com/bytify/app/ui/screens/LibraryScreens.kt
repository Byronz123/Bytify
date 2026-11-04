package com.bytify.app.ui.screens

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import com.bytify.app.ui.theme.LocalCel
import com.bytify.app.ui.theme.gloss
import com.bytify.app.ui.theme.cel
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytify.app.data.DownloadState
import com.bytify.app.data.model.Song
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.SongMenuRequest
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.Artwork
import com.bytify.app.ui.components.CollageArtwork
import com.bytify.app.ui.components.PlaylistNameDialog
import com.bytify.app.ui.components.SongRow
import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.util.daysAgo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// ===================================================================
// Koleksi (tab)
// ===================================================================

@Composable
fun LibraryScreen() {
    val app = appState()
    val library = app.container.library
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val playlists by library.playlists.collectAsState(initial = emptyList())
    val liked by library.likedSongs.collectAsState(initial = emptyList())
    val artists by library.topArtists(0L, 100).collectAsState(initial = emptyList())
    val downloaded by app.container.downloads.downloadedSongs.collectAsState(initial = emptyList())
    val current by app.player.state.collectAsState()

    if (creating) {
        PlaylistNameDialog(
            title = "Playlist baru",
            onDismiss = { creating = false },
            onConfirm = { name ->
                scope.launch { app.navigate(Routes.playlist(library.createPlaylist(name))) }
                creating = false
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp),
    ) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Koleksi", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { app.navigate(Routes.IMPORT) }) { Icon(Icons.Rounded.Link, "Impor playlist dari link") }
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "Buat playlist") }
            }
            LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(listOf("Playlist", "Disukai", "Diunduh", "Artis")) { index, label ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(label) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        when (tab) {
            0 -> {
                item {
                    // Pintasan cepat 2x2: koleksi otomatis yang paling sering dibuka.
                    Column(
                        Modifier.padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            QuickTile(Icons.Rounded.Favorite, "Disukai", "${liked.size} lagu", BytifyColors.Gold, Modifier.weight(1f)) {
                                app.navigate(Routes.collection("liked"))
                            }
                            QuickTile(Icons.Rounded.DownloadDone, "Diunduh", "${downloaded.size} lagu", BytifyColors.Navy, Modifier.weight(1f)) {
                                app.navigate(Routes.collection("downloaded"))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            QuickTile(Icons.Rounded.History, "Riwayat", "Baru diputar", BytifyColors.NavySoft, Modifier.weight(1f)) {
                                app.navigate(Routes.HISTORY)
                            }
                            QuickTile(Icons.AutoMirrored.Rounded.TrendingUp, "Top 50 kamu", "Bulan ini", BytifyColors.GoldDeep, Modifier.weight(1f)) {
                                app.navigate(Routes.collection("top:30"))
                            }
                        }
                    }
                }
                items(playlists, key = { it.id }) { playlist ->
                    LibraryEntry(
                        title = playlist.name,
                        subtitle = "Playlist • ${playlist.songCount} lagu",
                        onClick = { app.navigate(Routes.playlist(playlist.id)) },
                        art = { CollageArtwork(playlist.thumbnailList, size = 64.dp) },
                    )
                }
                if (playlists.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Belum ada playlist buatanmu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { creating = true }) { Text("Buat playlist") }
                        }
                    }
                }
            }
            1 -> {
                if (liked.isEmpty()) {
                    item { EmptyText("Ketuk ikon hati pada lagu untuk menyimpannya di sini.") }
                }
                itemsIndexed(liked, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = current.song?.id == song.id,
                        onClick = { app.play(liked, index) },
                        onMore = { app.showMenu(SongMenuRequest(song)) },
                    )
                }
            }
            2 -> {
                if (downloaded.isEmpty()) {
                    item { EmptyText("Lagu yang kamu unduh bisa diputar tanpa internet. Pilih \"Unduh\" di menu ⋮ lagu.") }
                }
                itemsIndexed(downloaded, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = current.song?.id == song.id,
                        onClick = { app.play(downloaded, index) },
                        onMore = { app.showMenu(SongMenuRequest(song)) },
                    )
                }
            }
            else -> {
                if (artists.isEmpty()) {
                    item { EmptyText("Artis yang sering kamu dengarkan akan muncul di sini.") }
                }
                items(artists, key = { it.artist }) { artist ->
                    LibraryEntry(
                        title = artist.artist,
                        subtitle = "${artist.plays} kali diputar",
                        onClick = { app.navigate(Routes.collection("artist:${artist.artist}")) },
                        art = { Artwork(artist.thumbnailUrl, Modifier.size(64.dp), CircleShape) },
                    )
                }
            }
        }
    }
}

/** Ubin pintasan persegi bergaya cel: ikon besar di tengah, label di bawah. */
@Composable
private fun QuickTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val onColor = if (color.luminance() > 0.4f) BytifyColors.Navy else BytifyColors.Cream
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(end = 4.dp, bottom = 4.dp)
                .cel(color, RoundedCornerShape(20.dp))
                .gloss(0.2f),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = onColor, modifier = Modifier.size(56.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryEntry(title: String, subtitle: String, onClick: () -> Unit, art: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

// ===================================================================
// Halaman daftar lagu (koleksi otomatis & playlist)
// ===================================================================

/**
 * Jenis koleksi: `liked`, `top:<hari>`, `chart:<negara>`, `mood:<label>|<kueri>`,
 * atau `artist:<nama>`.
 */
@Composable
fun CollectionScreen(kind: String) {
    val app = appState()
    val c = app.container
    val (title, subtitle, numbered) = remember(kind) {
        when {
            kind == "liked" -> Triple("Lagu yang disukai", "Koleksimu", false)
            kind == "downloaded" -> Triple("Diunduh", "Tersedia offline", false)
            kind.startsWith("top:") -> {
                val days = kind.removePrefix("top:")
                Triple(if (days == "7") "Paling sering minggu ini" else "Paling sering bulan ini", "Playlist otomatis", true)
            }
            kind == "chart:id" -> Triple("Top 50 Indonesia", "Tangga lagu • Apple Music", true)
            kind.startsWith("chart:") -> Triple("Top 50 Dunia", "Tangga lagu • Apple Music", true)
            kind.startsWith("mood:") -> Triple(kind.removePrefix("mood:").substringBefore('|'), "Mood", false)
            else -> Triple(kind.removePrefix("artist:"), "Artis", false)
        }
    }

    // Koleksi lokal reaktif; koleksi online dimuat sekali.
    val localFlow = remember(kind) {
        when {
            kind == "liked" -> c.library.likedSongs
            kind == "downloaded" -> c.downloads.downloadedSongs
            kind.startsWith("top:") ->
                c.library.topSongs(daysAgo(kind.removePrefix("top:").toInt()), 50).map { l -> l.map { it.song.toSong() } }
            else -> null
        }
    }
    var remote by remember(kind) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(kind) { mutableStateOf(false) }
    var attempt by remember(kind) { mutableStateOf(0) }
    LaunchedEffect(kind, attempt) {
        if (localFlow != null) return@LaunchedEffect
        failed = false
        remote = runCatching {
            when {
                kind.startsWith("chart:") -> c.charts.topSongs(kind.removePrefix("chart:"))
                kind.startsWith("mood:") -> c.youtube.searchSongs(kind.substringAfter('|'))
                else -> c.youtube.searchSongs(kind.removePrefix("artist:"))
            }
        }.onFailure { failed = true }.getOrNull()
    }
    val local by (localFlow ?: flowOf(null)).collectAsState(initial = null)
    val songs = local ?: remote

    SongListPage(
        title = title,
        subtitle = subtitle,
        songs = songs,
        numbered = numbered,
        failed = failed,
        onRetry = { attempt++ },
        emptyText = if (kind == "liked") "Belum ada lagu yang kamu sukai." else "Belum ada data untuk periode ini.",
    )
}

@Composable
fun PlaylistScreen(id: Long) {
    val app = appState()
    val library = app.container.library
    val scope = rememberCoroutineScope()
    val playlist by remember(id) { library.playlist(id) }.collectAsState(initial = null)
    val songs by remember(id) { library.playlistSongs(id) }.collectAsState(initial = null)
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val name = playlist?.name.orEmpty()

    if (renaming) {
        PlaylistNameDialog(
            title = "Ganti nama",
            initial = name,
            onDismiss = { renaming = false },
            onConfirm = { scope.launch { library.renamePlaylist(id, it) }; renaming = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Hapus playlist?") },
            text = { Text("\"$name\" akan dihapus. Lagu di dalamnya tetap ada di koleksimu.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    app.container.appScope.launch { library.deletePlaylist(id) }
                    app.navController.popBackStack()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Batal") } },
        )
    }

    SongListPage(
        title = name,
        subtitle = "Playlist",
        songs = songs,
        playlistId = id,
        emptyText = "Playlist ini masih kosong. Tambahkan lagu lewat menu ⋮ pada lagu mana pun.",
        actions = {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Ganti nama") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Hapus playlist") }
        },
    )
}

@Composable
private fun SongListPage(
    title: String,
    subtitle: String,
    songs: List<Song>?,
    emptyText: String,
    numbered: Boolean = false,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
    playlistId: Long? = null,
    actions: @Composable () -> Unit = {},
) {
    val app = appState()
    val current by app.player.state.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali")
                }
                Spacer(Modifier.weight(1f))
                actions()
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CollageArtwork(
                    songs.orEmpty().take(12).map { it.thumbnailUrl },
                    size = 220.dp,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.cel(shape = RoundedCornerShape(20.dp), stroke = 2.5.dp, shadow = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    listOfNotNull(subtitle, songs?.let { "${it.size} lagu" }).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { app.play(songs.orEmpty()) },
                        enabled = !songs.isNullOrEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BytifyColors.Gold, contentColor = BytifyColors.Navy),
                        border = BorderStroke(2.dp, LocalCel.current.line),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Putar")
                    }
                    val downloadStates by app.container.downloads.states.collectAsState()
                    val allDownloaded = !songs.isNullOrEmpty() &&
                        songs.all { downloadStates[it.id] == DownloadState.Completed }
                    OutlinedButton(
                        onClick = {
                            app.container.downloads.downloadAll(songs.orEmpty())
                            app.toast("Mengunduh ${songs.orEmpty().size} lagu")
                        },
                        enabled = !songs.isNullOrEmpty() && !allDownloaded,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(2.dp, LocalCel.current.line),
                    ) {
                        Icon(
                            if (allDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                            if (allDownloaded) "Semua sudah diunduh" else "Unduh semua",
                        )
                    }
                    OutlinedButton(
                        onClick = { app.player.playShuffled(songs.orEmpty()) },
                        enabled = !songs.isNullOrEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(2.dp, LocalCel.current.line),
                    ) {
                        Icon(Icons.Rounded.Shuffle, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Acak")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        when {
            failed -> item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Gagal memuat lagu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry) { Text("Coba lagi") }
                }
            }
            songs == null -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            songs.isEmpty() -> item { EmptyText(emptyText) }
            else -> itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = current.song?.id == song.id,
                    number = if (numbered) index + 1 else null,
                    onClick = { app.play(songs, index) },
                    onMore = { app.showMenu(SongMenuRequest(song, playlistId = playlistId)) },
                )
            }
        }
    }
}
