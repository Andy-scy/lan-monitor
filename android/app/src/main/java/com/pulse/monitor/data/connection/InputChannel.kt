package com.pulse.monitor.data.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * 遥控输入通道：独立于数据 WS 的低延迟小消息通道。
 * 懒连接 + 失败重连（下次 send 触发）+ 短队列缓冲，触控板 60Hz 移动无需等待建连。
 */
class InputChannel(
    private val host: String,
    private val port: Int,
    private val token: String?,
) {
    @Serializable
    data class Msg(
        val t: String,
        val dx: Double? = null,
        val dy: Double? = null,
        val b: Int? = null,
        val d: Int? = null,
        val dbl: Int? = null,
        val delta: Double? = null,
        val s: String? = null,
        val k: String? = null,
        val keys: List<String>? = null,
    )

    private val json = Json { encodeDefaults = false }
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val pending = ArrayDeque<String>()
    @Volatile var open = false
        private set

    private fun ensure() {
        if (ws != null) return
        val t = token ?: return
        val req = Request.Builder().url("ws://$host:$port/api/input?token=$t").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                synchronized(pending) {
                    while (pending.isNotEmpty()) {
                        webSocket.send(pending.removeFirst()) || break
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                ws = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                ws = null
            }
        })
    }

    private fun dispatch(m: Msg) {
        val text = json.encodeToString(Msg.serializer(), m)
        ensure()
        val sock = ws
        if (sock != null && open) {
            if (!sock.send(text)) buffer(text)
        } else {
            buffer(text)
        }
    }

    private fun buffer(text: String) {
        synchronized(pending) {
            pending.addLast(text)
            while (pending.size > 64) pending.removeFirst() // 丢弃最旧的移动事件
        }
    }

    fun move(dx: Float, dy: Float) = dispatch(Msg(t = "mv", dx = dx.toDouble(), dy = dy.toDouble()))
    fun click(button: Int = 0, double: Boolean = false) =
        dispatch(Msg(t = "cl", b = button, dbl = if (double) 1 else 0))
    fun button(button: Int, down: Boolean) = dispatch(Msg(t = "bd", b = button, d = if (down) 1 else 0))
    fun wheel(delta: Float) = dispatch(Msg(t = "wh", delta = delta.toDouble()))
    fun text(s: String) = dispatch(Msg(t = "tx", s = s))
    fun key(name: String) = dispatch(Msg(t = "kk", k = name))
    fun combo(keys: List<String>) = dispatch(Msg(t = "cb", keys = keys))

    fun close() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        open = false
        synchronized(pending) { pending.clear() }
    }
}
