package com.bytify.app.ui.screens

import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.ui.theme.cel
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bytify.app.AppContainer
import com.bytify.app.data.model.Song
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.Artwork
import com.bytify.app.ui.components.SectionHeader
import com.bytify.app.ui.components.SongCard
import com.bytify.app.util.daysAgo
import com.bytify.app.util.greeting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Mood untuk chip Beranda & kartu Jelajahi di Cari: label ke kueri pencarian. */
val Moods = listOf(
    "Santai" to "lagu santai chill",
    "Tidur" to "lagu tidur tenang",
    "Semangat" to "lagu semangat",
    "Galau" to "lagu galau",
    "Fokus" to "lofi fokus belajar",
    "Romantis" to "lagu romantis",
    "Olahraga" to "lagu olahraga workout",
    "Pesta" to "lagu pesta dance",
    "Akustik" to "akustik indonesia",
    "Indie" to "indie indonesia",
)

data class HomeUiState(
    val quickPicks: List<Song> = emptyList(),
    val recent: List<Song> = emptyList(),
    val forgotten: List<Song> = emptyList(),
    val chartIndonesia: List<Song> = emptyList(),
    val chartGlobal: List<Song> = emptyList(),
    val albums: List<com.bytify.app.data.remote.Album> = emptyList(),
    val becauseArtist: String? = null,
    val becauseSongs: List<Song> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val remote = MutableStateFlow(HomeUiState())

    val recent: StateFlow<List<Song>> = c.library.recentSongs(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Baris mix personal bergaya Spotify: acak dan mengikuti selera. */
    val mix: StateFlow<List<com.bytify.app.data.music.MixLagu>> = c.musicCatalog.mixPersonal()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val forgotten: StateFlow<List<Song>> = c.library.forgottenFavorites(daysAgo(14))
        .map { list -> list.filter { it.plays >= 2 }.map { it.song.toSong() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Karena kamu mendengarkan X": artis teratas 30 hari terakhir. */
    val because: StateFlow<Pair<String, List<Song>>?> = c.library.topArtists(daysAgo(30), 1)
        .map { it.firstOrNull()?.artist }
        .flatMapLatest { artist ->
            flow {
                if (artist.isNullOrBlank()) emit(null) else {
                    val songs = runCatching { c.youtube.searchSongs(artist) }.getOrDefault(emptyList())
                    emit(artist to songs)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<HomeUiState> = remote

    init {
        // Amati tangga lagu yang disinkron sendiri di AppContainer, jadi lagu
        // terbaru muncul otomatis walau layar sedang terbuka (parity katalog film).
        viewModelScope.launch {
            c.tanggaLaguId.collect { remote.value = remote.value.copy(chartIndonesia = it, loading = false) }
        }
        viewModelScope.launch {
            c.tanggaLaguGlobal.collect { remote.value = remote.value.copy(chartGlobal = it) }
        }
        viewModelScope.launch {
            c.albumPopuler.collect { remote.value = remote.value.copy(albums = it) }
        }
        // Picu sinkron awal bila container belum sempat mengisi saat Home dibuka.
        if (c.tanggaLaguId.value.isEmpty()) refresh()
    }

    fun refresh(force: Boolean = false) {
        remote.value = remote.value.copy(loading = true)
        viewModelScope.launch {
            c.segarkanTanggaLagu(paksa = force)
            remote.value = remote.value.copy(loading = false)
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { HomeViewModel(c) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val app = appState()
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app.container))
    val state by vm.state.collectAsState()
    val recent by vm.recent.collectAsState()
    val forgotten by vm.forgotten.collectAsState()
    val because by vm.because.collectAsState()
    val mix by vm.mix.collectAsState()
    val current by app.player.state.collectAsState()
    val quickPicks = (recent.ifEmpty { state.chartIndonesia }).take(6)

    PullToRefreshBox(
        isRefreshing = state.loading && state.chartIndonesia.isNotEmpty(),
        onRefresh = { vm.refresh(force = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp),
        ) {
            item {
                Row(
                    Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Beranda", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { app.navigate(Routes.HISTORY) }) { Icon(Icons.Outlined.History, "Riwayat") }
                    IconButton(onClick = { app.navigate(Routes.STATS) }) { Icon(Icons.Outlined.Insights, "Statistik") }
                    IconButton(onClick = { app.navigate(Routes.TOGETHER) }) { Icon(Icons.Outlined.Groups, "Dengar Bareng") }
                    IconButton(onClick = { app.navigate(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, "Pengaturan") }
                }
                Text(
                    greeting(),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            if (quickPicks.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickPicks.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { song ->
                                    QuickPick(
                                        song = song,
                                        isCurrent = current.song?.id == song.id,
                                        onClick = { app.play(listOf(song)) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Moods) { (label, query) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .padding(end = 3.dp, bottom = 3.dp)
                                .cel(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp), shadow = 3.dp)
                                .clickable { app.navigate(Routes.collection("mood:$label|$query")) }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        )
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item { SongCarousel("Lanjutkan mendengarkan", recent) }
            }

            item {
                SongCarousel(
                    title = "Top 100 Indonesia",
                    songs = state.chartIndonesia.take(15),
                    loading = state.loading && state.chartIndonesia.isEmpty(),
                    numbered = true,
                    onSeeAll = { app.navigate(Routes.collection("chart:id")) },
                    playAll = state.chartIndonesia,
                )
            }

            if (state.albums.isNotEmpty()) {
                item { AlbumCarousel("Album populer", state.albums.take(15)) }
            }

            because?.let { (artist, songs) ->
                if (songs.isNotEmpty()) {
                    item {
                        SongCarousel(
                            title = "Karena kamu mendengarkan $artist",
                            songs = songs,
                            onSeeAll = { app.navigate(Routes.collection("artist:$artist")) },
                        )
                    }
                }
            }

            // Saran selalu ada walau belum ada riwayat: blend tangga lagu nyata.
            if (because == null) {
                val saran = (state.chartIndonesia + state.chartGlobal).distinctBy { it.id }.drop(15).take(12)
                if (saran.isNotEmpty()) {
                    item { SongCarousel("Pilihan untukmu", saran) }
                }
            }

            if (forgotten.isNotEmpty()) {
                item { SongCarousel("Favorit yang terlupakan", forgotten) }
            }

            item {
                SongCarousel(
                    title = "Hits dunia",
                    songs = state.chartGlobal.take(15),
                    loading = state.loading && state.chartGlobal.isEmpty(),
                    numbered = true,
                    onSeeAll = { app.navigate(Routes.collection("chart:us")) },
                    playAll = state.chartGlobal,
                )
            }

            // Mix personal: beberapa campuran acak yang mengikuti selera,
            // menggantikan daftar peringkat bernomor yang terasa kaku.
            items(mix, key = { it.judul }) { baris ->
                SongGridCarousel(title = baris.judul, songs = baris.lagu)
            }

            if (!state.loading && state.chartIndonesia.isEmpty() && recent.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Gagal memuat Beranda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.refresh(force = true) }) { Text("Coba lagi") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPick(song: Song, isCurrent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .height(64.dp)
            .padding(end = 3.dp, bottom = 3.dp)
            .cel(
                if (isCurrent) BytifyColors.GoldLight else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(14.dp),
                shadow = 3.dp,
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnailUrl, Modifier.size(64.dp), RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) BytifyColors.Navy else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

/** Carousel lagu dengan judul bagian; mengetuk lagu memutar seluruh daftar. */
@Composable
fun SongCarousel(
    title: String,
    songs: List<Song>,
    loading: Boolean = false,
    numbered: Boolean = false,
    onSeeAll: (() -> Unit)? = null,
    playAll: List<Song> = songs,
) {
    val app = appState()
    if (!loading && songs.isEmpty()) return
    SectionHeader(title, action = onSeeAll?.let { { TextButton(onClick = it) { Text("Lihat semua") } } })
    if (loading) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(4) { Artwork(null, Modifier.size(148.dp), RoundedCornerShape(12.dp)) }
        }
        return
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { index, song ->
            Box {
                SongCard(
                    song = song,
                    title = if (numbered) "${index + 1}. ${song.title}" else song.title,
                    onClick = { app.play(playAll, index) },
                )
            }
        }
    }
}

/**
 * Carousel lagu dua baris yang digulir mendatar — bentuk yang dipakai untuk
 * mix personal. Dibanding satu baris, ruang vertikal yang sama memuat dua kali
 * lipat lagu sehingga Beranda terasa lebih padat tanpa menambah guliran.
 */
@Composable
fun SongGridCarousel(title: String, songs: List<Song>) {
    val app = appState()
    if (songs.isEmpty()) return
    SectionHeader(title)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        // 2 x (sampul 128 + jarak 6 + judul + artis) + jarak antarbaris.
        // Dilebihkan sedikit supaya baris kedua teks tidak terpotong.
        modifier = Modifier.height(368.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItemsIndexed(songs, key = { i, s -> "$title-$i-${s.id}" }) { index, song ->
            Column(
                Modifier
                    .width(132.dp)
                    .clickable { app.play(songs, index) },
            ) {
                Artwork(
                    song.thumbnailUrl,
                    Modifier.size(128.dp).cel(shape = RoundedCornerShape(12.dp)),
                    RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Carousel album; mengetuk album membuka daftar lagunya (dicari lewat YouTube). */
@Composable
fun AlbumCarousel(title: String, albums: List<com.bytify.app.data.remote.Album>) {
    val app = appState()
    if (albums.isEmpty()) return
    SectionHeader(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .width(152.dp)
                    .clickable { app.navigate(Routes.collection("album:${album.name}|${album.query}")) },
            ) {
                Artwork(album.artworkUrl, Modifier.size(148.dp).cel(shape = RoundedCornerShape(14.dp)), RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

