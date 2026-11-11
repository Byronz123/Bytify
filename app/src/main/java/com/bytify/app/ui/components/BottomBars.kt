package com.bytify.app.ui.components

import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import kotlin.math.abs
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.ui.theme.cel
import com.bytify.app.ui.theme.topShine
import kotlinx.coroutines.launch

/** Tombol putar khas Bytify: lingkaran gold bergaris tebal dengan kilau. */
@Composable
fun GoldPlayButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    shadow: Dp = 3.dp,
) {
    Box(
        Modifier
            .size(size)
            .cel(BytifyColors.Gold, CircleShape, stroke = 2.dp, shadow = shadow)
            .topShine()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(Modifier.size(size * 0.45f), color = BytifyColors.Navy, strokeWidth = 2.5.dp)
        } else {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isPlaying) "Jeda" else "Putar",
                tint = BytifyColors.Navy,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/** Mini player: kartu cel melayang, sedikit diwarnai dari cover. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val app = appState()
    val state by app.player.state.collectAsState()
    val likedIds by app.container.library.likedIds.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val song = state.song ?: return
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val tint = rememberDominantColor(song.thumbnailUrl, surface)
    val fill by animateColorAsState(lerp(surface, tint, 0.18f), label = "miniFill")
    val liked = song.id in likedIds

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 16.dp)
            .cel(fill, RoundedCornerShape(20.dp))
            .clickable { app.playerSheet.expand() },
    ) {
        Column {
            Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(
                    song.thumbnailUrl,
                    Modifier.size(48.dp).cel(shape = RoundedCornerShape(12.dp), stroke = 1.5.dp, shadow = 0.dp),
                    RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.error ?: song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { scope.launch { app.container.library.toggleLike(song, liked) } }) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (liked) "Hapus dari favorit" else "Sukai",
                        tint = if (liked) BytifyColors.Gold else MaterialTheme.colorScheme.onSurface,
                    )
                }
                GoldPlayButton(state.isPlaying, state.isBuffering, onClick = { app.player.togglePlay() }, shadow = 2.dp)
                IconButton(onClick = { app.player.next() }) {
                    Icon(Icons.Rounded.SkipNext, "Berikutnya")
                }
            }
            MiniProgress(state.durationMs)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Hanya bagian ini yang digambar ulang tiap kali posisi lagu berubah. */
@Composable
private fun MiniProgress(durationMs: Long) {
    val position by appState().player.positionMs.collectAsState()
    val progress = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp).clip(CircleShape),
        color = BytifyColors.Gold,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val navItems = listOf(
    NavItem(Routes.HOME, "Beranda", Icons.Outlined.Home, Icons.Rounded.Home),
    NavItem(Routes.SEARCH, "Cari", Icons.Rounded.Search, Icons.Rounded.Search),
    NavItem(Routes.EXPLORE, "Jelajah", Icons.Outlined.Explore, Icons.Rounded.Explore),
    NavItem(Routes.LIBRARY, "Koleksi", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

/**
 * Navigasi bawah bergaya cairan.
 *
 * Posisi kapsul disimpan sebagai **indeks pecahan** (0f..n-1f) di sebuah
 * [Animatable]. Satu angka itu menjadi sumber kebenaran tunggal untuk semuanya:
 * letak kapsul, lebarnya, pudar-timbulnya label, dan bentuk ikon. Karena itu
 * tidak ada lagi dua animasi yang saling mengejar.
 *
 * - Selama jari menekan dan menggeser, indeks mengikuti jari secara kontinu
 *   (interpolasi antar item), bukan melompat ke item terdekat.
 * - Perpindahan halaman baru dijalankan saat jari DILEPAS. Bila jari kembali ke
 *   item asal lalu dilepas, kapsul memantul balik dan halaman tidak berganti.
 * - Pendaratan memakai spring, bukan tween, supaya terasa memantul.
 */
@Composable
fun FloatingNavBar(currentRoute: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }

    val indeksRute = navItems.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val posisi = remember { Animatable(indeksRute.toFloat()) }
    var menggeser by remember { mutableStateOf(false) }
    val pilih by rememberUpdatedState(onSelect)
    // WAJIB: pointerInput membuat lambdanya sekali saja, jadi tanpa ini
    // currentRoute yang tertangkap di dalamnya membeku pada nilai komposisi
    // pertama — kapsul berpindah tetapi halaman tidak ikut berganti.
    val ruteKini by rememberUpdatedState(currentRoute)

    /** Pendaratan: cukup kenyal untuk terasa hidup, cukup teredam agar tidak liar. */
    val pendaratan = spring<Float>(dampingRatio = 0.68f, stiffness = 420f)

    // Rute bisa berubah dari luar (tombol kembali, tautan): ikut dianimasikan,
    // tapi jangan ganggu saat jari sedang menggeser.
    LaunchedEffect(indeksRute) {
        if (!menggeser && posisi.value != indeksRute.toFloat()) posisi.animateTo(indeksRute.toFloat(), pendaratan)
    }

    /** Ubah posisi x jari menjadi indeks pecahan dengan menginterpolasi antar pusat item. */
    fun indeksDari(x: Float): Float {
        val pusat = navItems.indices.mapNotNull { i -> bounds[i]?.let { (l, r) -> i to (l + r) / 2f } }
        if (pusat.isEmpty()) return posisi.value
        if (x <= pusat.first().second) return pusat.first().first.toFloat()
        if (x >= pusat.last().second) return pusat.last().first.toFloat()
        for (k in 0 until pusat.size - 1) {
            val (i1, c1) = pusat[k]
            val (i2, c2) = pusat[k + 1]
            if (x in c1..c2) {
                val t = if (c2 == c1) 0f else (x - c1) / (c2 - c1)
                return i1 + t * (i2 - i1)
            }
        }
        return posisi.value
    }

    /** Dilepas: daratkan ke item terdekat, lalu pindah halaman bila memang berubah. */
    fun daratkan() {
        menggeser = false
        val tujuan = posisi.value.roundToInt().coerceIn(0, navItems.lastIndex)
        scope.launch { posisi.animateTo(tujuan.toFloat(), pendaratan) }
        val rute = navItems[tujuan].route
        if (rute != ruteKini) pilih(rute)
    }

    Box(
        modifier
            .padding(start = 24.dp, end = 28.dp)
            .cel(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                // Ketukan biasa: langsung daratkan ke item yang disentuh.
                .pointerInput(navItems) {
                    detectTapGestures { offset ->
                        val tujuan = indeksDari(offset.x).roundToInt().coerceIn(0, navItems.lastIndex)
                        scope.launch { posisi.animateTo(tujuan.toFloat(), pendaratan) }
                        navItems[tujuan].route.takeIf { it != ruteKini }?.let(pilih)
                    }
                }
                // Geseran: kapsul mengikuti jari terus-menerus; halaman baru
                // berpindah saat jari diangkat.
                .pointerInput(navItems) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            menggeser = true
                            scope.launch { posisi.snapTo(indeksDari(offset.x)) }
                        },
                        onDragEnd = { daratkan() },
                        onDragCancel = { daratkan() },
                    ) { change, _ ->
                        change.consume()
                        scope.launch { posisi.snapTo(indeksDari(change.position.x)) }
                    }
                },
        ) {
            // Kapsul: tepi kiri dan kanan diinterpolasi dari batas dua item yang
            // diapit indeks pecahan, jadi lebarnya ikut menyesuaikan panjang
            // label item tujuan tanpa animasi terpisah.
            val frac = posisi.value
            val i1 = frac.toInt().coerceIn(0, navItems.lastIndex)
            val i2 = (i1 + 1).coerceAtMost(navItems.lastIndex)
            val t = frac - i1
            val b1 = bounds[i1]
            val b2 = bounds[i2]
            if (b1 != null && b2 != null) {
                val kiri = b1.first + (b2.first - b1.first) * t
                val kanan = b1.second + (b2.second - b1.second) * t
                if (kanan > kiri) {
                    Box(
                        Modifier
                            .offset { IntOffset(kiri.roundToInt(), 0) }
                            .width(with(density) { (kanan - kiri).toDp() })
                            .height(46.dp)
                            .cel(BytifyColors.Gold, CircleShape, stroke = 1.5.dp, shadow = 0.dp)
                            .topShine(0.22f),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navItems.forEachIndexed { index, item ->
                    // 1 saat kapsul tepat di item ini, 0 saat sudah di tetangganya.
                    // Dipakai bersama untuk pudar label, lebar label, warna, dan
                    // bentuk ikon — semuanya bergerak serempak.
                    val porsi = (1f - abs(posisi.value - index)).coerceIn(0f, 1f)
                    val warna = lerp(MaterialTheme.colorScheme.onSurfaceVariant, BytifyColors.Navy, porsi)
                    Row(
                        Modifier
                            .onGloballyPositioned { c ->
                                val x = c.positionInParent().x
                                bounds[index] = x to x + c.size.width
                            }
                            .clip(CircleShape)
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Ikon terisi penuh hanya setelah kapsul benar-benar mendarat.
                        Icon(if (porsi > 0.5f) item.selectedIcon else item.icon, item.label, tint = warna)
                        // Label memudar sekaligus menyempit mengikuti [porsi]: di
                        // tengah perjalanan, label asal dan tujuan sama-sama samar.
                        if (porsi > 0.01f) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = warna,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .alpha(porsi)
                                    .layout { measurable, constraints ->
                                        val p = measurable.measure(constraints)
                                        val lebar = (p.width * porsi).roundToInt()
                                        layout(lebar, p.height) { p.place(0, 0) }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
