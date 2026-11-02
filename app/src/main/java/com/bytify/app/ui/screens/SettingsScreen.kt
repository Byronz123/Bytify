package com.bytify.app.ui.screens

import androidx.annotation.OptIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.bytify.app.BuildConfig
import com.bytify.app.data.DownloadState
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.bytify.app.playback.DynamicIsland
import com.bytify.app.data.update.UpdateState
import com.bytify.app.data.prefs.Settings as AppSettings
import com.bytify.app.ui.Routes
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.appState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
fun SettingsScreen() {
    val app = appState()
    val c = app.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val context = LocalContext.current
    // Dicek ulang setiap layar dibuka kembali (setelah pengguna memberi izin).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val islandPermission = remember(resumed) { DynamicIsland.canDraw(context) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var refreshCache by remember { mutableStateOf(0) }

    val downloadStates by c.downloads.states.collectAsState()
    val downloadedCount = downloadStates.count { it.value == DownloadState.Completed }
    var downloadBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(refreshCache) {
        cacheBytes = withContext(Dispatchers.IO) { c.songCache.cacheSpace }
    }
    LaunchedEffect(downloadedCount) {
        downloadBytes = withContext(Dispatchers.IO) { c.downloads.cache.cacheSpace }
    }

    confirm?.let { (title, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text("Tindakan ini tidak bisa dibatalkan.") },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Batal") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Pengaturan", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Group("Tampilan") {
                SwitchRow(
                    Icons.Outlined.PictureInPicture,
                    "Dynamic Island",
                    if (settings.dynamicIsland && !islandPermission) "Izin \"tampil di atas aplikasi lain\" belum diberikan — ketuk untuk mengizinkan"
                    else "Pill melayang berisi lagu yang diputar saat kamu membuka aplikasi lain",
                    settings.dynamicIsland,
                ) { on ->
                    scope.launch { c.settings.setDynamicIsland(on) }
                    if (on && !DynamicIsland.canDraw(context)) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
                Divider()
                SwitchRow(Icons.Outlined.Contrast, "Hitam pekat", "Latar hitam murni, hemat baterai di layar AMOLED", settings.pureBlack) {
                    scope.launch { c.settings.setPureBlack(it) }
                }
            }
        }
        item {
            Group("Pemutar & audio") {
                ActionRow(Icons.Outlined.Alarm, "Alarm musik", "Bangun dengan lagu favoritmu") {
                    app.navigate(Routes.ALARMS)
                }
                Divider()
                ActionRow(Icons.Outlined.Equalizer, "Equalizer", if (settings.equalizer.enabled) "Aktif" else "Nonaktif") {
                    app.navigate(Routes.EQUALIZER)
                }
                Divider()
                SwitchRow(Icons.Outlined.HighQuality, "Kualitas audio tinggi", "Matikan untuk menghemat kuota", settings.highQuality) {
                    scope.launch { c.settings.setHighQuality(it) }
                }
                Divider()
                SwitchRow(Icons.Outlined.GraphicEq, "Lewati jeda hening", "Lompati bagian sunyi di awal & akhir lagu", settings.skipSilence) {
                    scope.launch { c.settings.setSkipSilence(it) }
                }
                Divider()
                SwitchRow(Icons.Outlined.Radio, "Putar otomatis lagu serupa", "Antrean diisi lagu terkait saat hampir habis", settings.autoRadio) {
                    scope.launch { c.settings.setAutoRadio(it) }
                }
                Divider()
                SwitchRow(Icons.Outlined.Translate, "Terjemahkan lirik", "Tampilkan terjemahan Bahasa Indonesia di bawah lirik", settings.translateLyrics) {
                    scope.launch { c.settings.setTranslateLyrics(it) }
                }
            }
        }
        item {
            Group("Penyimpanan & data") {
                SwitchRow(Icons.Outlined.DownloadForOffline, "Unduh otomatis lagu yang disukai", "Lagu yang kamu sukai langsung tersedia offline", settings.autoDownloadLiked) {
                    scope.launch { c.settings.setAutoDownloadLiked(it) }
                }
                Divider()
                ActionRow(Icons.Outlined.FileDownloadOff, "Hapus semua unduhan", "$downloadedCount lagu • ${downloadBytes / (1024 * 1024)} MB") {
                    confirm = "Hapus semua unduhan?" to { c.downloads.removeAll() }
                }
                Divider()
                ActionRow(Icons.Outlined.Storage, "Hapus cache lagu", "Terpakai ${cacheBytes / (1024 * 1024)} MB dari ${com.bytify.app.AppContainer.SONG_CACHE_BYTES / (1024 * 1024)} MB") {
                    confirm = "Hapus cache lagu?" to {
                        scope.launch(Dispatchers.IO) {
                            c.songCache.keys.toList().forEach { c.songCache.removeResource(it) }
                            refreshCache++
                        }
                    }
                }
                Divider()
                ActionRow(Icons.Outlined.History, "Hapus riwayat putar", "Statistik & Recap ikut terhapus") {
                    confirm = "Hapus riwayat putar?" to { scope.launch { c.library.clearHistory() } }
                }
                Divider()
                ActionRow(Icons.Outlined.ManageSearch, "Hapus riwayat pencarian", null) {
                    confirm = "Hapus riwayat pencarian?" to { scope.launch { c.settings.clearSearchHistory() } }
                }
            }
        }
        item {
            Group("Pembaruan") {
                val update by c.updates.state.collectAsState()
                ActionRow(
                    Icons.Outlined.SystemUpdate,
                    "Cek pembaruan",
                    when (val u = update) {
                        UpdateState.Checking -> "Memeriksa…"
                        UpdateState.UpToDate -> "Bytify ${c.updates.currentVersion} sudah versi terbaru"
                        is UpdateState.Available -> "Versi ${u.info.version} tersedia — ketuk untuk melihat"
                        is UpdateState.Downloading -> "Mengunduh ${(u.progress * 100).toInt()}%"
                        is UpdateState.ReadyToInstall -> "Siap dipasang — ketuk untuk memasang"
                        is UpdateState.Failed -> u.message
                        UpdateState.Idle -> "Versi terpasang: ${c.updates.currentVersion}"
                    },
                ) {
                    when (val u = update) {
                        is UpdateState.ReadyToInstall -> c.updates.install(u.file)
                        is UpdateState.Downloading, UpdateState.Checking -> Unit
                        else -> c.appScope.launch { c.updates.check(); c.settings.markUpdateChecked() }
                    }
                }
                Divider()
                SwitchRow(Icons.Outlined.Update, "Cek otomatis", "Periksa versi baru sekali sehari saat aplikasi dibuka", settings.autoCheckUpdates) {
                    scope.launch { c.settings.setAutoCheckUpdates(it) }
                }
            }
        }
        item {
            Group("Tentang") {
                InfoRow(Icons.Outlined.Info, "Bytify ${BuildConfig.VERSION_NAME}", "Musik tanpa iklan, diputar langsung dari YouTube Music")
                Divider()
                InfoRow(Icons.Outlined.Info, "Sumber data",
                    "Audio & pencarian: NewPipe Extractor • Lirik: LrcLib • Tangga lagu: Apple Music RSS")
                Divider()
                InfoRow(Icons.Outlined.Info, "Lisensi", "GNU General Public License v3.0")
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
