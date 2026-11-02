package com.bytify.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.SongMenuRequest
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.SongRow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Label kelompok tanggal: Hari ini, Kemarin, Minggu ini, atau tanggal. */
private fun dayLabel(timestamp: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 86_400_000L
    return when {
        timestamp >= today -> "Hari ini"
        timestamp >= today - day -> "Kemarin"
        timestamp >= today - 6 * day -> "Minggu ini"
        else -> SimpleDateFormat("MMMM yyyy", Locale("id", "ID")).format(timestamp)
            .replaceFirstChar { it.uppercase() }
    }
}

@Composable
fun HistoryScreen() {
    val app = appState()
    val scope = rememberCoroutineScope()
    val rows by app.container.library.history.collectAsState(initial = null)
    val current by app.player.state.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = rows.orEmpty().filter {
        query.isBlank() || it.song.title.contains(query, true) || it.song.artist.contains(query, true)
    }
    val groups = filtered.groupBy { dayLabel(it.timestamp) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus semua riwayat?") },
            text = { Text("Statistik dan Recap juga akan dihitung ulang dari nol.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.container.library.clearHistory() }
                    confirmClear = false
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Batal") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali")
                }
                Text("Riwayat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Cari di riwayat")
                }
                IconButton(onClick = { confirmClear = true }, enabled = !rows.isNullOrEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, "Hapus semua riwayat")
                }
            }
            if (searching) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Cari judul atau artis") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
        if (rows != null && filtered.isEmpty()) {
            item {
                Text(
                    if (query.isBlank()) "Belum ada lagu yang diputar." else "Tidak ada yang cocok dengan \"$query\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        groups.forEach { (label, entries) ->
            item(key = "header-$label") {
                Text(
                    label,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
                )
            }
            items(entries, key = { it.eventId }) { row ->
                val song = row.song.toSong()
                SongRow(
                    song = song,
                    isCurrent = current.song?.id == song.id,
                    onClick = { app.play(listOf(song)) },
                    onMore = { app.showMenu(SongMenuRequest(song, historyEventId = row.eventId)) },
                )
            }
        }
    }
}
