package com.bytify.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bytify.app.BytifyApplication
import com.bytify.app.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alarm berbunyi: minta service pemutar memulai musik alarm. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1)
        if (id < 0) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_ALARM)
                .putExtra(EXTRA_ALARM_ID, id),
        )
        val pending = goAsync()
        val container = (context.applicationContext as BytifyApplication).container
        container.appScope.launch(Dispatchers.IO) {
            try {
                container.alarms.onFired(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}

/** Alarm terhapus saat HP restart / jam diubah, jadi jadwalkan ulang. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as BytifyApplication).container
        container.appScope.launch(Dispatchers.IO) {
            try {
                container.alarms.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
