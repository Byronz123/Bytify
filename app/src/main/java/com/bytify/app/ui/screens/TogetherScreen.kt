package com.bytify.app.ui.screens

import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.ui.theme.topShine
import com.bytify.app.ui.theme.cel
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytify.app.data.prefs.Settings
import com.bytify.app.data.together.TogetherState
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.Artwork
import kotlinx.coroutines.launch

@Composable
fun TogetherScreen() {
    val app = appState()
    val together = app.container.together
    val state by together.state.collectAsState()
    val suggestions by together.suggestions.collectAsState()
    val settings by app.container.settings.settings.collectAsState(initial = Settings())
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(settings.togetherName) {
        if (name.isBlank()) name = settings.togetherName
    }

    fun saveName() = app.container.appScope.launch { app.container.settings.setTogetherName(name) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = LocalBottomInset.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Dengar Bareng", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(88.dp).cel(BytifyColors.Gold, CircleShape).topShine(),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Groups, null, tint = BytifyColors.Navy, modifier = Modifier.size(44.dp)) }
                Spacer(Modifier.height(14.dp))
                Text("Dengar Bareng", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Dengar & nonton bareng secara realtime. Buat room sebagai host, atau gabung dengan kode dari temanmu. " +
                            "Lagu disinkronkan sampai ke posisi putarnya; untuk film dan Live TV, tamu otomatis dibawa ke " +
                            "tontonan yang sama dan host memberi aba-aba mulai lewat tombol di layar pemutar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (!together.isConfigured) {
            item {
                Card {
                    Text("Belum dikonfigurasi", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Isi supabase.url dan supabase.key di local.properties, lalu build ulang aplikasi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        item { StatusCard(state) }

        when (val s = state) {
            TogetherState.Idle -> item {
                Card {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(24) },
                        label = { Text("Nama kamu") },
                        leadingIcon = { Icon(Icons.Rounded.Person, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(6) },
                        label = { Text("Kode room (untuk bergabung)") },
                        leadingIcon = { Icon(Icons.Rounded.Key, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { saveName(); together.createRoom(name) },
                            enabled = name.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("Buat room") }
                        OutlinedButton(
                            onClick = { saveName(); together.joinRoom(name, code) },
                            enabled = name.isNotBlank() && code.length == 6,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("Gabung") }
                    }
                }
            }
            is TogetherState.Connecting -> item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Menghubungkan ke room ${s.code}…")
                    }
                    TextButton(onClick = together::leave) { Text("Batal") }
                }
            }
            is TogetherState.Ended -> item {
                Card {
                    Text(s.message, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = together::dismissEnded, shape = RoundedCornerShape(14.dp)) { Text("Oke") }
                }
            }
            is TogetherState.InRoom -> {
                item {
                    Card {
                        Text("KODE ROOM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.code,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 6.sp,
                                fontFamily = MaterialTheme.typography.headlineLarge.fontFamily,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(s.code))
                                app.toast("Kode disalin")
                            }) { Icon(Icons.Rounded.ContentCopy, "Salin kode") }
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Dengar bareng aku di Bytify! Buka Dengar Bareng lalu masukkan kode: ${s.code}")
                                }
                                context.startActivity(Intent.createChooser(send, "Undang teman"))
                            }) { Icon(Icons.Rounded.Share, "Undang teman") }
                        }
                        Text(
                            if (s.isHost) "Kamu host. Lagu yang kamu putar akan diikuti semua anggota."
                            else "Pemutaran mengikuti host. Sarankan lagu lewat menu ⋮ pada lagu mana pun.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Text("Anggota (${s.members.size})", style = MaterialTheme.typography.titleMedium)
                }
                items(s.members, key = { it.key }) { member ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (member.isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                member.name.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = if (member.isHost) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(member.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (member.isHost) {
                            Text(
                                "HOST",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (s.isHost && suggestions.isNotEmpty()) {
                    item { Text("Saran lagu", style = MaterialTheme.typography.titleMedium) }
                    items(suggestions, key = { it.second.id }) { (from, song) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(song.thumbnailUrl, Modifier.size(48.dp), RoundedCornerShape(8.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("dari $from", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { app.player.playNext(song); together.clearSuggestion(song); app.toast("Diputar berikutnya") }) {
                                Text("Antrekan")
                            }
                            TextButton(onClick = { together.clearSuggestion(song) }) { Text("Abaikan", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                item {
                    Button(
                        onClick = together::leave,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (s.isHost) "Akhiri room" else "Keluar dari room")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: TogetherState) {
    val (label, color) = when (state) {
        is TogetherState.InRoom ->
            if (state.reconnecting) "Menyambung ulang…" to MaterialTheme.colorScheme.tertiary
            else "Terhubung" to MaterialTheme.colorScheme.primary
        is TogetherState.Connecting -> "Menghubungkan…" to MaterialTheme.colorScheme.tertiary
        else -> "Tidak terhubung" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 4.dp, bottom = 4.dp)
            .cel(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}
