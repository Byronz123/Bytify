package com.bytify.app.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch

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
    var selectedFilter by rememberSaveable { mutableStateOf("all") }
    var refreshing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    fun refreshCatalog() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            syncMessage = null
            try {
                val count = repository.refresh()
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
        if (repository.isCatalogConfigured) refreshCatalog()
    }

    val source = when (selectedFilter) {
        "favorites" -> favorites
        "movie" -> movies.filter { !it.isSeries }
        "series" -> movies.filter { it.isSeries }
        else -> movies
    }
    val sectionIds = selectedFilter.removePrefix("section:").takeIf { selectedFilter.startsWith("section:") }
        ?.let { selectedTitle -> sections.firstOrNull { it.title == selectedTitle }?.movieIds?.toSet() }
    val visibleMovies = source.filter { movie ->
        val matchesQuery = query.isBlank() || movie.title.contains(query, true) ||
            movie.overview.contains(query, true) || movie.genres.any { it.contains(query, true) }
        val matchesSection = sectionIds == null || movie.id in sectionIds
        matchesQuery && matchesSection
    }

    Column(if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Film & Serial", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Katalog hiburan Bytify",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = ::refreshCatalog, enabled = !refreshing && repository.isCatalogConfigured) {
                if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, contentDescription = "Perbarui katalog")
            }
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Cari judul, sinopsis, atau genre…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedFilter == "all",
                    onClick = { selectedFilter = "all" },
                    label = { Text("Semua") },
                )
            }
            items(listOf("movie" to "Film", "series" to "Serial")) { (key, label) ->
                FilterChip(selected = selectedFilter == key, onClick = { selectedFilter = key }, label = { Text(label) })
            }
            item {
                FilterChip(
                    selected = selectedFilter == "favorites",
                    onClick = { selectedFilter = "favorites" },
                    label = { Text("Favorit") },
                    leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(16.dp)) },
                )
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
                        "Tambahkan movie.catalog.url=https://HOST/api/catalog.json ke local.properties, " +
                            "lalu build ulang aplikasi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        syncMessage?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (message.contains("gagal", true) || message.contains("belum", true)) {
                    MaterialTheme.colorScheme.error
                } else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (refreshing && movies.isEmpty()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BytifyColors.GoldDeep)
            }
        } else if (visibleMovies.isEmpty()) {
            Box(
                Modifier.fillMaxSize().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Movie, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            selectedFilter == "favorites" -> "Belum ada film favorit."
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
        } else if (selectedFilter == "all" && query.isBlank() && sections.isNotEmpty()) {
            MovieBrowse(
                movies = movies,
                sections = sections,
                history = history,
                modifier = Modifier.weight(1f),
                onOpen = { app.navigate(Routes.movieDetail(it.id)) },
                onResume = { movie, episodeId -> app.navigate(Routes.moviePlayer(movie.id, episodeId)) },
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 2.dp,
                    bottom = LocalBottomInset.current + 18.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleMovies, key = { it.id }) { movie ->
                    MovieRow(
                        movie = movie,
                        isFavorite = movie.id in favoriteIds,
                        onClick = { app.navigate(Routes.movieDetail(movie.id)) },
                        onFavorite = {
                            scope.launch {
                                repository.setFavorite(movie, movie.id !in favoriteIds)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieRow(
    movie: Movie,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = movie.posterUrl,
                contentDescription = "Poster ${movie.title}",
                modifier = Modifier.width(88.dp).height(124.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(movie.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(movie.releaseYear?.toString(), movie.durationMin?.let { "$it menit" })
                    .joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (movie.rating != null) {
                    Text("★ ${"%.1f".format(java.util.Locale.ROOT, movie.rating)}", style = MaterialTheme.typography.labelMedium, color = BytifyColors.GoldDeep)
                }
                if (movie.genres.isNotEmpty()) {
                    Text(movie.genres.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(movie.overview.ifBlank { "Belum ada sinopsis." }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
}

/** Tampilan jelajah: lanjutkan menonton + rel poster per section katalog. */
@Composable
private fun MovieBrowse(
    movies: List<Movie>,
    sections: List<com.bytify.app.data.movie.MovieSection>,
    history: List<com.bytify.app.data.db.MovieHistoryEntity>,
    modifier: Modifier,
    onOpen: (Movie) -> Unit,
    onResume: (Movie, String?) -> Unit,
) {
    val byId = remember(movies) { movies.associateBy { it.id } }
    // Riwayat episode disimpan dengan id episode; petakan kembali ke serialnya.
    val episodeOwner = remember(movies) {
        movies.filter { it.isSeries }.flatMap { s -> s.episodes.map { it.id to s } }.toMap()
    }
    val resume = remember(history, byId, episodeOwner) {
        history.filter { it.durationMs <= 0L || it.positionMs < it.durationMs - 15_000L }
            .mapNotNull { h ->
                byId[h.movieId]?.let { Triple(it, null as String?, h) }
                    ?: episodeOwner[h.movieId]?.let { Triple(it, h.movieId, h) }
            }
            .distinctBy { it.first.id }
            .take(12)
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (resume.isNotEmpty()) {
            item(key = "resume") {
                RailTitle("Lanjutkan menonton")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resume, key = { it.third.movieId }) { (movie, episodeId, h) ->
                        Column(Modifier.width(200.dp).clickable { onResume(movie, episodeId) }) {
                            Box {
                                AsyncImage(
                                    model = movie.backdropUrl ?: movie.posterUrl,
                                    contentDescription = movie.title,
                                    modifier = Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop,
                                )
                                if (h.durationMs > 0L) {
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) },
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                        color = BytifyColors.Gold,
                                    )
                                }
                            }
                            Text(h.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        items(sections, key = { "section:" + it.title }) { section ->
            val list = section.movieIds.mapNotNull(byId::get)
            if (list.isNotEmpty()) {
                RailTitle(section.title)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { section.title + it.id }) { movie -> PosterCard(movie) { onOpen(movie) } }
                }
            }
        }
    }
}

@Composable
private fun RailTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
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
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(BytifyColors.Gold).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(movie.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        movie.releaseYear?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
