package com.bytify.app.ui.components

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
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
    NavItem(Routes.LIBRARY, "Koleksi", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

/**
 * Navigasi bawah: pill cel melayang. Kapsul gold meluncur ke tab yang dipilih;
 * tepi depan bergerak lebih cepat dari tepi belakang sehingga kapsul memanjang
 * lalu menyusut seperti cairan.
 */
@Composable
fun FloatingNavBar(currentRoute: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val target = bounds[currentRoute]
    var previousLeft by remember { mutableFloatStateOf(target?.first ?: 0f) }
    val movingRight = (target?.first ?: 0f) >= previousLeft
    val lead = spring<Float>(dampingRatio = 0.72f, stiffness = 700f)
    val trail = spring<Float>(dampingRatio = 0.8f, stiffness = 240f)
    val left by animateFloatAsState(target?.first ?: 0f, if (movingRight) trail else lead, label = "navLeft")
    val right by animateFloatAsState(target?.second ?: 0f, if (movingRight) lead else trail, label = "navRight")
    LaunchedEffect(target?.first) { target?.first?.let { previousLeft = it } }

    // Geser jari di sepanjang navigasi: kapsul ikut jari, tab berpindah saat
    // jari melewati tab lain, dan kapsul kembali rapi saat dilepas.
    // pointerInput(Unit) hanya dibuat sekali; pakai nilai terbaru, bukan yang lama.
    val route by rememberUpdatedState(currentRoute)
    val select by rememberUpdatedState(onSelect)
    var dragX by remember { mutableStateOf<Float?>(null) }
    var rowWidth by remember { mutableFloatStateOf(0f) }
    fun nearest(x: Float): String? = navItems.minByOrNull { item ->
        bounds[item.route]?.let { (l, r) -> kotlin.math.abs((l + r) / 2 - x) } ?: Float.MAX_VALUE
    }?.route

    Box(
        modifier
            .padding(start = 24.dp, end = 28.dp)
            .cel(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .onSizeChanged { rowWidth = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = it.x },
                        onDragEnd = { dragX = null },
                        onDragCancel = { dragX = null },
                    ) { change, amount ->
                        change.consume()
                        val x = ((dragX ?: change.position.x) + amount).coerceIn(0f, rowWidth)
                        dragX = x
                        nearest(x)?.let { if (it != route) select(it) }
                    }
                },
        ) {
            if (target != null && right > left) {
                val width = right - left
                val dragLeft = dragX?.let { (it - width / 2).coerceIn(0f, (rowWidth - width).coerceAtLeast(0f)) }
                Box(
                    Modifier
                        .offset { IntOffset((dragLeft ?: left).roundToInt(), 0) }
                        .width(with(density) { (right - left).toDp() })
                        .height(46.dp)
                        .cel(BytifyColors.Gold, CircleShape, stroke = 1.5.dp, shadow = 0.dp)
                        .topShine(0.22f),
                )
            }
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navItems.forEach { item ->
                    val selected = currentRoute == item.route
                    val content by animateColorAsState(
                        if (selected) BytifyColors.Navy else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "navContent",
                    )
                    Row(
                        Modifier
                            .onGloballyPositioned { c ->
                                val x = c.positionInParent().x
                                bounds[item.route] = x to x + c.size.width
                            }
                            .clip(CircleShape)
                            .clickable { onSelect(item.route) }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (selected) item.selectedIcon else item.icon, item.label, tint = content)
                        AnimatedVisibility(selected, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = content,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
