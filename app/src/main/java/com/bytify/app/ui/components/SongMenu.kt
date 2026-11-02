package com.bytify.app.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytify.app.data.DownloadState
import com.bytify.app.data.model.Song
import com.bytify.app.ui.appState
import com.bytify.app.util.ShareCard
import com.bytify.app.util.shareUrl
import kotlinx.coroutines.launch

/** Menu aksi lagu (bottom sheet) yang dipakai di semua layar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuHost() {
    val app = appState()
    val request = app.songMenu ?: return
    val song = request.song
    val context = LocalContext.current
    val likedIds by app.container.library.likedIds.collectAsState(initial = emptySet())
    val liked = song.id in likedIds
    val downloadStates by app.container.downloads.states.collectAsState()
    val download = downloadStates[song.id]
    val dismiss = { app.songMenu = null }

    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.thumbnailUrl, Modifier.size(56.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            MenuItem(Icons.Rounded.PlaylistPlay, "Putar berikutnya") {
                app.player.playNext(song); app.toast("Diputar setelah lagu ini"); dismiss()
            }
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Tambah ke antrean") {
                app.player.addToQueue(song); app.toast("Ditambahkan ke antrean"); dismiss()
            }
            MenuItem(Icons.Rounded.Radio, "Mulai radio") {
                app.player.playSongs(listOf(song)); dismiss()
            }
            MenuItem(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Hapus dari favorit" else "Sukai") {
                app.container.appScope.launch { app.container.library.toggleLike(song, liked) }; dismiss()
            }
            when (download) {
                DownloadState.Completed -> MenuItem(Icons.Rounded.DownloadDone, "Hapus unduhan") {
                    app.container.downloads.remove(song.id); app.toast("Unduhan dihapus"); dismiss()
                }
                DownloadState.Queued, DownloadState.Downloading -> MenuItem(Icons.Rounded.Downloading, "Batalkan unduhan") {
                    app.container.downloads.remove(song.id); dismiss()
                }
                else -> MenuItem(Icons.Rounded.Download, "Unduh") {
                    app.container.downloads.download(song); app.toast("Mengunduh \"${song.title}\""); dismiss()
                }
            }
            if (app.container.together.isGuest) {
                MenuItem(Icons.Rounded.Groups, "Sarankan ke host") {
                    app.container.together.suggest(song); app.toast("Saran terkirim ke host"); dismiss()
                }
            }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Tambah ke playlist") {
                app.addToPlaylistSong = song; dismiss()
            }
            request.playlistId?.let { playlistId ->
                MenuItem(Icons.Rounded.Delete, "Hapus dari playlist ini") {
                    app.container.appScope.launch { app.container.library.removeFromPlaylist(playlistId, song.id) }; dismiss()
                }
            }
            request.historyEventId?.let { eventId ->
                MenuItem(Icons.Rounded.History, "Hapus dari riwayat") {
                    app.container.appScope.launch { app.container.library.deleteHistoryEntry(eventId) }; dismiss()
                }
            }
            MenuItem(Icons.Rounded.Image, "Bagikan sebagai gambar") {
                app.container.appScope.launch { ShareCard.share(context, song) }; dismiss()
            }
            shareUrl(song.id)?.let { url ->
                MenuItem(Icons.Rounded.Share, "Bagikan link") {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "${song.title} – ${song.artist}\n$url\n\nDibagikan dari Bytify")
                    }
                    context.startActivity(Intent.createChooser(send, "Bagikan lagu"))
                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Pilih playlist tujuan, atau buat playlist baru. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistHost() {
    val app = appState()
    val song = app.addToPlaylistSong ?: return
    val playlists by app.container.library.playlists.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    val dismiss = { app.addToPlaylistSong = null }

    if (creating) {
        PlaylistNameDialog(
            title = "Playlist baru",
            onDismiss = { creating = false },
            onConfirm = { name ->
                app.container.appScope.launch {
                    val id = app.container.library.createPlaylist(name)
                    app.container.library.addToPlaylist(id, song)
                    app.toast("Ditambahkan ke \"$name\"")
                }
                creating = false
                dismiss()
            },
        )
        return
    }

    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            "Tambah ke playlist",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            item { MenuItem(Icons.Rounded.Add, "Buat playlist baru") { creating = true } }
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            app.container.appScope.launch {
                                val added = app.container.library.addToPlaylist(playlist.id, song)
                                app.toast(if (added) "Ditambahkan ke \"${playlist.name}\"" else "Sudah ada di \"${playlist.name}\"")
                            }
                            dismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CollageArtwork(playlist.thumbnailList, size = 48.dp, shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text("${playlist.songCount} lagu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistNameDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Nama playlist") },
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
