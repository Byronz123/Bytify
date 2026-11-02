package com.bytify.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bytify.app.AppContainer
import com.bytify.app.data.remote.ImportException
import com.bytify.app.data.remote.ImportRepository
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Running(val progress: ImportRepository.Progress) : ImportUiState
    data class Done(val result: ImportRepository.Result) : ImportUiState
    data class Failed(val message: String) : ImportUiState
}

class ImportViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state
    private var job: Job? = null

    fun start(link: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = ImportUiState.Running(ImportRepository.Progress(ImportRepository.Phase.Reading))
            _state.value = try {
                ImportUiState.Done(c.importer.import(link) { _state.value = ImportUiState.Running(it) })
            } catch (e: ImportException) {
                ImportUiState.Failed(e.message.orEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportUiState.Failed("Impor gagal. Periksa koneksi internet lalu coba lagi.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = ImportUiState.Idle
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { ImportViewModel(c) } }
    }
}

@Composable
fun ImportScreen() {
    val app = appState()
    val vm: ImportViewModel = viewModel(factory = ImportViewModel.factory(app.container))
    val state by vm.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var link by rememberSaveable { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.navController.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Impor playlist", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) }
                Spacer(Modifier.height(16.dp))
                Text("Pindahkan playlist-mu ke Bytify", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tempel link playlist atau album publik dari YouTube Music, YouTube, Spotify, atau Apple Music. Setiap lagu dicari otomatis di YouTube Music.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Link playlist") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }) {
                            Icon(Icons.Rounded.ContentPaste, "Tempel")
                        }
                    },
                    enabled = state !is ImportUiState.Running,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                if (state is ImportUiState.Running) {
                    OutlinedButton(onClick = vm::cancel, shape = RoundedCornerShape(14.dp)) { Text("Batalkan") }
                } else {
                    Button(
                        onClick = { vm.start(link) },
                        enabled = link.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Impor") }
                }
            }
        }

        when (val s = state) {
            ImportUiState.Idle -> Unit
            is ImportUiState.Running -> item { ProgressCard(s.progress) }
            is ImportUiState.Failed -> item {
                Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            }
            is ImportUiState.Done -> {
                item {
                    Column(
                        Modifier
                            .padding(20.dp)
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Playlist \"${s.result.name}\" dibuat dengan ${s.result.added} lagu.", style = MaterialTheme.typography.titleMedium)
                        s.result.truncatedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (s.result.unmatched.isNotEmpty()) {
                            Text(
                                "${s.result.unmatched.size} lagu tidak ditemukan di YouTube Music:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { app.navigate(Routes.playlist(s.result.playlistId)) }, shape = RoundedCornerShape(14.dp)) {
                            Text("Buka playlist")
                        }
                    }
                }
                items(s.result.unmatched) { name ->
                    Text("• $name", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: ImportRepository.Progress) {
    Column(Modifier.padding(20.dp).fillMaxWidth()) {
        Text(
            when (progress.phase) {
                ImportRepository.Phase.Reading -> "Membaca playlist…"
                ImportRepository.Phase.Matching -> "Mencari lagu di YouTube Music…"
                ImportRepository.Phase.Saving -> "Menyimpan playlist…"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(10.dp))
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.checked.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Text("Ditemukan ${progress.found}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text("• dicek ${progress.checked} dari ${progress.total}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        }
    }
}
