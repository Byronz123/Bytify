package com.bytify.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bytify.app.BytifyApplication
import com.bytify.app.MainActivity
import com.bytify.app.R
import com.bytify.app.playback.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Widget layar utama: lagu yang sedang diputar + tombol kontrol. */
class BytifyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as BytifyApplication).container
        provideContent {
            val now by container.nowPlaying.collectAsState()
            WidgetContent(now)
        }
    }

    companion object {
        suspend fun refresh(context: Context) = runCatching { BytifyWidget().updateAll(context) }
    }
}

private val Background = Color(0xFF1F2A44)
private val White = ColorProvider(Color.White)
private val Muted = ColorProvider(Color(0xFFE8DCC8))

@Composable
private fun WidgetContent(now: NowPlaying) {
    val song = now.song
    Row(
        GlanceModifier
            .fillMaxSize()
            .background(Background)
            .cornerRadius(24.dp)
            .padding(10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val art = now.artwork
        Box(GlanceModifier.size(48.dp).cornerRadius(12.dp).background(Color(0xFF2E3B5C)), contentAlignment = Alignment.Center) {
            if (art != null) {
                Image(ImageProvider(art), null, GlanceModifier.size(48.dp).cornerRadius(12.dp), contentScale = ContentScale.Crop)
            } else {
                Image(ImageProvider(R.drawable.ic_w_note), null, GlanceModifier.size(24.dp))
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                song?.title ?: "Bytify",
                maxLines = 1,
                style = TextStyle(color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                song?.artist ?: "Ketuk untuk mulai mendengarkan",
                maxLines = 1,
                style = TextStyle(color = Muted, fontSize = 12.sp),
            )
        }
        if (song != null) {
            Image(
                ImageProvider(R.drawable.ic_w_prev),
                "Sebelumnya",
                GlanceModifier.size(36.dp).padding(6.dp).clickable(actionRunCallback<PreviousAction>()),
            )
            Box(
                GlanceModifier.size(40.dp).cornerRadius(20.dp).background(Color(0xFFC6A75E))
                    .clickable(actionRunCallback<TogglePlayAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(if (now.isPlaying) R.drawable.ic_w_pause else R.drawable.ic_w_play),
                    if (now.isPlaying) "Jeda" else "Putar",
                    GlanceModifier.size(24.dp),
                )
            }
            Image(
                ImageProvider(R.drawable.ic_w_next),
                "Berikutnya",
                GlanceModifier.size(36.dp).padding(6.dp).clickable(actionRunCallback<NextAction>()),
            )
        }
    }
}

/** Jalankan perintah ke pemutar; jika antrean kosong, buka aplikasi. */
private suspend fun withPlayer(context: Context, block: (com.bytify.app.playback.PlayerConnection) -> Unit) {
    val container = (context.applicationContext as BytifyApplication).container
    withContext(Dispatchers.Main) {
        container.player.connect()
        if (container.player.state.value.song == null) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            block(container.player)
        }
    }
}

class TogglePlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withPlayer(context) { it.togglePlay() }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withPlayer(context) { it.next() }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withPlayer(context) { it.previous() }
}

class BytifyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BytifyWidget()
}
