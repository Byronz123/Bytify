package com.bytify.app.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bytify.app.ui.theme.BytifyColors

/** Slider volume media sistem; ikut berubah saat tombol volume HP ditekan. */
@Composable
fun VolumeSlider(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (!dragging) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun set(value: Float) {
        volume = value.coerceIn(0f, max.toFloat())
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.toInt(), 0)
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { set(volume - 1) }) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, "Kecilkan volume", Modifier.size(22.dp))
        }
        Slider(
            value = volume,
            onValueChange = { dragging = true; set(it) },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..max.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = BytifyColors.Beige,
                activeTrackColor = BytifyColors.Beige,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f),
            ),
        )
        IconButton(onClick = { set(volume + 1) }) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Besarkan volume", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
