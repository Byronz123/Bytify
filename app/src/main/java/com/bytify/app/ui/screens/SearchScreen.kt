package com.bytify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bytify.app.data.model.Song
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.SongMenuRequest
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.SongRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val moodColors = listOf(
    0xFF1F7A4D, 0xFF3B4FA0, 0xFFD2662B, 0xFF6A3D8F, 0xFF2A6F86,
    0xFFB43F5E, 0xFF8A6A1F, 0xFF9C2F8C, 0xFF52703A, 0xFF1D5E5A,
)

private sealed interface SearchResult {
    data object Idle : SearchResult
    data object Loading : SearchResult
    data class Done(val songs: List<Song>) : SearchResult
    data object Failed : SearchResult
}

@Composable
fun SearchScreen() {
    val app = appState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val settings by app.container.settings.settings.collectAsState(initial = null)
    val current by app.player.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult>(SearchResult.Idle) }
    var submitted by rememberSaveable { mutableStateOf("") }

    // Cari otomatis setelah pengguna berhenti mengetik sejenak.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            result = SearchResult.Idle
            return@LaunchedEffect
        }
        delay(450)
        result = SearchResult.Loading
        result = runCatching { app.container.youtube.searchSongs(q) }
            .fold({ SearchResult.Done(it) }, { SearchResult.Failed })
        submitted = q
    }

    fun saveQuery(q: String) = scope.launch { app.container.settings.addSearch(q) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Cari", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(16.dp))
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Lagu, artis, atau mood…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Hapus") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                if (query.isNotBlank()) saveQuery(query)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        when (val r = result) {
            SearchResult.Idle -> SearchHome(
                history = settings?.searchHistory.orEmpty(),
                onPick = { query = it },
                onRemove = { scope.launch { app.container.settings.removeSearch(it) } },
                onClear = { scope.launch { app.container.settings.clearSearchHistory() } },
                onMood = { label, q -> app.navigate(Routes.collection("mood:$label|$q")) },
            )
            SearchResult.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            SearchResult.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Pencarian gagal. Periksa koneksi internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SearchResult.Done -> if (r.songs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Tidak ada hasil untuk \"$submitted\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomInset.current + 16.dp)) {
                    itemsIndexed(r.songs, key = { _, s -> s.id }) { _, song ->
                        SongRow(
                            song = song,
                            isCurrent = current.song?.id == song.id,
                            onClick = {
                                focus.clearFocus()
                                saveQuery(submitted)
                                app.play(listOf(song))
                            },
                            onMore = { app.showMenu(SongMenuRequest(song)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHome(
    history: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onMood: (String, String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomInset.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pencarian terakhir", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("Hapus semua") }
                }
            }
            history.forEach { q ->
                item(span = { GridItemSpan(2) }) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(q) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(q) }) { Icon(Icons.Rounded.Close, "Hapus \"$q\"") }
                    }
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Text("Jelajahi mood", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        items(Moods.withIndex().toList()) { (i, mood) ->
            Box(
                Modifier
                    .aspectRatio(1.8f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(moodColors[i % moodColors.size]))
                    .clickable { onMood(mood.first, mood.second) }
                    .padding(14.dp),
            ) {
                Text(mood.first, style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        }
    }
}
