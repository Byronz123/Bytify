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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Kotak teks memakai [query] supaya ketikan tampil seketika, tetapi
    // PENYARINGAN memakai [queryTersaring] yang baru menyusul setelah jeda.
    // Tanpa ini, setiap huruf menyaring ulang 8.681 kanal di main thread dan
    // mengetik terasa tersendat.
    var queryTersaring by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isEmpty()) queryTersaring = "" else {
            delay(JEDA_PENCARIAN_MS)
            queryTersaring = query
        }
    }
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
                append("${result.availableChannels} kanal tersedia")
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

    // Etalase kanal mengikuti bentuk layar Film: satu deret mendatar per
    // kategori, bukan satu daftar 8.681 baris yang harus digulir buta.
    val deretKategori by produceState(emptyList<DeretKanal>(), channels) {
        value = withContext(Dispatchers.Default) {
            val ember = HashMap<String, MutableList<LiveChannel>>()
            channels.forEach { kanal ->
                kanal.kategoriList().forEach { mentah ->
                    val kategori = mentah.trim()
                    if (kategori.isNotEmpty()) ember.getOrPut(kategori) { mutableListOf() }.add(kanal)
                }
            }
            ember.entries
                .filter { it.value.size >= BATAS_DERET_KANAL }
                .sortedByDescending { it.value.size }
                .map { (kategori, isi) ->
                    DeretKanal(kategori, isi.take(ISI_DERET_KANAL), isi.size)
                }
        }
    }

    // Negara yang ditawarkan saat sebuah kategori dibuka hanya negara yang
    // benar-benar punya kanal di kategori itu. Daftar 180 negara penuh akan
    // sebagian besar berujung "tidak ada kanal yang cocok".
    val negaraKategori by produceState(emptyList<String>(), channels, selectedCategory) {
        val kategori = selectedCategory
        value = withContext(Dispatchers.Default) {
            if (kategori == "all") emptyList()
            else channels
                .filter { kanal -> kanal.kategoriList().any { it.equals(kategori, true) } }
                .map { it.country }
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
        }
    }
    // Katalog kini 8.681 kanal. Tanpa remember, seluruh daftar disaring ulang
    // pada SETIAP recomposition — termasuk saat menggulir — dan terukur sebagai
    // jank 40,7% dengan persentil-90 61 ms di layar ini.
    // Sama seperti layar film: penyaringan 8.681 kanal dijalankan di luar main
    // thread supaya mengetik di kotak cari tidak membekukan antarmuka.
    val visibleChannels by produceState(
        initialValue = emptyList<LiveChannel>(),
        channels, selectedFilter, selectedCategory, selectedCountry, queryTersaring, favoriteIds, nowEpochMs,
    ) {
        value = withContext(Dispatchers.Default) {
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
                val matchesQuery = queryTersaring.isBlank() ||
                    channel.name.contains(queryTersaring, true) ||
                    channel.category.contains(queryTersaring, true) ||
                    channel.country.contains(queryTersaring, true) ||
                    channel.description.contains(queryTersaring, true)
                matchesFilter && matchesCategory && matchesCountry && matchesQuery
            }
        }
    }

    val etalase = selectedFilter == "all" && selectedCategory == "all" &&
        selectedCountry == "all" && query.isBlank() && deretKategori.isNotEmpty()

    // Model tata letak mengikuti Podcast/Radio: seluruh kepala (pencarian,
    // chip, catatan) ikut menggulung bersama daftar, sehingga kanal memakai
    // tinggi layar penuh dan tidak terdesak ke separuh bawah.
    LazyColumn(
        modifier = if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 18.dp),
    ) {
        if (!embedded) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Live TV", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { refreshCatalog() } }, enabled = !refreshing && repository.isConfigured) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, contentDescription = "Perbarui daftar kanal")
                    }
                }
            }
        }

        item {
            SearchField(
                query = query,
                onQuery = { query = it },
                placeholder = "Cari kanal TV…",
                sibuk = refreshing,
            )
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
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
                // Kategori yang sedang dibuka lewat "Lihat semua" tampil sebagai
                // chip aktif yang bisa dilepas, jadi pengguna tahu sedang di mana.
                if (selectedCategory != "all") {
                    item {
                        FilterChip(
                            selected = true,
                            onClick = { selectedCategory = "all"; selectedCountry = "all" },
                            label = { Text(selectedCategory, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Keluar dari kategori", Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }

        // Begitu sebuah kategori dibuka, saringannya adalah NEGARA. Itu yang
        // masih membedakan kanal di dalam satu kategori; menyodorkan daftar
        // kategori lagi di sini tidak menyaring apa pun.
        if (selectedCategory != "all" && negaraKategori.isNotEmpty()) {
            item {
                Text(
                    "Negara",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedCountry == "all",
                            onClick = { selectedCountry = "all" },
                            label = { Text("Semua negara") },
                        )
                    }
                    items(negaraKategori, key = { it }) { negara ->
                        FilterChip(
                            selected = selectedCountry == negara,
                            onClick = { selectedCountry = if (selectedCountry == negara) "all" else negara },
                            label = { Text(negara) },
                        )
                    }
                }
            }
        }

        if (!repository.isConfigured) {
            item {
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
        }

        val catatanKepala = statusMessage
            ?: lastGeneratedAt?.let {
                "Data katalog: " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
            }
        if (catatanKepala != null) {
            item {
                Text(
                    catatanKepala,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (catatanKepala.startsWith("Pembaruan gagal")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (etalase) {
            items(deretKategori, key = { "kategori:" + it.kategori }) { deret ->
                DeretKanalMendatar(
                    deret = deret,
                    onLihatSemua = { selectedCategory = deret.kategori },
                    onBuka = { kanal ->
                        app.container.player.pause()
                        app.navigate(Routes.livePlayer(kanal.id))
                    },
                )
            }
        } else if (refreshing && channels.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BytifyColors.GoldDeep)
                }
            }
        } else if (visibleChannels.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
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
            }
        } else {
            items(visibleChannels, key = { it.id }) { channel ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
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
/** Jeda sebelum ketikan dipakai menyaring; cukup untuk satu kata, tak terasa lambat. */
private const val JEDA_PENCARIAN_MS = 250L

private const val LIVE_STATUS_MAX_AGE_MS = 24 * 60 * 60 * 1000L
/** Kategori kanal sebagai daftar; sumbernya menggabungkannya dengan titik koma. */
private fun LiveChannel.kategoriList(): List<String> =
    category.split(';').map(String::trim).filter(String::isNotEmpty)

/** Label kategori yang enak dibaca: "Animation;Comedy" -> "Animation · Comedy". */
private fun LiveChannel.kategoriRapi(): String = kategoriList().joinToString(" · ")

private fun LiveChannel.isFresh(nowEpochMs: Long): Boolean =
    checkedAtEpochMs > 0L && nowEpochMs >= checkedAtEpochMs && nowEpochMs - checkedAtEpochMs <= LIVE_STATUS_MAX_AGE_MS
private fun LiveChannel.isCurrentlyAvailable(nowEpochMs: Long): Boolean = isAvailable && isFresh(nowEpochMs)

/** Jumlah kanal minimum agar sebuah kategori layak punya deretnya sendiri. */
private const val BATAS_DERET_KANAL = 8

/** Banyak kanal yang ditampilkan dalam satu deret mendatar. */
private const val ISI_DERET_KANAL = 20

private data class DeretKanal(
    val kategori: String,
    val kanal: List<LiveChannel>,
    val jumlah: Int,
)

/** Satu deret kanal mendatar dengan tombol "Lihat semua", sebentuk dengan Film. */
@Composable
private fun DeretKanalMendatar(
    deret: DeretKanal,
    onLihatSemua: () -> Unit,
    onBuka: (LiveChannel) -> Unit,
) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                deret.kategori,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onLihatSemua) {
                Text("Lihat semua")
                Text(
                    " (${deret.jumlah})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(deret.kanal, key = { deret.kategori + it.id }) { kanal ->
                Column(
                    Modifier.width(104.dp).clickable { onBuka(kanal) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(104.dp).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (kanal.logoUrl != null) {
                            AsyncImage(
                                model = kanal.logoUrl,
                                contentDescription = "Logo ${kanal.name}",
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.LiveTv,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    Text(
                        kanal.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        kanal.country,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
