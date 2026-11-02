package com.bytify.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bytify.app.data.prefs.Settings
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.appState
import kotlinx.coroutines.launch

private fun formatHz(hz: Int) = if (hz >= 1000) "${"%.1f".format(hz / 1000f).removeSuffix(".0")} kHz" else "$hz Hz"

@Composable
fun EqualizerScreen() {
    val app = appState()
    val c = app.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState(initial = Settings())
    val info by c.audioEffects.info.collectAsState()
    val levels by c.audioEffects.currentLevels.collectAsState()
    val eq = settings.equalizer
    // Nilai sementara saat slider digeser, agar gerakannya halus.
    val dragging = remember { mutableStateMapOf<Int, Float>() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Equalizer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = eq.enabled,
                    onCheckedChange = { scope.launch { c.settings.setEqualizerEnabled(it) } },
                    enabled = info != null,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }

        val eqInfo = info
        if (eqInfo == null) {
            item {
                Text(
                    "Putar sebuah lagu terlebih dahulu untuk mengaktifkan equalizer. Jika tetap tidak muncul, perangkat ini tidak mendukung equalizer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@LazyColumn
        }

        item {
            Text("Preset", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = eq.preset < 0,
                        onClick = { scope.launch { c.settings.setEqualizerBands(levels) } },
                        label = { Text("Kustom") },
                        enabled = eq.enabled,
                        shape = RoundedCornerShape(12.dp),
                        colors = chipColors(),
                    )
                }
                itemsIndexed(eqInfo.presets) { index, name ->
                    FilterChip(
                        selected = eq.preset == index,
                        onClick = { scope.launch { c.settings.setEqualizerPreset(index) } },
                        label = { Text(name) },
                        enabled = eq.enabled,
                        shape = RoundedCornerShape(12.dp),
                        colors = chipColors(),
                    )
                }
            }
        }

        item {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                eqInfo.centerFrequenciesHz.forEachIndexed { band, hz ->
                    val level = dragging[band] ?: levels.getOrNull(band)?.toFloat() ?: 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatHz(hz), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                        Slider(
                            value = level,
                            onValueChange = { dragging[band] = it },
                            onValueChangeFinished = {
                                val updated = levels.toMutableList().apply {
                                    dragging[band]?.let { set(band, it.toInt()) }
                                }
                                dragging.remove(band)
                                scope.launch { c.settings.setEqualizerBands(updated) }
                            },
                            valueRange = eqInfo.minLevel.toFloat()..eqInfo.maxLevel.toFloat(),
                            enabled = eq.enabled,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${if (level > 0) "+" else ""}${"%.0f".format(level / 100)} dB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp).padding(start = 8.dp),
                        )
                    }
                }
                TextButton(
                    onClick = { scope.launch { c.settings.setEqualizerBands(List(eqInfo.centerFrequenciesHz.size) { 0 }) } },
                    enabled = eq.enabled,
                ) { Text("Reset ke datar") }
            }
        }

        if (eqInfo.bassSupported) {
            item {
                Text("Bass boost", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 24.dp))
                val bass = dragging[-1] ?: eq.bassStrength.toFloat()
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = bass,
                        onValueChange = { dragging[-1] = it },
                        onValueChangeFinished = {
                            val value = dragging.remove(-1)?.toInt() ?: eq.bassStrength
                            scope.launch { c.settings.setBassStrength(value) }
                        },
                        valueRange = 0f..1000f,
                        enabled = eq.enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(bass / 10).toInt()}%", modifier = Modifier.width(56.dp).padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)
