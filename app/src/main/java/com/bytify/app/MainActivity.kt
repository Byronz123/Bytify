package com.bytify.app

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.bytify.app.data.prefs.Settings
import com.bytify.app.ui.BytifyApp
import com.bytify.app.ui.ModeFloating
import com.bytify.app.ui.theme.BytifyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as BytifyApplication).container

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch { container.player.connect() }

        // Android 12+ bisa masuk mode floating SENDIRI saat pengguna menggeser
        // ke beranda. Itu penting: dengan navigasi gestur, onUserLeaveHint()
        // tidak selalu terpanggil, sehingga tanpa ini menonton sambil keluar
        // aplikasi hanya bekerja pada tombol home klasik.
        lifecycleScope.launch {
            ModeFloating.adaVideo.collect { perbaruiParamsFloating() }
        }
        // Ikon tombolnya ikut berubah saat pemutaran dijeda atau dilanjutkan.
        lifecycleScope.launch {
            ModeFloating.sedangMain.collect { perbaruiParamsFloating() }
        }
        ContextCompat.registerReceiver(
            this,
            penerimaAksiFloating,
            IntentFilter(AKSI_TUKAR_PUTAR),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Cek pembaruan otomatis paling sering sekali sehari.
        lifecycleScope.launch {
            val s = container.settings.settings.first()
            if (s.autoCheckUpdates && System.currentTimeMillis() - s.lastUpdateCheck > UPDATE_INTERVAL_MS) {
                container.updates.check()
                container.settings.markUpdateChecked()
            }
        }

        setContent {
            val settings by container.settings.settings.collectAsState(initial = Settings())
            // Ikon status bar gelap di mode terang (kertas beige), terang di mode malam.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !settings.darkTheme
            }
            BytifyTheme(darkTheme = settings.darkTheme) {
                BytifyApp(container)
            }
        }
    }

    /**
     * Tombol di jendela floating sampai ke sini. Jendela floating tidak
     * meneruskan sentuhan ke isi aplikasi, jadi inilah satu-satunya jalan
     * menjeda dan melanjutkan tanpa harus membuka aplikasi lebih dulu.
     */
    private val penerimaAksiFloating = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AKSI_TUKAR_PUTAR) ModeFloating.kendali?.invoke()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(penerimaAksiFloating) }
        super.onDestroy()
    }

    /**
     * Dipanggil sistem tepat sebelum pengguna meninggalkan aplikasi lewat
     * tombol home. Hanya masuk mode floating kalau memang ada video di layar.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        masukModeFloating()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ModeFloating.setAktif(isInPictureInPictureMode)
    }

    private fun dukungFloating(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun masukModeFloating() {
        if (!dukungFloating() || !ModeFloating.adaVideo.value || isInPictureInPictureMode) return
        // Dibungkus runCatching: sistem menolak permintaan ini kalau jendela
        // sedang tidak terlihat, dan penolakan itu tidak boleh mematikan aplikasi.
        runCatching { enterPictureInPictureMode(bangunParamsFloating()) }
    }

    private fun perbaruiParamsFloating() {
        if (!dukungFloating()) return
        runCatching { setPictureInPictureParams(bangunParamsFloating()) }
    }

    private fun aksiTukarPutar(): RemoteAction? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val main = ModeFloating.sedangMain.value
        val judul = if (main) "Jeda" else "Putar"
        val ikon = Icon.createWithResource(
            this,
            if (main) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        val intent = PendingIntent.getBroadcast(
            this,
            if (main) 1 else 2,
            Intent(AKSI_TUKAR_PUTAR).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(ikon, judul, judul, intent)
    }

    private fun bangunParamsFloating(): PictureInPictureParams {
        val pembangun = PictureInPictureParams.Builder().setAspectRatio(ModeFloating.rasio)
        aksiTukarPutar()?.let { pembangun.setActions(listOf(it)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pembangun.setAutoEnterEnabled(ModeFloating.adaVideo.value)
            // Perubahan ukuran jendela oleh pengguna digambar mulus, bukan
            // dengan memuat ulang permukaan video.
            pembangun.setSeamlessResizeEnabled(true)
        }
        return pembangun.build()
    }

    companion object {
        private const val AKSI_TUKAR_PUTAR = "com.bytify.app.FLOATING_TUKAR_PUTAR"
        private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
