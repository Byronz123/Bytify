package com.bytify.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytify.app.ui.Routes
import com.bytify.app.ui.appState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Mini player melayang berbentuk kartu dengan warna dari cover. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val app = appState()
    val state by app.player.state.collectAsState()
    val likedIds by app.container.library.likedIds.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val song = state.song ?: return
    val tint = rememberDominantColor(song.thumbnailUrl, MaterialTheme.colorScheme.surfaceContainerHigh)
    val background by animateColorAsState(
        tint.copy(alpha = 1f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh, 0.35f),
        label = "miniBg",
    )
    val liked = song.id in likedIds

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .clickable { app.playerExpanded = true },
        color = background,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column {
            Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.thumbnailUrl, Modifier.size(48.dp), RoundedCornerShape(12.dp))
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
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { app.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.isPlaying) "Jeda" else "Putar",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                IconButton(onClick = { app.player.next() }) {
                    Icon(Icons.Rounded.SkipNext, "Berikutnya")
                }
            }
            MiniProgress(state.durationMs)
            Spacer(Modifier.height(6.dp))
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.White.copy(alpha = 0.12f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

private fun Color.compositeOver(base: Color, alpha: Float): Color = Color(
    red = red * alpha + base.red * (1 - alpha),
    green = green * alpha + base.green * (1 - alpha),
    blue = blue * alpha + base.blue * (1 - alpha),
    alpha = 1f,
)

private data class NavItem(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val navItems = listOf(
    NavItem(Routes.HOME, "Beranda", Icons.Outlined.Home, Icons.Rounded.Home),
    NavItem(Routes.SEARCH, "Cari", Icons.Rounded.Search, Icons.Rounded.Search),
    NavItem(Routes.LIBRARY, "Koleksi", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

/** Navigasi bawah berbentuk pill melayang; tab aktif melebar dengan label. */
@Composable
fun FloatingNavBar(currentRoute: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                val bg by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    label = "navBg",
                )
                val iconAlpha by animateFloatAsState(if (selected) 1f else 0.7f, label = "navAlpha")
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(bg)
                        .clickable { onSelect(item.route) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) item.selectedIcon else item.icon,
                        item.label,
                        tint = (if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            .copy(alpha = iconAlpha),
                    )
                    AnimatedVisibility(selected, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
