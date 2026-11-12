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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bytify.app.data.live.LiveChannel
import com.bytify.app.ui.LocalBottomInset
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.theme.BytifyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun LiveTvScreen(embedded: Boolean = false) {
    val app = appState()
    val repository = app.container.live
    val scope = rememberCoroutineScope()
    val channels by repository.channels.collectAsState(initial = emptyList())
    val favoriteIds by repository.favoriteIds.collectAsState(initial = emptySet())

    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("all") }
    var selectedCategory by rememberSaveable { mutableStateOf("all") }
    var selectedCountry by rememberSaveable { mutableStateOf("all") }
    var refreshing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var refreshAfterSeconds by remember { mutableIntStateOf(300) }
    var lastGeneratedAt by remember { mutableStateOf<Long?>(null) }
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    suspend fun refreshCatalog() {
        if (!repository.isConfigured || refreshing) return
        refreshing = true
        try {
            val result = repository.refresh()
            refreshAfterSeconds = result.refreshAfterSeconds
            lastGeneratedAt = result.generatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            statusMessage = buildString {
                append("${result.availableChannels} dari ${result.totalChannels} kanal tersedia. Daftar diperiksa ulang otomatis.")
                result.sourceSyncError?.takeIf(String::isNotBlank)?.let { append(" Peringatan sumber: $it") }
            }
        } catch (error: Exception) {
            statusMessage = "Pembaruan gagal: ${error.message ?: "server tidak dapat dijangkau"}. Data cache terakhir tetap ditampilkan."
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(repository.isConfigured) {
        if (repository.isConfigured) {
            while (true) {
                refreshCatalog()
                delay(refreshAfterSeconds.coerceIn(60, 3600) * 1_000L)
            }
        }
    }

    // Satu kanal bisa punya beberapa kategori, disimpan tergabung ("Animation;Comedy").
    // Kalau gabungan itu dipakai apa adanya, SETIAP kombinasi menjadi chip sendiri —
    // terukur 207 chip padahal kategori sebenarnya hanya 28, membuat baris filter
    // praktis tak terpakai. Di sini kategorinya dipipihkan lebih dulu.
    val categories = remember(channels) {
        channels.flatMap { it.kategoriList() }.filter(String::isNotBlank).distinct().sorted()
    }
    val countries = remember(channels) { channels.map { it.country }.filter(String::isNotBlank).distinct().sorted() }
    // Katalog kini 8.681 kanal. Tanpa remember, seluruh daftar disaring ulang
    // pada SETIAP recomposition — termasuk saat menggulir — dan terukur sebagai
    // jank 40,7% dengan persentil-90 61 ms di layar ini.
    val visibleChannels = remember(
        channels, selectedFilter, selectedCategory, selectedCountry, query, favoriteIds, nowEpochMs,
    ) {
        channels.filter { channel ->
            val matchesFilter = when (selectedFilter) {
                "available" -> channel.isCurrentlyAvailable(nowEpochMs)
                "favorites" -> channel.id in favoriteIds
                else -> true
            }
            val matchesCategory = selectedCategory == "all" ||
                channel.kategoriList().any { it.equals(selectedCategory, ignoreCase = true) }
            val matchesCountry = selectedCountry == "all" ||
                channel.country.equals(selectedCountry, ignoreCase = true)
            val matchesQuery = query.isBlank() || channel.name.contains(query, true) ||
                channel.category.contains(query, true) || channel.country.contains(query, true) ||
                channel.description.contains(query, true)
            matchesFilter && matchesCategory && matchesCountry && matchesQuery
        }
    }

    Column(if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Live TV", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Kanal yang tersedia saat ini",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { scope.launch { refreshCatalog() } }, enabled = !refreshing && repository.isConfigured) {
                if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, contentDescription = "Perbarui daftar kanal")
            }
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Cari kanal, kategori, atau negara…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = selectedFilter == "all", onClick = { selectedFilter = "all" }, label = { Text("Semua") })
            }
            item {
                FilterChip(selected = selectedFilter == "available", onClick = { selectedFilter = "available" }, label = { Text("Tersedia") })
            }
            item {
                FilterChip(
                    selected = selectedFilter == "favorites",
                    onClick = { selectedFilter = "favorites" },
                    label = { Text("Favorit") },
                    leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(16.dp)) },
                )
            }
        }

        if (categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = selectedCategory == "all", onClick = { selectedCategory = "all" }, label = { Text("Semua kategori") })
                }
                items(categories, key = { it }) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category, maxLines = 1) },
                    )
                }
            }
        }

        if (countries.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = selectedCountry == "all", onClick = { selectedCountry = "all" }, label = { Text("Semua negara") })
                }
                items(countries, key = { it }) { country ->
                    FilterChip(
                        selected = selectedCountry == country,
                        onClick = { selectedCountry = country },
                        label = { Text(country) },
                    )
                }
            }
        }

        if (!repository.isConfigured) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Backend Live TV belum diatur", fontWeight = FontWeight.Bold)
                    Text(
                        "Tambahkan live.catalog.url=https://HOST/api/live/channels ke local.properties, lalu build ulang aplikasi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        statusMessage?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Pembaruan gagal")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lastGeneratedAt?.let { timestamp ->
            Text(
                "Data katalog: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (refreshing && channels.isEmpty()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BytifyColors.GoldDeep)
            }
        } else if (visibleChannels.isEmpty()) {
            Box(
                Modifier.fillMaxSize().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.LiveTv, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            selectedFilter == "favorites" -> "Belum ada kanal favorit."
                            query.isNotBlank() -> "Tidak ada kanal yang cocok."
                            else -> "Daftar kanal Live TV masih kosong."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (repository.isConfigured) {
                        OutlinedButton(onClick = { scope.launch { refreshCatalog() } }, enabled = !refreshing) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Coba perbarui")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = LocalBottomInset.current + 18.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleChannels, key = { it.id }) { channel ->
                    LiveChannelRow(
                        channel = channel,
                        favorite = channel.id in favoriteIds,
                        isFresh = channel.isFresh(nowEpochMs),
                        nowEpochMs = nowEpochMs,
                        onClick = {
                            app.container.player.pause()
                            app.navigate(Routes.livePlayer(channel.id))
                        },
                        onFavorite = {
                            scope.launch { repository.setFavorite(channel.id, channel.id !in favoriteIds) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveChannelRow(
    channel: LiveChannel,
    favorite: Boolean,
    isFresh: Boolean,
    nowEpochMs: Long,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(66.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = "Logo ${channel.name}",
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(Icons.Outlined.LiveTv, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(channel.kategoriRapi(), channel.country).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(
                            when {
                                !isFresh -> MaterialTheme.colorScheme.outline
                                channel.isAvailable -> Color(0xFF26A269)
                                else -> Color(0xFFB84A4A)
                            },
                        ),
                    )
                    Text(
                        when {
                            !isFresh -> "Status kedaluwarsa"
                            channel.isAvailable -> "Tersedia"
                            else -> "Sedang offline"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            !isFresh -> MaterialTheme.colorScheme.onSurfaceVariant
                            channel.isAvailable -> Color(0xFF26A269)
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                    if (isFresh && !channel.isAvailable && !channel.statusMessage.isNullOrBlank()) {
                        Text("· ${channel.statusMessage}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "Hapus kanal dari favorit" else "Tambahkan kanal ke favorit",
                    tint = if (favorite) BytifyColors.GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/**
 * Berapa lama status kanal masih dianggap berlaku.
 *
 * Nilainya dulu 10 menit, yang cocok untuk pemeriksaan stream secara langsung.
 * Tetapi `checkedAtEpochMs` diisi dengan waktu KATALOG DIBANGUN, dan katalog itu
 * statis — dibangun saat deploy lalu disajikan berhari-hari. Akibatnya setiap
 * kanal selalu tampil "Status kedaluwarsa" sepuluh menit setelah penerbitan,
 * padahal kanalnya baik-baik saja. Ambangnya disetel ke sehari agar sesuai
 * dengan irama pembangunan katalog.
 */
private const val LIVE_STATUS_MAX_AGE_MS = 24 * 60 * 60 * 1000L
/** Kategori kanal sebagai daftar; sumbernya menggabungkannya dengan titik koma. */
private fun LiveChannel.kategoriList(): List<String> =
    category.split(';').map(String::trim).filter(String::isNotEmpty)

/** Label kategori yang enak dibaca: "Animation;Comedy" -> "Animation · Comedy". */
private fun LiveChannel.kategoriRapi(): String = kategoriList().joinToString(" · ")

private fun LiveChannel.isFresh(nowEpochMs: Long): Boolean =
    checkedAtEpochMs > 0L && nowEpochMs >= checkedAtEpochMs && nowEpochMs - checkedAtEpochMs <= LIVE_STATUS_MAX_AGE_MS
private fun LiveChannel.isCurrentlyAvailable(nowEpochMs: Long): Boolean = isAvailable && isFresh(nowEpochMs)
