package com.bytify.app.playback

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.bytify.app.BytifyApplication
import com.bytify.app.R

/** Service latar untuk unduhan offline, dengan notifikasi progres. */
@OptIn(UnstableApi::class)
class BytifyDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager =
        (application as BytifyApplication).container.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val active = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        return helper.buildProgressNotification(
            this,
            R.drawable.ic_stat_bytify,
            null,
            "Mengunduh $active lagu",
            downloads,
            notMetRequirements,
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "bytify_downloads"
    }
}
