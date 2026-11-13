package com.bytify.app.ui.screens

import com.bytify.app.data.movie.KATALOG_MASIH_SEGAR
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bytify.app.data.movie.Movie
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Jeda sebelum ketikan dipakai menyaring katalog film. */
private const val JEDA_PENCARIAN_FILM_MS = 250L

@Composable
fun MoviesScreen(embedded: Boolean = false) {
    val app = appState()
    val repository = app.container.movies
    val scope = rememberCoroutineScope()
    val movies by repository.movies.collectAsState(initial = emptyList())
    val favorites by repository.favoriteMovies.collectAsState(initial = emptyList())
    val favoriteIds by repository.favoriteIds.collectAsState(initial = emptySet())
    val sections by repository.sections.collectAsState(initial = emptyList())
    val history by repository.history.collectAsState(initial = emptyList())

    var query by rememberSaveable { mutableStateOf("") }
    // Ketikan tampil seketika lewat [query]; penyaringan 25.135 film memakai
    // [queryTersaring] yang menyusul setelah jeda, supaya mengetik tidak berat.
    var queryTersaring by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isEmpty()) queryTersaring = "" else {
            delay(JEDA_PENCARIAN_FILM_MS)
            queryTersaring = query
        }
    }
    var selectedFilter by rememberSaveable { mutableStateOf("all") }
    var refreshing by remember { mutableStateOf(false) }
    var searchingRemote by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    fun refreshCatalog(paksa: Boolean = true) {
        if (refreshing) return
        scope.launch {
            refreshing = true
            syncMessage = null
            try {
                val count = if (paksa) repository.refresh() else repository.refreshIfStale()
                if (count == KATALOG_MASIH_SEGAR) {
                    // Katalog masih baru; tidak perlu unduh ulang berkas penuh.
                    return@launch
                }
                syncMessage = if (count == 0) {
                    "Katalog berhasil diperiksa; belum ada film baru dalam respons."
                } else {
                    "$count film berhasil disinkronkan."
                }
            } catch (error: Exception) {
                syncMessage = error.message ?: "Sinkronisasi katalog gagal."
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (repository.isCatalogConfigured) refreshCatalog(paksa = false)
    }

    // Pencarian otomatis ke server LK21 saat pengguna mengetik minimal 2 huruf
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length >= 2 && repository.isCatalogConfigured) {
            delay(450)
            searchingRemote = true
            try {
                repository.searchRemoteAndCache(trimmed)
            } finally {
                searchingRemote = false
            }
        } else {
            searchingRemote = false
        }
    }

    // Penyaringan di-cache supaya katalog ~10.000 film tidak dipindai ulang pada
    // setiap recomposition. Catatan hasil ukur: ini TIDAK menurunkan jank saat
    // kolom cari kosong (predikatnya langsung true, jadi sudah murah); gunanya
    // terasa saat pengguna mengetik kueri, ketika pemindaian benar-benar bekerja.
    val source = remember(selectedFilter, movies, favorites) {
        when {
            selectedFilter == "favorites" -> favorites
            selectedFilter == "movie" -> movies.filter { !it.isSeries }
            selectedFilter == "series" -> movies.filter { it.isSeries }
            selectedFilter.startsWith("genre:") -> {
                val genre = selectedFilter.removePrefix("genre:")
                movies.filter { film -> film.genres.any { it.equals(genre, true) } }
            }
            else -> movies
        }
    }

    // Katalog LK21 yang dipanen sejauh ini tidak memuat satu pun serial (25.135
    // entri, semuanya bertipe "movie"): penjelajah memang melewati /series,
    // /nontondrama, dan /anime. Chip Film/Serial karena itu hanya ditawarkan
    // kalau datanya benar-benar ada, supaya tidak ada saringan yang pasti kosong.
    val adaSerial = remember(movies) { movies.any { it.isSeries } }

    // Deret per genre dibangun DI LUAR main thread: 25 ribu film dikelompokkan
    // ke 29 genre. Tiap deret menyimpan jumlah penuhnya untuk tombol "Lihat semua".
    val deretGenre by produceState(emptyList<DeretGenre>(), movies) {
        value = withContext(Dispatchers.Default) {
            val ember = HashMap<String, MutableList<Movie>>()
            movies.forEach { film ->
                film.genres.forEach { mentah ->
                    val genre = mentah.trim()
                    if (genre.isNotEmpty()) ember.getOrPut(genre) { mutableListOf() }.add(film)
                }
            }
            ember.entries
                .filter { it.value.size >= BATAS_DERET_GENRE }
                .sortedByDescending { it.value.size }
                .map { (genre, daftar) ->
                    DeretGenre(
                        genre = genre,
                        film = daftar.sortedWith(
                            compareByDescending<Movie> { it.releaseYear ?: 0 }
                                .thenByDescending { it.rating ?: 0.0 },
                        ).take(ISI_DERET_GENRE),
                        jumlah = daftar.size,
                    )
                }
        }
    }
    val sectionIds = remember(selectedFilter, sections) {
        selectedFilter.removePrefix("section:").takeIf { selectedFilter.startsWith("section:") }
            ?.let { selectedTitle -> sections.firstOrNull { it.title == selectedTitle }?.movieIds?.toSet() }
    }
    // Penyaringan 25.135 film dijalankan DI LUAR main thread. Terukur di Galaxy
    // A36: sekali jalan memakan ~600 ms, yang muncul sebagai satu pembekuan saat
    // mengetik. produceState membiarkan daftar lama tampil sampai hasil baru siap.
    val visibleMovies by produceState(
        initialValue = emptyList<Movie>(),
        source, queryTersaring, sectionIds,
    ) {
        value = withContext(Dispatchers.Default) {
            source.filter { movie ->
                val matchesQuery = queryTersaring.isBlank() ||
                    movie.title.contains(queryTersaring, true) ||
                    movie.overview.contains(queryTersaring, true) ||
                    movie.genres.any { it.contains(queryTersaring, true) }
                val matchesSection = sectionIds == null || movie.id in sectionIds
                matchesQuery && matchesSection
            }
        }
    }

    // Peta film dan pemilik episode dibangun DI LUAR main thread; keduanya
    // dipakai baris "Lanjutkan menonton".
    val peta by produceState(PetaFilm(), movies) {
        value = withContext(Dispatchers.Default) {
            PetaFilm(
                byId = movies.associateBy { it.id },
                episodeOwner = movies.filter { it.isSeries }
                    .flatMap { serial -> serial.episodes.map { it.id to serial } }.toMap(),
            )
        }
    }
    val resume = remember(history, peta) {
        history.filter { it.durationMs <= 0L || it.positionMs < it.durationMs - 15_000L }
            .mapNotNull { h ->
                peta.byId[h.movieId]?.let { Triple(it, null as String?, h) }
                    ?: peta.episodeOwner[h.movieId]?.let { Triple(it, h.movieId, h) }
            }
            .distinctBy { it.first.id }
            .take(12)
    }

    val bawaan = selectedFilter == "all" && query.isBlank() && movies.isNotEmpty()

    // SATU LazyColumn untuk kedua tampilan. Dulu tampilan bawaan dan hasil
    // pencarian adalah dua pohon komposisi berbeda, jadi huruf pertama yang
    // diketik menukar seluruh pohon: kolom cari kehilangan identitasnya, fokus
    // lepas, dan papan ketik menutup sendiri. Di sini kepala selalu item yang
    // sama, hanya isi di bawahnya yang berganti.
    LazyColumn(
        modifier = if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 18.dp),
    ) {
        item(key = "kepala") {
            Column {
                if (!embedded) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Film & Serial", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = ::refreshCatalog, enabled = !refreshing && repository.isCatalogConfigured) {
                            if (refreshing || searchingRemote) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, contentDescription = "Perbarui katalog")
                        }
                    }
                }

                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Cari judul, sinopsis, atau genre…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (refreshing || searchingRemote) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (embedded && repository.isCatalogConfigured) {
                            IconButton(onClick = ::refreshCatalog) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Perbarui katalog")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedFilter == "all",
                            onClick = { selectedFilter = "all" },
                            label = { Text("Semua") },
                        )
                    }
                    if (adaSerial) {
                        items(listOf("movie" to "Film", "series" to "Serial")) { (kunci, label) ->
                            FilterChip(
                                selected = selectedFilter == kunci,
                                onClick = { selectedFilter = kunci },
                                label = { Text(label) },
                            )
                        }
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == "favorites",
                            onClick = { selectedFilter = "favorites" },
                            label = { Text("Favorit") },
                            leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(16.dp)) },
                        )
                    }
                    // Genre yang sedang dibuka lewat "Lihat semua" tampil sebagai
                    // chip aktif, jadi pengguna tahu sedang menyaring apa.
                    if (selectedFilter.startsWith("genre:")) {
                        item {
                            FilterChip(
                                selected = true,
                                onClick = { selectedFilter = "all" },
                                label = { Text(selectedFilter.removePrefix("genre:")) },
                                trailingIcon = { Icon(Icons.Rounded.Close, "Hapus saringan genre", Modifier.size(16.dp)) },
                            )
                        }
                    }
                    items(sections, key = { it.title }) { section ->
                        FilterChip(
                            selected = selectedFilter == "section:${section.title}",
                            onClick = { selectedFilter = "section:${section.title}" },
                            label = { Text(section.title, maxLines = 1) },
                        )
                    }
                }

                if (!repository.isCatalogConfigured) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Backend katalog belum diatur", fontWeight = FontWeight.Bold)
                            Text(
                                "Tambahkan movie.catalog.url=http://10.0.2.2:8787/api/catalog.json ke local.properties, " +
                                    "lalu build ulang aplikasi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                syncMessage?.let { pesan ->
                    Text(
                        pesan,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pesan.contains("gagal", true) || pesan.contains("belum", true)) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (bawaan) {
            if (resume.isNotEmpty()) {
                item(key = "resume") {
                    JudulDeret("Lanjutkan menonton")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(resume, key = { it.third.movieId }) { (film, idEpisode, h) ->
                            Column(Modifier.width(200.dp).clickable { app.navigate(Routes.moviePlayer(film.id, idEpisode)) }) {
                                Box {
                                    AsyncImage(
                                        model = film.backdropUrl ?: film.posterUrl,
                                        contentDescription = film.title,
                                        modifier = Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentScale = ContentScale.Crop,
                                    )
                                    if (h.durationMs > 0L) {
                                        androidx.compose.material3.LinearProgressIndicator(
                                            progress = { (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) },
                                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            color = BytifyColors.Gold,
                                        )
                                    }
                                }
                                Text(
                                    h.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            items(sections, key = { "bagian:" + it.title }) { section ->
                val daftar = remember(section, peta) { section.movieIds.mapNotNull(peta.byId::get) }
                if (daftar.isNotEmpty()) {
                    Deret(
                        judul = section.title,
                        jumlah = daftar.size,
                        film = daftar.take(ISI_DERET_GENRE),
                        onLihatSemua = { selectedFilter = "section:${section.title}" },
                        onBuka = { app.navigate(Routes.movieDetail(it.id)) },
                    )
                }
            }

            items(deretGenre, key = { "genre:" + it.genre }) { deret ->
                Deret(
                    judul = deret.genre,
                    jumlah = deret.jumlah,
                    film = deret.film,
                    onLihatSemua = { selectedFilter = "genre:${deret.genre}" },
                    onBuka = { app.navigate(Routes.movieDetail(it.id)) },
                )
            }
        } else if ((refreshing || searchingRemote) && visibleMovies.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BytifyColors.GoldDeep)
                }
            }
        } else if (visibleMovies.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Movie, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when {
                                selectedFilter == "favorites" -> "Belum ada film favorit."
                                selectedFilter == "series" -> "Katalog ini belum memuat serial."
                                query.isNotBlank() -> "Tidak ada film yang cocok dengan pencarian."
                                else -> "Katalog film masih kosong."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (repository.isCatalogConfigured) {
                            OutlinedButton(onClick = ::refreshCatalog, enabled = !refreshing) {
                                Icon(Icons.Rounded.Refresh, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Coba sinkronkan")
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "jumlah") {
                Text(
                    "${visibleMovies.size} film",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            items(visibleMovies, key = { it.id }) { film ->
                MovieRow(
                    movie = film,
                    isFavorite = film.id in favoriteIds,
                    onClick = { app.navigate(Routes.movieDetail(film.id)) },
                    onFavorite = { scope.launch { repository.setFavorite(film, film.id !in favoriteIds) } },
                )
            }
        }
    }
}

/** Jumlah film minimum agar sebuah genre layak punya deretnya sendiri. */
private const val BATAS_DERET_GENRE = 12

/** Banyak poster yang ditampilkan dalam satu deret mendatar. */
private const val ISI_DERET_GENRE = 20

private data class PetaFilm(
    val byId: Map<String, Movie> = emptyMap(),
    val episodeOwner: Map<String, Movie> = emptyMap(),
)

private data class DeretGenre(
    val genre: String,
    val film: List<Movie>,
    val jumlah: Int,
)

/** Satu deret poster mendatar dengan tombol "Lihat semua". */
@Composable
private fun Deret(
    judul: String,
    jumlah: Int,
    film: List<Movie>,
    onLihatSemua: () -> Unit,
    onBuka: (Movie) -> Unit,
) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(judul, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onLihatSemua) {
                Text("Lihat semua")
                Text(
                    " ($jumlah)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(film, key = { judul + it.id }) { satu -> PosterCard(satu) { onBuka(satu) } }
        }
    }
}

@Composable
private fun JudulDeret(judul: String) {
    Text(
        judul,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
    )
}

/**
 * Baris film mengikuti bentuk baris lagu: sampul kecil, judul satu baris, satu
 * baris keterangan, tombol di ujung. Kartu tebal dengan poster 88x124 dan dua
 * baris sinopsis membuat daftar terasa besar dan hanya memuat empat judul per
 * layar; bentuk ini memuat hampir dua kali lipat dan sejajar dengan daftar lagu.
 */
@Composable
private fun MovieRow(
    movie: Movie,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = movie.posterUrl,
            contentDescription = "Poster ${movie.title}",
            modifier = Modifier.width(52.dp).height(74.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                movie.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val keterangan = listOfNotNull(
                movie.releaseYear?.toString(),
                movie.rating?.let { "★ " + "%.1f".format(java.util.Locale.ROOT, it) },
                movie.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            Text(
                keterangan.ifBlank { "Belum ada keterangan." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Hapus dari favorit" else "Tambahkan ke favorit",
                tint = if (isFavorite) BytifyColors.GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PosterCard(movie: Movie, onClick: () -> Unit) {
    Column(Modifier.width(118.dp).clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = movie.posterUrl,
                contentDescription = "Poster ${movie.title}",
                modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            if (movie.isSeries) {
                Text(
                    "SERIAL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = BytifyColors.Navy,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(BytifyColors.Gold).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            movie.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        movie.releaseYear?.let {
            Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
