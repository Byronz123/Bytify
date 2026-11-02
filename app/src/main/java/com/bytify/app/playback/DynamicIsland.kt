package com.bytify.app.playback

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bytify.app.MainActivity
import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.ui.theme.JakartaSans
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Kontrol yang bisa dipanggil dari pill (dijalankan oleh service pemutar). */
interface IslandControls {
    fun togglePlay()
    fun next()
    fun previous()
}

/**
 * "Dynamic Island" Bytify: pill melayang di atas aplikasi lain selama musik
 * diputar. Butuh izin "Tampilkan di atas aplikasi lain".
 */
class DynamicIsland(
    private val context: Context,
    private val state: StateFlow<NowPlaying>,
    private val controls: IslandControls,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private val density = context.resources.displayMetrics.density

    val isShowing get() = view != null

    fun show() {
        if (view != null || !canDraw(context)) return
        val overlayOwner = OverlayOwner().also { it.start() }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            setContent { IslandPill(state, controls, onOpenApp = ::openApp, onExpandedChange = ::resize) }
        }
        val params = WindowManager.LayoutParams(
            px(COLLAPSED_W),
            px(COLLAPSED_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * context.resources.displayMetrics.density).toInt()
        }
        runCatching { windowManager.addView(composeView, params) }.onSuccess {
            view = composeView
            owner = overlayOwner
            this.params = params
        }.onFailure { overlayOwner.stop() }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        owner?.stop()
        view = null
        owner = null
        params = null
    }

    /** Ukuran jendela mengikuti pill; jendela overlay tidak membesar sendiri. */
    private fun resize(expanded: Boolean) {
        val v = view ?: return
        val p = params ?: return
        p.width = px(if (expanded) EXPANDED_W else COLLAPSED_W)
        p.height = px(if (expanded) EXPANDED_H else COLLAPSED_H)
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    private fun px(dp: Int) = (dp * density).toInt()

    private fun openApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    companion object {
        const val COLLAPSED_W = 150
        const val COLLAPSED_H = 38
        const val EXPANDED_W = 340
        const val EXPANDED_H = 76

        fun canDraw(context: Context) = Settings.canDrawOverlays(context)
    }
}

@Composable
private fun IslandPill(
    state: StateFlow<NowPlaying>,
    controls: IslandControls,
    onOpenApp: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    val now by state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val song = now.song ?: return

    // Jendela melebar sebelum animasi membuka, menciut setelah animasi menutup;
    // pill kembali ringkas otomatis setelah beberapa detik.
    LaunchedEffect(expanded, song.id) {
        if (expanded) {
            onExpandedChange(true)
            delay(5_000)
            expanded = false
        } else {
            delay(250)
            onExpandedChange(false)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            expanded,
            transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
            label = "island",
        ) { open ->
            if (!open) {
                Row(
                    Modifier
                        .size(DynamicIsland.COLLAPSED_W.dp, DynamicIsland.COLLAPSED_H.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black)
                        .clickable { expanded = true }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(now, 28)
                    Spacer(Modifier.weight(1f))
                    EqualizerBars(now.isPlaying)
                    Spacer(Modifier.width(8.dp))
                }
            } else {
                Row(
                    Modifier
                        .size(DynamicIsland.EXPANDED_W.dp, DynamicIsland.EXPANDED_H.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.Black)
                        .clickable { expanded = false }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.clickable(onClick = onOpenApp)) { Cover(now, 52, rounded = true) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = Color.White,
                            fontFamily = JakartaSans,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.artist,
                            color = Color.White.copy(alpha = 0.65f),
                            fontFamily = JakartaSans,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = controls::previous) {
                        Icon(Icons.Rounded.SkipPrevious, "Sebelumnya", tint = Color.White)
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(BytifyColors.Green)
                            .clickable(onClick = controls::togglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (now.isPlaying) "Jeda" else "Putar",
                            tint = BytifyColors.OnGreen,
                        )
                    }
                    IconButton(onClick = controls::next) {
                        Icon(Icons.Rounded.SkipNext, "Berikutnya", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(now: NowPlaying, sizeDp: Int, rounded: Boolean = false) {
    val shape = if (rounded) RoundedCornerShape(12.dp) else CircleShape
    val art = now.artwork
    if (art != null) {
        Image(
            art.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp.dp).clip(shape),
        )
    } else {
        Box(
            Modifier.size(sizeDp.dp).clip(shape).background(Color(0xFF1F2621)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MusicNote, null, tint = BytifyColors.Green, modifier = Modifier.size((sizeDp / 2).dp)) }
    }
}

/** Empat bar hijau bergerak — motif equalizer ikon Bytify. */
@Composable
private fun EqualizerBars(playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = listOf(0.45f, 0.9f, 0.65f, 0.35f).mapIndexed { i, base ->
        val animated by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(380 + i * 110), RepeatMode.Reverse),
            label = "bar$i",
        )
        if (playing) animated else base * 0.5f
    }
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(
                Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp)).background(BytifyColors.Green),
            )
        }
    }
}

/** Lifecycle minimal agar ComposeView bisa hidup di jendela overlay. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
