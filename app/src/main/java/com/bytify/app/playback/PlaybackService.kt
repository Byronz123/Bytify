package com.bytify.app.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.bytify.app.BytifyApplication
import com.bytify.app.MainActivity
import com.bytify.app.R
import com.bytify.app.alarm.AlarmReceiver
import com.bytify.app.data.model.Song
import com.bytify.app.widget.BytifyWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Service pemutar latar belakang: notifikasi media, kontrol lockscreen,
 * Android Auto, alarm musik, widget, Dynamic Island, dan pencatatan riwayat.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val container by lazy { (application as BytifyApplication).container }
    private lateinit var island: DynamicIsland

    /** Satu sesi dengar; dicatat sekali lalu durasinya diperbarui. */
    private class ListenRecord(val song: Song, val startedAt: Long) {
        var eventId: Long? = null
    }

    private var record: ListenRecord? = null
    private var accumulatedMs = 0L
    private var playingSince: Long? = null
    private var consecutiveErrors = 0
    private val recordMutex = Mutex()
    private var artworkJob: Job? = null
    private var fadeJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(container.playbackDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_bytify)
            },
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(container, scope, packageName))
            .setSessionActivity(openApp)
            .build()

        island = DynamicIsland(
            this,
            container.nowPlaying,
            object : IslandControls {
                override fun togglePlay() = if (player.isPlaying) player.pause() else player.play()
                override fun next() = player.seekToNext()
                override fun previous() = player.seekToPrevious()
            },
        )

        scope.launch {
            var effectsAttached = false
            container.settings.settings.collect {
                player.skipSilenceEnabled = it.skipSilence
                container.youtube.preferHighQuality = it.highQuality
                if (!effectsAttached) {
                    container.audioEffects.attach(player.audioSessionId, it.equalizer)
                    effectsAttached = true
                } else {
                    container.audioEffects.apply(it.equalizer)
                }
            }
        }

        // Dynamic Island tampil hanya saat aplikasi di latar belakang & ada lagu aktif.
        scope.launch {
            val appVisible = MutableStateFlow(isAppVisible())
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, _ -> appVisible.value = isAppVisible() },
            )
            combine(
                container.settings.settings.map { it.dynamicIsland },
                appVisible,
                container.nowPlaying.map { it.song != null },
            ) { enabled, visible, hasSong -> enabled && !visible && hasSong }
                .collect { show -> if (show) island.show() else island.hide() }
        }

        // Simpan berkala agar riwayat tidak hilang jika aplikasi ditutup paksa.
        scope.launch {
            while (true) {
                delay(CHECKPOINT_MS)
                if (player.isPlaying) saveListen()
            }
        }
    }

    private fun isAppVisible() =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ALARM) {
            startAlarm(intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1))
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ------------------------------------------------------------------
    // Alarm musik
    // ------------------------------------------------------------------

    private fun startAlarm(alarmId: Long) {
        // Wajib menjadi foreground dalam 5 detik; notifikasi media menggantikannya nanti.
        ServiceCompat.startForeground(
            this,
            ALARM_NOTIFICATION_ID,
            alarmNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        scope.launch {
            val alarm = container.alarms.alarm(alarmId)
            var songs = alarm?.let { container.songsForSource(it.source) }.orEmpty()
            if (songs.isEmpty()) songs = container.songsForSource("recent")
            if (songs.isEmpty()) songs = runCatching { container.charts.topSongs("id") }.getOrDefault(emptyList())
            if (songs.isEmpty()) {
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                return@launch
            }
            val queue = (if (alarm?.shuffle != false) songs.shuffled() else songs).take(ALARM_QUEUE_SIZE)
            player.volume = 0.1f
            player.setMediaItems(queue.map { it.toMediaItem() })
            player.prepare()
            player.play()
            fadeIn()
            // Setelah notifikasi media tampil, notifikasi persiapan alarm tidak diperlukan lagi.
            delay(6_000)
            NotificationManagerCompat.from(this@PlaybackService).cancel(ALARM_NOTIFICATION_ID)
        }
    }

    /** Volume naik perlahan selama 30 detik agar bangun lebih nyaman. */
    private fun fadeIn() {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            for (step in 1..30) {
                delay(1_000)
                player.volume = (0.1f + step * 0.03f).coerceAtMost(1f)
            }
        }
    }

    private fun alarmNotification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(ALARM_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Alarm musik")
                .build(),
        )
        return NotificationCompat.Builder(this, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bytify)
            .setContentTitle("Alarm Bytify")
            .setContentText("Menyiapkan musikmu…")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
    }

    // ------------------------------------------------------------------
    // Event pemutar
    // ------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveListen()
            record = mediaItem?.let { ListenRecord(Song.fromMediaItem(it), System.currentTimeMillis()) }
            accumulatedMs = 0
            playingSince = if (player.isPlaying) System.currentTimeMillis() else null
            publishNowPlaying(loadArtwork = true)
            prefetchNext()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            if (isPlaying) {
                playingSince = now
                consecutiveErrors = 0
            } else {
                playingSince?.let { accumulatedMs += now - it }
                playingSince = null
                saveListen()
            }
            publishNowPlaying(loadArtwork = false)
        }

        override fun onPlayerError(error: PlaybackException) {
            // Lewati lagu yang gagal agar pemutaran tetap berlanjut (maks. 3 kali beruntun).
            if (consecutiveErrors++ < MAX_AUTO_SKIPS && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /** Siapkan URL stream lagu berikutnya agar perpindahan lagu instan. */
    private fun prefetchNext() {
        val nextIndex = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val nextId = player.getMediaItemAt(nextIndex).mediaId
        if (container.downloads.isDownloaded(nextId)) return
        container.appScope.launch(Dispatchers.IO) {
            runCatching { container.youtube.resolveStreamUrlBlocking(nextId) }
        }
    }

    /** Perbarui state bersama untuk widget & Dynamic Island. */
    private fun publishNowPlaying(loadArtwork: Boolean) {
        val song = player.currentMediaItem?.let(Song::fromMediaItem)
        val previous = container.nowPlaying.value
        val keepArt = !loadArtwork && previous.song?.id == song?.id
        container.nowPlaying.value = NowPlaying(song, player.isPlaying, if (keepArt) previous.artwork else null)
        scope.launch { BytifyWidget.refresh(this@PlaybackService) }
        if (!loadArtwork || song?.thumbnailUrl == null) return
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val request = ImageRequest.Builder(this@PlaybackService)
                .data(song.thumbnailUrl)
                .size(256)
                .allowHardware(false)
                .build()
            val bitmap = (imageLoader.execute(request) as? SuccessResult)?.drawable
                ?.let { it as? BitmapDrawable }?.bitmap ?: return@launch
            if (container.nowPlaying.value.song?.id == song.id) {
                container.nowPlaying.value = container.nowPlaying.value.copy(artwork = bitmap)
                BytifyWidget.refresh(this@PlaybackService)
            }
        }
    }

    /** Catat (atau perbarui) durasi dengar sesi saat ini ke riwayat. */
    private fun saveListen() {
        val current = record ?: return
        val total = accumulatedMs + (playingSince?.let { System.currentTimeMillis() - it } ?: 0)
        if (total < MIN_RECORDED_MS) return
        container.appScope.launch(Dispatchers.IO) {
            recordMutex.withLock {
                val id = current.eventId
                if (id == null) {
                    current.eventId = container.library.recordPlay(current.song, current.startedAt, total)
                } else {
                    container.library.updatePlayTime(id, total)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveListen()
        island.hide()
        container.nowPlaying.value = NowPlaying()
        container.appScope.launch { BytifyWidget.refresh(this@PlaybackService) }
        container.audioEffects.release()
        session?.release()
        session = null
        player.removeListener(listener)
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ALARM = "com.bytify.app.action.ALARM"
        private const val MIN_RECORDED_MS = 10_000L
        private const val CHECKPOINT_MS = 15_000L
        private const val MAX_AUTO_SKIPS = 3
        private const val ALARM_NOTIFICATION_ID = 3
        private const val ALARM_CHANNEL = "bytify_alarm"
        private const val ALARM_QUEUE_SIZE = 50
    }
}
