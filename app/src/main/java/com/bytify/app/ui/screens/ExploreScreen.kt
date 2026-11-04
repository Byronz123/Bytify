package com.bytify.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytify.app.data.remote.Episode
import com.bytify.app.data.remote.Podcast
import com.bytify.app.data.remote.RadioGenres
import com.bytify.app.data.remote.RadioStation
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.Artwork
import com.bytify.app.ui.components.SectionHeader
import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.ui.theme.cel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hasil muat data jaringan untuk layar Jelajah. */
private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

@Composable
fun ExploreScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Jelajah", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        // Satu pintu untuk konten non-musik: Film, Podcast, Radio, Live TV.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExploreTab(Icons.Rounded.Movie, "Film", tab == 0) { tab = 0 }
            ExploreTab(Icons.Rounded.Podcasts, "Podcast", tab == 1) { tab = 1 }
            ExploreTab(Icons.Rounded.Radio, "Radio", tab == 2) { tab = 2 }
            ExploreTab(Icons.Rounded.LiveTv, "Live TV", tab == 3) { tab = 3 }
        }
        when (tab) {
            0 -> MoviesScreen(embedded = true)
            1 -> PodcastHome()
            2 -> RadioHome()
            else -> LiveTvScreen(embedded = true)
        }
    }
}

@Composable
private fun ExploreTab(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .cel(if (selected) BytifyColors.Gold else MaterialTheme.colorScheme.surfaceContainerLow, CircleShape, shadow = if (selected) 3.dp else 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) BytifyColors.Navy else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Kolom pencarian bersama; mencari otomatis setelah jeda mengetik. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    val focus = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Hapus") }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

private fun LazyListScope.status(load: Load<*>, empty: String, onRetry: () -> Unit) {
    when (load) {
        Load.Loading -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        Load.Failed -> item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Gagal memuat. Periksa koneksi internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text("Coba lagi") }
            }
        }
        is Load.Ready<*> -> if ((load.value as? List<*>)?.isEmpty() == true) item {
            Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
    }
}

// =====================================================================
// Podcast
// =====================================================================

@Composable
private fun PodcastHome() {
    val app = appState()
    val repo = app.container.podcasts
    val subscriptions by repo.subscriptions.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var top by remember { mutableStateOf<Load<List<Podcast>>>(Load.Loading) }
    var results by remember { mutableStateOf<Load<List<Podcast>>?>(null) }
    var latest by remember { mutableStateOf<List<Episode>>(emptyList()) }

    LaunchedEffect(attempt) {
        top = Load.Loading
        top = runCatching { repo.top() }.fold({ Load.Ready(it) }, { Load.Failed })
    }
    LaunchedEffect(query, attempt) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(450)
        results = Load.Loading
        results = runCatching { repo.search(q) }.fold({ Load.Ready(it) }, { Load.Failed })
    }
    LaunchedEffect(subscriptions.map { it.id }) {
        latest = if (subscriptions.isEmpty()) emptyList() else repo.latestFromSubscriptions(subscriptions).take(10)
    }

    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item { SearchField(query, { query = it }, "Cari podcast…") }
        val searching = results
        if (searching != null) {
            item { SectionHeader("Hasil pencarian") }
            if (searching is Load.Ready) items(searching.value, key = { it.id }) { PodcastRow(it) }
            status(searching, "Tidak ada podcast untuk \"$query\".") { attempt++ }
            return@LazyColumn
        }
        if (subscriptions.isNotEmpty()) {
            item {
                SectionHeader("Langgananmu")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(subscriptions, key = { it.id }) { PodcastCard(it) }
                }
            }
        }
        if (latest.isNotEmpty()) {
            item { SectionHeader("Episode terbaru") }
            items(latest, key = { it.id }) { EpisodeRow(it, latest) }
        }
        item { SectionHeader("Podcast teratas Indonesia") }
        val chart = top
        if (chart is Load.Ready) itemsIndexed(chart.value, key = { _, p -> p.id }) { i, p -> PodcastRow(p, number = i + 1) }
        status(chart, "Belum ada data podcast.") { attempt++ }
    }
}

@Composable
private fun PodcastCard(podcast: Podcast) {
    val app = appState()
    Column(Modifier.width(132.dp).clickable { app.navigate(Routes.podcast(podcast.id)) }) {
        Artwork(podcast.artworkUrl, Modifier.size(128.dp).cel(shape = RoundedCornerShape(14.dp)), RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        Text(podcast.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PodcastRow(podcast: Podcast, number: Int? = null) {
    val app = appState()
    Row(
        Modifier.fillMaxWidth().clickable { app.navigate(Routes.podcast(podcast.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text("$number", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
        }
        Artwork(podcast.artworkUrl, Modifier.size(60.dp).cel(shape = RoundedCornerShape(12.dp), stroke = 1.5.dp, shadow = 0.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(podcast.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(podcast.author, podcast.genre).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// =====================================================================
// Radio
// =====================================================================

@Composable
private fun RadioHome() {
    val app = appState()
    val repo = app.container.radio
    val favorites by repo.favorites.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var stations by remember { mutableStateOf<Load<List<RadioStation>>>(Load.Loading) }

    LaunchedEffect(query, genre, attempt) {
        val q = query.trim()
        if (q.isNotEmpty()) delay(450)
        stations = Load.Loading
        stations = runCatching {
            when {
                q.isNotEmpty() -> repo.search(q)
                genre != null -> repo.byTag(genre!!)
                else -> repo.popular()
            }
        }.fold({ Load.Ready(it) }, { Load.Failed })
    }

    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item { SearchField(query, { query = it }, "Cari stasiun radio…") }
        if (query.isBlank()) {
            item {
                LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { GenreChip("Populer", genre == null) { genre = null } }
                    items(RadioGenres) { (label, tag) -> GenreChip(label, genre == tag) { genre = tag } }
                }
            }
            if (favorites.isNotEmpty() && genre == null) {
                item { SectionHeader("Stasiun favorit") }
                items(favorites, key = { "fav-" + it.id }) { StationRow(it, favorites, true) }
            }
        }
        item {
            SectionHeader(
                when {
                    query.isNotBlank() -> "Hasil pencarian"
                    genre != null -> RadioGenres.first { it.second == genre }.first
                    else -> "Populer di Indonesia"
                },
            )
        }
        val list = stations
        if (list is Load.Ready) {
            val favIds = favorites.map { it.id }.toSet()
            items(list.value, key = { it.id }) { StationRow(it, list.value, it.id in favIds) }
        }
        status(list, "Tidak ada stasiun yang cocok.") { attempt++ }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) BytifyColors.Navy else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .cel(if (selected) BytifyColors.Gold else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp), shadow = 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun StationRow(station: RadioStation, list: List<RadioStation>, favorite: Boolean) {
    val app = appState()
    val scope = rememberCoroutineScope()
    val current by app.player.state.collectAsState()
    val playing = current.song?.id == "radio:${station.id}"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                // Antrean berisi stasiun di daftar yang sama agar tombol next/prev berguna.
                val index = list.indexOf(station).coerceAtLeast(0)
                app.player.playSongs(list.map { it.toSong() }, index, radio = false)
            }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            Artwork(station.favicon, Modifier.size(52.dp).cel(shape = RoundedCornerShape(12.dp), stroke = 1.5.dp, shadow = 0.dp), RoundedCornerShape(12.dp))
            if (playing) {
                Box(
                    Modifier.size(52.dp).cel(BytifyColors.Navy.copy(alpha = 0.6f), RoundedCornerShape(12.dp), stroke = 1.5.dp, shadow = 0.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.GraphicEq, null, tint = BytifyColors.Gold) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (playing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    station.tags.take(3).joinToString(", ").ifBlank { null },
                    station.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
                ).joinToString(" • ").ifBlank { "Radio" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = {
            scope.launch {
                app.container.radio.toggleFavorite(station, favorite)
                app.toast(if (favorite) "Dihapus dari favorit" else "Disimpan ke favorit")
            }
        }) {
            Icon(
                if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (favorite) "Hapus dari favorit" else "Simpan ke favorit",
                tint = if (favorite) BytifyColors.Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
