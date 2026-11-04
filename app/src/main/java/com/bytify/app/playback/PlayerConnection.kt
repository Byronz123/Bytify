package com.bytify.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bytify.app.data.model.Song
import com.bytify.app.data.prefs.SettingsRepository
import com.bytify.app.data.youtube.YouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val error: String? = null,
)

/** Mode sleep timer: berhenti pada waktu tertentu, atau di akhir lagu. */
sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val endsAtMs: Long) : SleepTimer
    data object EndOfSong : SleepTimer
}

/** Penghubung UI ke [PlaybackService] lewat MediaController. */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val youtube: YouTubeRepository,
    private val settings: SettingsRepository,
) {
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()

    private var sleepJob: Job? = null
    private var radioSeedId: String? = null
    private var loadingMore = false

    suspend fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val built = MediaController.Builder(context, token).buildAsync().await()
        controller = built
        built.addListener(listener)
        sync()
        scope.launch {
            while (isActive) {
                controller?.let { if (it.isPlaying || _positionMs.value != it.currentPosition) _positionMs.value = it.currentPosition }
                delay(POSITION_TICK_MS)
            }
        }
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /**
     * Putar daftar lagu mulai [startIndex]; satu lagu otomatis dibuatkan radio
     * kecuali [radio] false (mis. saat mengikuti host Listen Together).
     */
    fun playSongs(
        songs: List<Song>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        radio: Boolean = true,
        startPositionMs: Long = 0,
    ) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex.coerceIn(songs.indices), startPositionMs)
        c.prepare()
        c.play()
        radioSeedId = null
        if (radio && songs.size == 1) startRadio(songs.first())
    }

    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) return
        playSongs(songs, songs.indices.random(), shuffle = true)
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(listOf(song))
        c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(listOf(song))
        c.addMediaItem(song.toMediaItem())
    }

    fun removeFromQueue(index: Int) {
        controller?.takeIf { index != it.currentMediaItemIndex }?.removeMediaItem(index)
    }

    fun play() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    /** Hentikan lagu & kosongkan antrean (mini player ditutup). */
    fun stopAndClear() {
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    fun pause() {
        controller?.pause()
    }

    /** Posisi terkini langsung dari controller (lebih akurat dari tick 250 ms). */
    val currentPositionMs: Long get() = controller?.currentPosition ?: _positionMs.value

    fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun skipTo(index: Int) = controller?.seekToDefaultPosition(index)

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        controller?.playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _sleepTimer.value = SleepTimer.Off
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimer.value = SleepTimer.At(endsAt)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _sleepTimer.value = SleepTimer.Off
        }
    }

    fun sleepAtEndOfSong() {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimer.EndOfSong
    }

    /** Isi antrean dengan lagu-lagu terkait (gaya "radio"). */
    private fun startRadio(seed: Song) {
        if (seed.id.startsWith(Song.SEARCH_PREFIX)) return
        scope.launch {
            if (!settings.settings.first().autoRadio) return@launch
            radioSeedId = seed.id
            val related = runCatching { youtube.related(seed.id) }.getOrDefault(emptyList())
            val c = controller ?: return@launch
            if (radioSeedId != seed.id) return@launch
            val existing = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            c.addMediaItems(related.filter { it.id !in existing }.take(RADIO_SIZE).map { it.toMediaItem() })
        }
    }

    /** Saat antrean hampir habis, tambahkan lagu terkait otomatis. */
    private fun maybeLoadMore() {
        val c = controller ?: return
        if (loadingMore || c.repeatMode != Player.REPEAT_MODE_OFF) return
        if (c.currentMediaItemIndex < c.mediaItemCount - 1) return
        val current = c.currentMediaItem?.mediaId ?: return
        if (current.startsWith(Song.SEARCH_PREFIX)) return
        loadingMore = true
        scope.launch {
            try {
                if (!settings.settings.first().autoRadio) return@launch
                val related = runCatching { youtube.related(current) }.getOrDefault(emptyList())
                val controllerNow = controller ?: return@launch
                val existing = (0 until controllerNow.mediaItemCount)
                    .map { controllerNow.getMediaItemAt(it).mediaId }.toSet()
                controllerNow.addMediaItems(
                    related.filter { it.id !in existing }.take(RADIO_SIZE).map { it.toMediaItem() },
                )
            } finally {
                loadingMore = false
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_sleepTimer.value == SleepTimer.EndOfSong &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            ) {
                controller?.pause()
                _sleepTimer.value = SleepTimer.Off
            }
            maybeLoadMore()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = sync()
    }

    private fun sync() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { Song.fromMediaItem(c.getMediaItemAt(it)) }
        _state.value = PlayerUiState(
            song = c.currentMediaItem?.let(Song::fromMediaItem),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            pitch = c.playbackParameters.pitch,
            error = c.playerError?.let(::describe),
        )
        _positionMs.value = c.currentPosition
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Tidak ada koneksi internet."
        else -> "Lagu ini gagal diputar. Coba lagu lain."
    }

    companion object {
        private const val POSITION_TICK_MS = 250L
        private const val RADIO_SIZE = 20
    }
}
