package com.bytify.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.bytify.app.data.prefs.Settings
import com.bytify.app.ui.BytifyApp
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
            BytifyTheme(pureBlack = settings.pureBlack) {
                BytifyApp(container)
            }
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
