package com.bytify.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import com.bytify.app.ui.components.VolumeSlider
import androidx.compose.material.icons.rounded.Share
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import com.bytify.app.ui.theme.cel
import com.bytify.app.ui.components.GoldPlayButton
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.bytify.app.data.model.Lyrics
import com.bytify.app.data.model.Song
import com.bytify.app.data.together.TogetherState
import com.bytify.app.playback.SleepTimer
import com.bytify.app.ui.SongMenuRequest
import com.bytify.app.ui.appState
import com.bytify.app.ui.components.Artwork
import com.bytify.app.ui.components.rememberDominantColor
import com.bytify.app.util.ShareCard
import com.bytify.app.util.formatMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class PlayerSheet { None, Queue, Sleep, Speed }

/** Player layar penuh: latar blur dari cover, kontrol, lirik tersinkron. */
@Composable
fun PlayerScreen(onCollapse: () -> Unit) {
    val app = appState()
    val state by app.player.state.collectAsState()
    val likedIds by app.container.library.likedIds.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    var showLyrics by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(PlayerSheet.None) }
    val song = state.song

    BackHandler(enabled = app.playerSheet.isOpen, onBack = onCollapse)

    // Latar player gelap: ikon status bar terang hanya selama player terbuka.
    val view = LocalView.current
    val open = app.playerSheet.isOpen
    DisposableEffect(open) {
        if (!open) return@DisposableEffect onDispose { }
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }

    if (song == null) {
        LaunchedEffect(Unit) { onCollapse() }
        return
    }

    val dominant = rememberDominantColor(song.thumbnailUrl, MaterialTheme.colorScheme.surfaceContainerHigh)
    val tint by animateColorAsState(dominant, label = "playerTint")
    val liked = song.id in likedIds
    val lyrics by produceState<LyricsState>(LyricsState.Loading, song.id) {
        value = LyricsState.Loading
        value = if (!song.isMusic) LyricsState.Missing else runCatching { app.container.lyrics.lyricsFor(song) }
            .fold({ if (it == null) LyricsState.Missing else LyricsState.Ready(it) }, { LyricsState.Missing })
    }
    val translateOn by app.container.settings.settings
        .map { it.translateLyrics }
        .collectAsState(initial = false)
    val translations by produceState<List<String?>?>(null, song.id, lyrics, translateOn) {
        val ready = lyrics as? LyricsState.Ready
        value = if (!translateOn || ready == null) null else runCatching {
            app.container.translator.translate(song.id, ready.lyrics.lines.map { it.text })
        }.getOrNull()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)

    ) {
        // Latar: cover diburamkan (Android 12+) + gradasi warna dominan.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Artwork(
                song.thumbnailUrl,
                Modifier.fillMaxSize().blur(90.dp).alpha(0.55f),
                RoundedCornerShape(0.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.75f), MaterialTheme.colorScheme.background.copy(alpha = 0.92f), MaterialTheme.colorScheme.background),
                ),
            ),
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Tutup player", Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val room by app.container.together.state.collectAsState()
                    val roomCode = (room as? TogetherState.InRoom)?.code
                    Text(
                        if (roomCode != null) "DENGAR BARENG • $roomCode" else "SEDANG DIPUTAR",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (roomCode != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${state.currentIndex + 1} dari ${state.queue.size}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                IconButton(onClick = { app.showMenu(SongMenuRequest(song)) }) { Icon(Icons.Rounded.MoreVert, "Opsi lagu") }
            }

            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                AnimatedContent(showLyrics, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lyricsSwap") { lyricsMode ->
                    if (lyricsMode) {
                        LyricsView(song, lyrics, translations, onSeek = app.player::seekTo)
                    } else {
                        SwipeableCover(song, onNext = app.player::next, onPrevious = app.player::previous)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1_800, velocity = 40.dp),
                    )
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { scope.launch { app.container.library.toggleLike(song, liked) } }) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (liked) "Hapus dari favorit" else "Sukai",
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }

            if (song.isLive) LiveBadge() else SeekBar(state.durationMs, onSeek = app.player::seekTo)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleIcon(Icons.Rounded.Shuffle, "Acak", state.shuffle) { app.player.toggleShuffle() }
                IconButton(onClick = { app.player.previous() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Sebelumnya", Modifier.size(40.dp))
                }
                GoldPlayButton(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onClick = { app.player.togglePlay() },
                    size = 76.dp,
                    shadow = 5.dp,
                )
                IconButton(onClick = { app.player.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Berikutnya", Modifier.size(40.dp))
                }
                ToggleIcon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Ulangi",
                    state.repeatMode != Player.REPEAT_MODE_OFF,
                ) { app.player.cycleRepeat() }
            }

            VolumeSlider(Modifier.padding(top = 4.dp))

            val sleep by app.player.sleepTimer.collectAsState()
            val context = LocalContext.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 10.dp, end = 4.dp)
                    .cel(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f), RoundedCornerShape(18.dp), stroke = 1.5.dp, shadow = 3.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (song.isMusic) ActionItem(Icons.Rounded.Lyrics, "LIRIK", showLyrics) { showLyrics = !showLyrics }
                ActionItem(Icons.AutoMirrored.Rounded.QueueMusic, "ANTREAN", false) { sheet = PlayerSheet.Queue }
                ActionItem(Icons.Rounded.Share, "BAGIKAN", false) {
                    app.container.appScope.launch { ShareCard.share(context, song) }
                }
                ActionItem(Icons.Rounded.Speed, "TEMPO", state.speed != 1f || state.pitch != 1f) { sheet = PlayerSheet.Speed }
                ActionItem(Icons.Rounded.Bedtime, "TIMER", sleep != SleepTimer.Off) { sheet = PlayerSheet.Sleep }
            }
        }
    }

    when (sheet) {
        PlayerSheet.Queue -> QueueSheet { sheet = PlayerSheet.None }
        PlayerSheet.Sleep -> SleepSheet { sheet = PlayerSheet.None }
        PlayerSheet.Speed -> SpeedSheet(state.speed, state.pitch) { sheet = PlayerSheet.None }
        PlayerSheet.None -> Unit
    }
}

/** Pengganti slider untuk siaran radio: tidak ada durasi, selalu siaran langsung. */
@Composable
private fun LiveBadge() {
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "liveDot")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .cel(Color(0xFFB4473A), RoundedCornerShape(50), stroke = 1.5.dp, shadow = 2.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(Color.White))
            Spacer(Modifier.width(8.dp))
            Text("SIARAN LANGSUNG", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

@Composable
private fun SwipeableCover(song: Song, onNext: () -> Unit, onPrevious: () -> Unit) {
    var drag by remember(song.id) { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(drag, label = "coverDrag")
    Artwork(
        song.thumbnailUrl,
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(top = 8.dp, bottom = 14.dp, end = 6.dp)
            .graphicsOffset(offset)
            .cel(shape = RoundedCornerShape(24.dp), stroke = 2.5.dp, shadow = 6.dp)
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(drag) > 160) { if (drag < 0) onNext() else onPrevious() }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, amount -> drag += amount }
            },
        RoundedCornerShape(24.dp),
    )
}

private fun Modifier.graphicsOffset(x: Float) = this.then(
    Modifier.graphicsLayer { translationX = x; alpha = 1f - (abs(x) / 900f).coerceAtMost(0.5f) },
)

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object Missing : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
}

@Composable
private fun LyricsView(
    song: Song,
    state: LyricsState,
    translations: List<String?>?,
    onSeek: (Long) -> Unit,
) {
    when (state) {
        LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LyricsState.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Lirik tidak ditemukan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is LyricsState.Ready -> {
            val lines = state.lyrics.lines
            // Posisi berubah 4x/detik; derivedStateOf memastikan daftar lirik
            // hanya digambar ulang saat baris aktif benar-benar berganti.
            val position by appState().player.positionMs.collectAsState()
            val activeIndex by remember(lines, state.lyrics.synced) {
                derivedStateOf {
                    if (state.lyrics.synced) lines.indexOfLast { (it.timeMs ?: 0) <= position + 250 } else -1
                }
            }
            val listState = rememberLazyListState()
            // Mode pilih baris untuk dibagikan sebagai gambar (tekan lama untuk mulai).
            var selected by remember(song.id) { mutableStateOf(emptySet<Int>()) }
            val selecting = selected.isNotEmpty()
            val context = LocalContext.current
            val appScope = appState().container.appScope
            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0 && !selecting) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
            }
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 32.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    val active = index == activeIndex
                    val passed = state.lyrics.synced && index < activeIndex
                    val alpha by animateFloatAsState(
                        when {
                            !state.lyrics.synced -> 0.9f
                            active -> 1f
                            passed -> 0.35f
                            else -> 0.5f
                        },
                        label = "lyricAlpha",
                    )
                    val isSelected = index in selected
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (selecting) (if (isSelected) 1f else 0.4f) else alpha)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    when {
                                        selecting && line.text.isNotBlank() ->
                                            selected = if (isSelected) selected - index else (selected + index).take(MAX_SHARE_LINES).toSet()
                                        !selecting -> line.timeMs?.let(onSeek)
                                    }
                                },
                                onLongClick = { if (line.text.isNotBlank()) selected = selected + index },
                            )
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                    ) {
                        Text(
                            line.text.ifBlank { "♪" },
                            fontSize = if (state.lyrics.synced) 24.sp else 19.sp,
                            lineHeight = if (state.lyrics.synced) 32.sp else 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
                            color = if (active) MaterialTheme.colorScheme.primary else Color.White,
                        )
                        translations?.getOrNull(index)?.let { translated ->
                            Text(
                                translated,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                item {
                    Text(
                        if (translations != null) "Lirik dari ${state.lyrics.source} • Terjemahan Google" else "Lirik dari ${state.lyrics.source}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    Text(
                        "Tekan lama baris lirik untuk membagikannya sebagai gambar.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (selecting) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { selected = emptySet() }) { Text("Batal") }
                    Text("${selected.size} baris", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        val chosen = selected.sorted().map { lines[it].text }
                        selected = emptySet()
                        appScope.launch { ShareCard.share(context, song, chosen) }
                    }) { Text("Bagikan") }
                }
            }
            }
        }
    }
}

private const val MAX_SHARE_LINES = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(durationMs: Long, onSeek: (Long) -> Unit) {
    val positionMs by appState().player.positionMs.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = 12.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
            ),
            thumb = {
                Box(Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(6.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                    ),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
        Row {
            Text(formatMs((fraction * durationMs).toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                "-" + formatMs((durationMs - (fraction * durationMs).toLong()).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tombol bilah aksi bawah: ikon + label kecil, menyala gold saat aktif. */
@Composable
private fun ActionItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Box(
            Modifier.size(4.dp).clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val app = appState()
    val state by app.player.state.collectAsState()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex.coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text("Antrean", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(state.queue, key = { i, s -> "$i-${s.id}" }) { index, song ->
                val current = index == state.currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { app.player.skipTo(index) }
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.thumbnailUrl, Modifier.size(48.dp), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (!current) {
                        IconButton(onClick = { app.player.removeFromQueue(index) }) {
                            Icon(Icons.Rounded.Close, "Hapus dari antrean", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(onDismiss: () -> Unit) {
    val app = appState()
    val timer by app.player.sleepTimer.collectAsState()
    val remaining by produceState(0L, timer) {
        while (true) {
            value = (timer as? SleepTimer.At)?.let { (it.endsAtMs - System.currentTimeMillis()).coerceAtLeast(0) } ?: 0
            delay(1_000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
            Text(
                when (timer) {
                    SleepTimer.Off -> "Musik berhenti otomatis setelah waktu yang dipilih."
                    SleepTimer.EndOfSong -> "Berhenti di akhir lagu ini."
                    is SleepTimer.At -> "Berhenti dalam ${formatMs(remaining)}."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            listOf(5, 10, 15, 30, 45, 60, 90).chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { minutes ->
                        TextButton(
                            onClick = { app.player.setSleepTimer(minutes); onDismiss() },
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) { Text("$minutes mnt") }
                    }
                }
            }
            TextButton(onClick = { app.player.sleepAtEndOfSong(); onDismiss() }) { Text("Akhir lagu ini") }
            if (timer != SleepTimer.Off) {
                TextButton(onClick = { app.player.setSleepTimer(null); onDismiss() }) {
                    Text("Matikan timer", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(speed: Float, pitch: Float, onDismiss: () -> Unit) {
    val app = appState()
    var s by remember { mutableFloatStateOf(speed) }
    var p by remember { mutableFloatStateOf(pitch) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text("Tempo & nada", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("Kecepatan: ${"%.2f".format(s)}x", style = MaterialTheme.typography.titleSmall)
            Slider(value = s, onValueChange = { s = it }, valueRange = 0.5f..2f, steps = 29,
                onValueChangeFinished = { app.player.setSpeedAndPitch(s, p) })
            Text("Nada: ${"%.2f".format(p)}x", style = MaterialTheme.typography.titleSmall)
            Slider(value = p, onValueChange = { p = it }, valueRange = 0.5f..2f, steps = 29,
                onValueChangeFinished = { app.player.setSpeedAndPitch(s, p) })
            TextButton(onClick = { s = 1f; p = 1f; app.player.setSpeedAndPitch(1f, 1f) }) { Text("Kembali normal") }
            Text(
                "Tips: kecepatan 0.8x + nada 0.9x untuk efek slowed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}
