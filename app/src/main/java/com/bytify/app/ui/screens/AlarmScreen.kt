package com.bytify.app.ui.screens

import com.bytify.app.ui.theme.cel
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytify.app.alarm.AlarmRepository
import com.bytify.app.data.db.AlarmEntity
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.appState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Berbunyi dalam 7 jam 20 menit" */
private fun untilText(triggerAt: Long): String {
    val minutes = ((triggerAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    val days = minutes / (24 * 60)
    val hours = (minutes / 60) % 24
    val mins = minutes % 60
    return buildString {
        append("Berbunyi dalam ")
        if (days > 0) append("$days hari ")
        if (hours > 0) append("$hours jam ")
        append("$mins menit")
    }
}

@Composable
fun AlarmScreen() {
    val app = appState()
    val alarmsRepo = app.container.alarms
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val alarms by alarmsRepo.alarms.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<AlarmEntity?>(null) }
    // Detik ini berubah tiap menit agar hitung mundur tetap akurat.
    val tick by produceState(0L) { while (true) { value = System.currentTimeMillis(); delay(30_000) } }
    val canExact = remember(tick) { alarmsRepo.canScheduleExact() }

    editing?.let { alarm ->
        AlarmEditor(
            initial = alarm,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                scope.launch {
                    alarmsRepo.save(updated)?.let { app.toast(untilText(it)) }
                }
            },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Alarm musik", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { editing = AlarmEntity(hour = 6, minute = 0) }) { Icon(Icons.Rounded.Add, "Tambah alarm") }
            }
            Text(
                "Bangun dengan lagu favoritmu. Volume naik perlahan selama 30 detik. Gunakan sumber \"Diunduh\" agar tetap berbunyi tanpa internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (!canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                Row(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
                        .clickable {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.padding(6.dp))
                    Text("Izinkan \"Alarm & pengingat\" agar alarm berbunyi tepat waktu. Ketuk untuk membuka pengaturan.")
                }
            }
        }
        if (alarms.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Belum ada alarm.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { editing = AlarmEntity(hour = 6, minute = 0) }) { Text("Tambah alarm") }
                }
            }
        }
        items(alarms, key = { it.id }) { alarm ->
            Row(
                Modifier
                    .padding(start = 16.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)
                    .fillMaxWidth()
                    .cel(
                        if (alarm.enabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(20.dp),
                    )
                    .clickable { editing = alarm }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "%02d:%02d".format(alarm.hour, alarm.minute),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = MaterialTheme.typography.headlineLarge.fontFamily,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${AlarmRepository.describeDays(alarm.days)} • ${alarm.sourceLabel}${if (alarm.shuffle) " • Acak" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (alarm.enabled) {
                        Text(
                            remember(alarm, tick) { untilText(AlarmRepository.nextTrigger(alarm)) },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { scope.launch { alarmsRepo.delete(alarm) } }) {
                    Icon(Icons.Rounded.Delete, "Hapus alarm", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = { on -> scope.launch { alarmsRepo.setEnabled(alarm, on)?.let { app.toast(untilText(it)) } } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(initial: AlarmEntity, onDismiss: () -> Unit, onSave: (AlarmEntity) -> Unit) {
    val app = appState()
    val playlists by app.container.library.playlists.collectAsState(initial = emptyList())
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var days by remember { mutableIntStateOf(initial.days) }
    var source by remember { mutableStateOf(initial.source to initial.sourceLabel) }
    var shuffle by remember { mutableStateOf(initial.shuffle) }
    val sources = listOf(
        "liked" to "Lagu yang disukai",
        "downloaded" to "Diunduh (offline)",
        "recent" to "Baru diputar",
    ) + playlists.map { "playlist:${it.id}" to it.name }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (initial.id == 0L) "Alarm baru" else "Ubah alarm", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            TimePicker(
                state = time,
                colors = TimePickerDefaults.colors(
                    clockDialColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            Text("Ulangi", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            LazyRow(
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(AlarmRepository.dayLabels().withIndex().toList()) { (i, label) ->
                    val on = days and (1 shl i) != 0
                    FilterChip(
                        selected = on,
                        onClick = { days = days xor (1 shl i) },
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
                AlarmRepository.describeDays(days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Putar dari", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            sources.forEach { (key, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { source = key to label },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = source.first == key, onClick = { source = key to label })
                    Text(label)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { shuffle = !shuffle }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Acak urutan lagu", modifier = Modifier.weight(1f))
                Switch(checked = shuffle, onCheckedChange = { shuffle = it })
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            hour = time.hour,
                            minute = time.minute,
                            days = days,
                            source = source.first,
                            sourceLabel = source.second.removeSuffix(" (offline)"),
                            shuffle = shuffle,
                            enabled = true,
                        ),
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Simpan alarm") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
