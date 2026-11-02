package com.bytify.app.data.together

import com.bytify.app.data.model.Song
import com.bytify.app.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.UUID
import kotlin.math.abs

data class RoomMember(val key: String, val name: String, val isHost: Boolean)

sealed interface TogetherState {
    data object Idle : TogetherState
    data class Connecting(val code: String) : TogetherState
    data class InRoom(
        val code: String,
        val isHost: Boolean,
        val members: List<RoomMember>,
        val reconnecting: Boolean = false,
    ) : TogetherState
    data class Ended(val message: String) : TogetherState
}

/**
 * Dengar Bareng: host memutar, tamu mengikuti secara realtime lewat
 * Supabase Realtime (broadcast + presence).
 */
class ListenTogether(
    okHttp: OkHttpClient,
    json: Json,
    private val scope: CoroutineScope,
    private val player: PlayerConnection,
    supabaseUrl: String,
    supabaseKey: String,
) {
    val isConfigured = supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()

    private val client = RealtimeClient(okHttp, json, scope, supabaseUrl, supabaseKey)
    private val myKey = UUID.randomUUID().toString()

    private val _state = MutableStateFlow<TogetherState>(TogetherState.Idle)
    val state: StateFlow<TogetherState> = _state.asStateFlow()

    /** Saran lagu dari tamu (hanya untuk host). */
    private val _suggestions = MutableStateFlow<List<Pair<String, Song>>>(emptyList())
    val suggestions: StateFlow<List<Pair<String, Song>>> = _suggestions.asStateFlow()

    private var myName = ""
    private var isHost = false
    private var code = ""
    private var hostJobs = mutableListOf<Job>()
    private var statusJob: Job? = null
    private var pendingSync: Job? = null
    private var members: List<RoomMember> = emptyList()

    val isGuest: Boolean get() = (_state.value as? TogetherState.InRoom)?.isHost == false

    fun createRoom(name: String) = start(name, generateCode(), host = true)

    fun joinRoom(name: String, roomCode: String) = start(name, roomCode.trim().uppercase(), host = false)

    private fun start(name: String, roomCode: String, host: Boolean) {
        if (!isConfigured) return
        myName = name.trim().ifBlank { "Pendengar" }
        isHost = host
        code = roomCode
        members = emptyList()
        _suggestions.value = emptyList()
        _state.value = TogetherState.Connecting(roomCode)
        val meta = buildJsonObject {
            put("name", myName)
            put("host", host)
        }
        client.join("bytify-room-$roomCode", myKey, meta, listener)
        statusJob?.cancel()
        statusJob = scope.launch {
            client.status.collect { status ->
                val current = _state.value as? TogetherState.InRoom ?: return@collect
                _state.value = current.copy(reconnecting = status != RealtimeClient.Status.Connected)
            }
        }
    }

    fun leave() {
        if (isHost && _state.value is TogetherState.InRoom) client.broadcast(EVENT_END, JsonObject(emptyMap()))
        stopHostSync()
        statusJob?.cancel()
        client.leave()
        _state.value = TogetherState.Idle
    }

    fun dismissEnded() {
        if (_state.value is TogetherState.Ended) _state.value = TogetherState.Idle
    }

    /** Tamu menyarankan lagu ke host. */
    fun suggest(song: Song) {
        client.broadcast(EVENT_SUGGEST, buildJsonObject {
            put("from", myName)
            put("song", song.toJson())
        })
    }

    fun clearSuggestion(song: Song) {
        _suggestions.value = _suggestions.value.filterNot { it.second.id == song.id }
    }

    private val listener = object : RealtimeClient.Listener {
        override fun onJoined() {
            _state.value = TogetherState.InRoom(code, isHost, members)
            if (isHost) startHostSync() else client.broadcast(EVENT_REQUEST_SYNC, JsonObject(emptyMap()))
        }

        override fun onPresence(members: Map<String, JsonObject>) {
            this@ListenTogether.members = members.map { (key, meta) ->
                RoomMember(
                    key = key,
                    name = meta["name"]?.jsonPrimitive?.content ?: "Pendengar",
                    isHost = meta["host"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }.sortedByDescending { it.isHost }
            (_state.value as? TogetherState.InRoom)?.let {
                _state.value = it.copy(members = this@ListenTogether.members)
            }
            // Anggota baru langsung mendapat keadaan terkini.
            if (isHost) requestSync()
        }

        override fun onBroadcast(event: String, payload: JsonObject) {
            when (event) {
                EVENT_SYNC -> if (!isHost) applySync(payload)
                EVENT_REQUEST_SYNC -> if (isHost) requestSync()
                EVENT_SUGGEST -> if (isHost) {
                    val song = (payload["song"] as? JsonObject)?.toSong() ?: return
                    val from = payload["from"]?.jsonPrimitive?.content ?: "Tamu"
                    if (_suggestions.value.none { it.second.id == song.id }) {
                        _suggestions.value = _suggestions.value + (from to song)
                    }
                }
                EVENT_END -> if (!isHost) {
                    client.leave()
                    _state.value = TogetherState.Ended("Host mengakhiri sesi Dengar Bareng.")
                }
            }
        }

        override fun onClosed(error: String?) {
            stopHostSync()
            _state.value = TogetherState.Ended(error ?: "Terputus dari room.")
        }
    }

    // ------------------------------------------------------------------
    // Host: kirim keadaan pemutar
    // ------------------------------------------------------------------

    private fun startHostSync() {
        stopHostSync()
        hostJobs += scope.launch {
            player.state
                .map { Triple(it.song?.id, it.isPlaying, it.durationMs) }
                .distinctUntilChanged()
                .collect { requestSync() }
        }
        hostJobs += scope.launch {
            var lastPosition = player.currentPositionMs
            var lastCheck = System.currentTimeMillis()
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                // Kirim segera jika host menggeser posisi (lompatan tak wajar).
                val now = System.currentTimeMillis()
                val position = player.currentPositionMs
                val expected = lastPosition + if (player.state.value.isPlaying) now - lastCheck else 0
                lastPosition = position
                lastCheck = now
                if (abs(position - expected) > SEEK_JUMP_MS) sendSync()
            }
        }
        hostJobs += scope.launch {
            while (isActive) {
                delay(HEARTBEAT_SYNC_MS)
                sendSync()
            }
        }
    }

    private fun stopHostSync() {
        hostJobs.forEach { it.cancel() }
        hostJobs.clear()
    }

    /** Gabungkan pemicu yang berdekatan (ganti lagu, anggota baru) jadi satu pesan. */
    private fun requestSync() {
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(SYNC_COALESCE_MS)
            sendSync()
        }
    }

    private fun sendSync() {
        val state = player.state.value
        val song = state.song ?: return
        client.broadcast(EVENT_SYNC, buildJsonObject {
            put("song", song.toJson())
            put("position", player.currentPositionMs)
            put("playing", state.isPlaying)
        })
    }

    // ------------------------------------------------------------------
    // Tamu: ikuti host
    // ------------------------------------------------------------------

    private fun applySync(payload: JsonObject) {
        val song = (payload["song"] as? JsonObject)?.toSong() ?: return
        val position = payload["position"]?.jsonPrimitive?.longOrNull ?: 0
        val playing = payload["playing"]?.jsonPrimitive?.booleanOrNull ?: true
        val local = player.state.value
        if (local.song?.id != song.id) {
            player.playSongs(listOf(song), radio = false, startPositionMs = position + NETWORK_LATENCY_MS)
            if (!playing) player.pause()
            return
        }
        val target = position + if (playing) NETWORK_LATENCY_MS else 0
        if (abs(player.currentPositionMs - target) > DRIFT_TOLERANCE_MS) player.seekTo(target)
        if (playing && !local.isPlaying) player.play()
        if (!playing && local.isPlaying) player.pause()
    }

    private fun Song.toJson() = buildJsonObject {
        put("id", id)
        put("title", title)
        put("artist", artist)
        thumbnailUrl?.let { put("thumb", it) }
        durationSec?.let { put("duration", it) }
    }

    private fun JsonObject.toSong(): Song? {
        val id = (this["id"] as? JsonPrimitive)?.content ?: return null
        return Song(
            id = id,
            title = (this["title"] as? JsonPrimitive)?.content.orEmpty(),
            artist = (this["artist"] as? JsonPrimitive)?.content.orEmpty(),
            thumbnailUrl = (this["thumb"] as? JsonPrimitive)?.content,
            durationSec = (this["duration"] as? JsonPrimitive)?.longOrNull,
        )
    }

    companion object {
        private const val EVENT_SYNC = "sync"
        private const val EVENT_REQUEST_SYNC = "request_sync"
        private const val EVENT_SUGGEST = "suggest"
        private const val EVENT_END = "end"
        private const val SYNC_INTERVAL_MS = 1_000L
        private const val HEARTBEAT_SYNC_MS = 10_000L
        private const val SEEK_JUMP_MS = 2_000L
        private const val DRIFT_TOLERANCE_MS = 2_500L
        private const val NETWORK_LATENCY_MS = 300L
        private const val SYNC_COALESCE_MS = 300L

        /** Kode 6 karakter tanpa huruf yang mudah tertukar (0/O, 1/I). */
        private fun generateCode(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return (1..6).map { alphabet.random() }.joinToString("")
        }
    }
}
