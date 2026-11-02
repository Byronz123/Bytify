package com.bytify.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bytify.app.MainActivity
import com.bytify.app.data.db.AlarmEntity
import com.bytify.app.data.db.BytifyDao
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/** Penyimpanan & penjadwalan alarm musik lewat AlarmManager. */
class AlarmRepository(private val context: Context, private val dao: BytifyDao) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    val alarms: Flow<List<AlarmEntity>> = dao.alarms()

    suspend fun alarm(id: Long) = dao.alarm(id)

    /** @return waktu alarm berikutnya (epoch ms), atau null jika nonaktif. */
    suspend fun save(alarm: AlarmEntity): Long? {
        val id = dao.upsertAlarm(alarm).takeIf { it > 0 } ?: alarm.id
        val saved = alarm.copy(id = id)
        return if (saved.enabled) schedule(saved) else null.also { cancel(saved.id) }
    }

    suspend fun setEnabled(alarm: AlarmEntity, enabled: Boolean) = save(alarm.copy(enabled = enabled))

    suspend fun delete(alarm: AlarmEntity) {
        cancel(alarm.id)
        dao.deleteAlarm(alarm.id)
    }

    suspend fun rescheduleAll() = dao.enabledAlarms().forEach(::schedule)

    /** Dipanggil saat alarm berbunyi: jadwalkan ulang yang berulang, matikan yang sekali. */
    suspend fun onFired(id: Long) {
        val alarm = dao.alarm(id) ?: return
        if (alarm.days == 0) dao.upsertAlarm(alarm.copy(enabled = false)) else schedule(alarm)
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun schedule(alarm: AlarmEntity): Long {
        val triggerAt = nextTrigger(alarm)
        val show = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canScheduleExact()) {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), fireIntent(alarm.id))
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fireIntent(alarm.id))
        }
        return triggerAt
    }

    private fun cancel(id: Long) = alarmManager.cancel(fireIntent(id))

    private fun fireIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** Indeks hari Senin=0 .. Minggu=6 dari Calendar.DAY_OF_WEEK. */
        fun dayIndex(calendar: Calendar) = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7

        fun nextTrigger(alarm: AlarmEntity, now: Calendar = Calendar.getInstance()): Long {
            val candidate = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            for (offset in 0..7) {
                val day = (candidate.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
                if (day.timeInMillis <= now.timeInMillis) continue
                if (alarm.days == 0 || alarm.days and (1 shl dayIndex(day)) != 0) return day.timeInMillis
            }
            return candidate.apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        }

        private val dayNames = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

        fun describeDays(days: Int): String = when (days) {
            0 -> "Sekali"
            0b1111111 -> "Setiap hari"
            0b0011111 -> "Hari kerja"
            0b1100000 -> "Akhir pekan"
            else -> dayNames.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
        }

        fun dayLabels() = dayNames
    }
}
