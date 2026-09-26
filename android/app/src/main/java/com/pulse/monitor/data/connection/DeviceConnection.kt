package com.pulse.monitor.data.connection

import com.pulse.monitor.data.model.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** 连接状态机：Connecting → Online ⇄ Stale → Reconnecting → Offline */
enum class LinkState { CONNECTING, ONLINE, STALE, RECONNECTING, OFFLINE, NEEDS_PAIRING }

/** 内存环形时序缓冲（Dashboard 与详情页图表共用） */
class Series(private val capacity: Int = 1800) {
    private val lock = Any()
    private val values = ArrayDeque<Float>(capacity)
    private val stamps = ArrayDeque<Long>(capacity)

    fun add(ts: Long, v: Float?) {
        if (v == null || v.isNaN()) return
        synchronized(lock) {
            values.addLast(v)
            stamps.addLast(ts)
            while (values.size > capacity) {
                values.removeFirst()
                stamps.removeFirst()
            }
        }
    }

    fun snapshot(): Pair<FloatArray, LongArray> = synchronized(lock) {
        values.toFloatArray() to stamps.toLongArray()
    }

    fun last(n: Int): FloatArray = synchronized(lock) {
        if (values.isEmpty()) return FloatArray(0)
        val from = maxOf(0, values.size - n)
        values.toList().subList(from, values.size).toFloatArray()
    }

    fun lastValue(): Float? = synchronized(lock) { values.lastOrNull() }

    fun size(): Int = synchronized(lock) { values.size }
}

/**
 * 单台电脑的长连接。一个实例 = 一台设备。
 * 状态与快照全部以 StateFlow 暴露，UI 用 collectAsState 订阅；
 * 图表数据从 Series 读取，避免 1Hz 全页重组。
 */
class DeviceConnection(
    val deviceId: String,
    var displayName: String,
    var host: String,
    var port: Int,
    @Volatile var token: String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var wantConnected = false
    private var wsSocket: WebSocket? = null
    @Volatile private var attempt = 0

    private val _state = MutableStateFlow(LinkState.OFFLINE)
    val state: StateFlow<LinkState> = _state

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snap: StateFlow<Snapshot?> = _snapshot

    @Volatile var lastMessageAt = 0L
        private set

    // ---- 历史缓冲（30 分钟 @1s）----
    val cpuHist = Series()
    val gpuHist = Series()
    val gpuMemHist = Series()
    val memHist = Series()
    val downHist = Series()
    val upHist = Series()
    val cpuTempHist = Series()
    val gpuTempHist = Series()
    val freqHist = Series()

    private fun ingest(s: Snapshot) {
        _snapshot.value = s
        lastMessageAt = System.currentTimeMillis()
        val ts = s.ts
        cpuHist.add(ts, s.cpu?.usage?.toFloat())
        cpuTempHist.add(ts, s.cpu?.temp?.toFloat())
        freqHist.add(ts, s.cpu?.freqMhz?.toFloat())
        s.gpus?.firstOrNull()?.let { g ->
            gpuHist.add(ts, g.usage?.toFloat())
            gpuMemHist.add(ts, g.memUsedMB?.toFloat())
            gpuTempHist.add(ts, g.temp?.toFloat())
        }
        memHist.add(ts, s.mem?.usagePct?.toFloat())
        downHist.add(ts, s.net?.downloadBps?.toFloat())
        upHist.add(ts, s.net?.uploadBps?.toFloat())
        if (_state.value == LinkState.STALE || _state.value == LinkState.CONNECTING) {
            _state.value = LinkState.ONLINE
        }
    }

    // ---- 生命周期 ----

    fun start() {
        if (wantConnected) return
        wantConnected = true
        scope.launch { connectLoop() }
        scope.launch { staleWatch() }
    }

    fun stop() {
        wantConnected = false
        wsSocket?.close(1000, "bye")
        wsSocket = null
        _state.value = LinkState.OFFLINE
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    fun markPaired(newToken: String) {
        token = newToken
        if (wantConnected) {
            // 让 connectLoop 用新 token 重连
            wsSocket?.cancel()
            wsSocket = null
        } else {
            start()
        }
    }

    private suspend fun connectLoop() {
        while (scope.isActive && wantConnected) {
            val t = token
            if (t.isNullOrEmpty()) {
                _state.value = LinkState.NEEDS_PAIRING
                delay(1500)
                continue
            }
            if (wsSocket == null) {
                _state.value = if (attempt == 0) LinkState.CONNECTING else LinkState.RECONNECTING
                attempt++
                openSocket(t)
            }
            delay(backoffDelay(attempt))
            if (wsSocket == null) continue // 建连失败，指数退避后重试
        }
    }

    private fun backoffDelay(attempt: Int): Long {
        val base = if (attempt <= 1) 800L else minOf(15000L, 1000L shl minOf(attempt, 4))
        return base + (0..300).random()
    }

    private fun openSocket(t: String) {
        val req = Request.Builder()
            .url("ws://$host:$port/api/ws?token=$t")
            .build()
        wsSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                _state.value = LinkState.ONLINE
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString(Snapshot.serializer(), text) }
                    .onSuccess { ingest(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.w("Pulse", "ws fail: ${t::class.simpleName}: ${t.message}")
                if (response?.code == 401) _state.value = LinkState.NEEDS_PAIRING
                else if (wantConnected) _state.value = LinkState.RECONNECTING
                wsSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsSocket = null
                if (wantConnected) _state.value = LinkState.OFFLINE
            }
        })
    }

    private suspend fun staleWatch() {
        while (scope.isActive) {
            delay(1000)
            val s = _state.value
            if (s == LinkState.ONLINE && System.currentTimeMillis() - lastMessageAt > 5000) {
                _state.value = LinkState.STALE
            }
        }
    }

    /** 自适应降频：让 Agent 改为 intervalMs 毫秒推一次（省电模式） */
    fun sendInterval(intervalMs: Int) {
        val msg: JsonObject = buildJsonObject {
            put("ctrl", buildJsonObject { put("intervalMs", intervalMs) })
        }
        wsSocket?.send(msg.toString())
    }

    /** 一次性 HTTP 拉取（设备列表页的轻量摘要轮询用） */
    fun fetchOnce(): Snapshot? {
        val t = token ?: return null
        return runCatching {
            val req = Request.Builder()
                .url("http://$host:$port/api/snapshot?token=$t")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                json.decodeFromString(Snapshot.serializer(), body)
            }
        }.getOrNull()?.also { ingest(it) }
    }

    /** 一键内存优化（同步调用，需在 IO 线程） */
    fun optimize(): com.pulse.monitor.data.model.OptimizeResult? {
        val t = token ?: return null
        return runCatching {
            val req = Request.Builder()
                .url("http://$host:$port/api/optimize")
                .header("Authorization", "Bearer $t")
                .post(okhttp3.RequestBody.create(null, "{}"))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@runCatching null
                if (!resp.isSuccessful) return@runCatching null
                json.decodeFromString(com.pulse.monitor.data.model.OptimizeResult.serializer(), body)
            }
        }.getOrNull()
    }

    fun helloProbe(): HelloResult {
        return runCatching {
            val req = Request.Builder().url("http://$host:$port/api/hello").build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && body != null) {
                    android.util.Log.i("Pulse", "probe ok $host:$port")
                    HelloResult.Online(json.decodeFromString(com.pulse.monitor.data.model.Hello.serializer(), body))
                } else {
                    android.util.Log.w("Pulse", "probe http ${resp.code} $host:$port")
                    HelloResult.Unreachable
                }
            }
        }.getOrElse {
            android.util.Log.w("Pulse", "probe err $host:$port", it)
            HelloResult.Unreachable
        }
    }
}

sealed class HelloResult {
    data class Online(val hello: com.pulse.monitor.data.model.Hello) : HelloResult()
    object Unreachable : HelloResult()
}
