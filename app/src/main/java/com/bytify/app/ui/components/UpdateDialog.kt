package com.bytify.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bytify.app.data.update.UpdateState
import com.bytify.app.ui.appState
import kotlinx.coroutines.launch

/** Dialog pembaruan: catatan rilis → unduh (progres) → pasang. */
@Composable
fun UpdateDialogHost() {
    val app = appState()
    val updates = app.container.updates
    val state by updates.state.collectAsState()

    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.ReadyToInstall -> s.info
        else -> return
    }

    AlertDialog(
        onDismissRequest = updates::dismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Bytify ${info.version} tersedia") },
        text = {
            Column {
                Text(
                    "Versi terpasang: ${updates.currentVersion} • Ukuran: ${info.apkSize / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        info.notes.ifBlank { "Perbaikan dan peningkatan performa." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                (state as? UpdateState.Downloading)?.let { downloading ->
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { downloading.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    )
                    Text(
                        "Mengunduh ${(downloading.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.ReadyToInstall -> Button(onClick = { updates.install(s.file) }) { Text("Pasang") }
                is UpdateState.Downloading -> Button(onClick = {}, enabled = false) { Text("Mengunduh…") }
                else -> Button(onClick = { app.container.appScope.launch { updates.download(info) } }) { Text("Perbarui") }
            }
        },
        dismissButton = {
            if (state !is UpdateState.Downloading) TextButton(onClick = updates::dismiss) { Text("Nanti") }
        },
    )
}

