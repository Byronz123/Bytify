package com.bytify.app.data.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Klien Supabase Realtime minimal (protokol Phoenix di atas WebSocket OkHttp)
 * untuk satu channel: broadcast + presence. Tanpa tabel database.
 */
class RealtimeClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    projectUrl: String,
    private val apiKey: String,
) {
    enum class Status { Disconnected, Connecting, Connected }

    interface Listener {
        fun onBroadcast(event: String, payload: JsonObject)
        fun onPresence(members: Map<String, JsonObject>)
        fun onJoined()
        fun onClosed(error: String?)
    }

    private val socketUrl = projectUrl.trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"

    private val ref = AtomicInteger(0)
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var topic: String? = null
    private var joinRef: String? = null
    private var listener: Listener? = null
    private var presenceKey: String = ""
    private var presenceMeta: JsonObject? = null
    private val members = linkedMapOf<String, JsonObject>()
    private var closedByUser = false

    private val _status = MutableStateFlow(Status.Disconnected)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun join(channel: String, presenceKey: String, meta: JsonObject, listener: Listener) {
        leave()
        closedByUser = false
        this.topic = "realtime:$channel"
        this.presenceKey = presenceKey
        this.presenceMeta = meta
        this.listener = listener
        connect()
    }

    private fun connect() {
        _status.value = Status.Connecting
        socket = okHttp.newWebSocket(Request.Builder().url(socketUrl).build(), socketListener)
    }

    fun broadcast(event: String, payload: JsonObject) {
        send(
            "broadcast",
            buildJsonObject {
                put("type", "broadcast")
                put("event", event)
                put("payload", payload)
            },
        )
    }

    fun leave() {
        closedByUser = true
        heartbeat?.cancel()
        heartbeat = null
        topic?.let { send("phx_leave", JsonObject(emptyMap())) }
        socket?.close(1000, "leave")
        socket = null
        topic = null
        members.clear()
        _status.value = Status.Disconnected
    }

    private fun send(event: String, payload: JsonObject, toTopic: String? = topic) {
        val t = toTopic ?: return
        val message = buildJsonObject {
            put("topic", t)
            put("event", event)
            put("payload", payload)
            put("ref", ref.incrementAndGet().toString())
            joinRef?.let { put("join_ref", it) }
        }
        socket?.send(message.toString())
    }

    private fun sendJoin() {
        joinRef = ref.incrementAndGet().toString()
        val payload = buildJsonObject {
            putJsonObject("config") {
                putJsonObject("broadcast") {
                    put("self", false)
                    put("ack", false)
                }
                putJsonObject("presence") { put("key", presenceKey) }
                put("private", false)
            }
        }
        val message = buildJsonObject {
            put("topic", topic)
            put("event", "phx_join")
            put("payload", payload)
            put("ref", joinRef)
            put("join_ref", joinRef)
        }
        socket?.send(message.toString())
    }

    private fun trackPresence() {
        val meta = presenceMeta ?: return
        send(
            "presence",
            buildJsonObject {
                put("type", "presence")
                put("event", "track")
                put("payload", meta)
            },
        )
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sendJoin()
            heartbeat?.cancel()
            heartbeat = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    send("heartbeat", JsonObject(emptyMap()), toTopic = "phoenix")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            if (message["topic"]?.jsonPrimitive?.content != topic) return
            val payload = message["payload"] as? JsonObject ?: JsonObject(emptyMap())
            scope.launch { handle(message["event"]?.jsonPrimitive?.content.orEmpty(), payload, message) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch { onDisconnected(t.message ?: "Koneksi terputus") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { onDisconnected(null) }
        }
    }

    private fun handle(event: String, payload: JsonObject, message: JsonObject) {
        val l = listener ?: return
        when (event) {
            "phx_reply" -> {
                val isJoinReply = message["ref"]?.jsonPrimitive?.content == joinRef
                val ok = payload["status"]?.jsonPrimitive?.content == "ok"
                if (isJoinReply && ok && _status.value != Status.Connected) {
                    _status.value = Status.Connected
                    trackPresence()
                    l.onJoined()
                } else if (isJoinReply && !ok) {
                    l.onClosed("Gagal masuk room")
                }
            }
            "broadcast" -> {
                val name = payload["event"]?.jsonPrimitive?.content ?: return
                l.onBroadcast(name, payload["payload"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            "presence_state" -> {
                members.clear()
                payload.forEach { (key, value) -> firstMeta(value)?.let { members[key] = it } }
                l.onPresence(members.toMap())
            }
            "presence_diff" -> {
                (payload["leaves"] as? JsonObject)?.keys?.forEach { members.remove(it) }
                (payload["joins"] as? JsonObject)?.forEach { (key, value) -> firstMeta(value)?.let { members[key] = it } }
                l.onPresence(members.toMap())
            }
            "phx_error", "phx_close" -> scope.launch { onDisconnected("Room ditutup oleh server") }
        }
    }

    private fun firstMeta(value: JsonElement): JsonObject? =
        ((value as? JsonObject)?.get("metas") as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject

    /** Sambung ulang otomatis kecuali pengguna sendiri yang keluar. */
    private suspend fun onDisconnected(error: String?) {
        heartbeat?.cancel()
        if (closedByUser || topic == null) return
        _status.value = Status.Connecting
        delay(RECONNECT_MS)
        if (!closedByUser && topic != null) {
            socket?.cancel()
            connect()
        } else {
            listener?.onClosed(error)
        }
    }

    companion object {
        private const val HEARTBEAT_MS = 25_000L
        private const val RECONNECT_MS = 3_000L
    }
}
