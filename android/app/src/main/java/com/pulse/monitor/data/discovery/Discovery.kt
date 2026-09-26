package com.pulse.monitor.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.pulse.monitor.data.model.Hello
import com.pulse.monitor.data.model.PairResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 局域网发现（UDP 广播）：
 * 同时发 255.255.255.255 与本网段定向广播（很多路由器会丢弃全局广播），
 * Agent 单播回 JSON 应答。收包期间持有 MulticastLock 提高成功率。
 */
object Discovery {

    const val MAGIC = "PULSE:DISCOVER:v1"
    const val DISCOVERY_PORT = 42711

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Found(val hello: Hello, val address: String)

    suspend fun scan(context: Context, durationMs: Long = 3200): List<Found> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("pulse-scan")
        lock?.setReferenceCounted(false)
        lock?.acquire()
        try {
            val targets = mutableListOf<InetAddress>()
            runCatching {
                targets.add(InetAddress.getByName("255.255.255.255"))
                wifi?.connectionInfo?.ipAddress?.let { ip ->
                    if (ip != 0) {
                        // DHCP 信息是反端序 int
                        targets.add(
                            InetAddress.getByAddress(
                                byteArrayOf(
                                    (ip and 0xFF).toByte(), ((ip shr 8) and 0xFF).toByte(),
                                    ((ip shr 16) and 0xFF).toByte(), 0xFF.toByte(),
                                )
                            )
                        )
                    }
                }
            }.onFailure { runCatching { targets.add(InetAddress.getByName("255.255.255.255")) } }

            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0)) // 系统分配临时端口，Agent 会回包到源端口
                broadcast = true
                soTimeout = 300
            }
            socket.use { s ->
                val payload = MAGIC.toByteArray()
                repeat(2) {
                    for (t in targets) {
                        runCatching {
                            s.send(DatagramPacket(payload, payload.size, t, DISCOVERY_PORT))
                        }
                    }
                    delay(200)
                }
                val found = LinkedHashMap<String, Found>()
                val deadline = System.currentTimeMillis() + durationMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val hello = runCatching { json.decodeFromString(Hello.serializer(), text) }.getOrNull()
                        if (hello != null && hello.id.isNotBlank() && hello.proto?.startsWith("PULSE") != false) {
                            found.putIfAbsent(hello.id, Found(hello, pkt.address.hostAddress ?: ""))
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
                found.values.toList()
            }
        } finally {
            lock?.release()
        }
    }

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
}

/** PIN 配对 */
object PairingApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    sealed class Result {
        data class Ok(val token: String, val name: String?) : Result()
        object WrongPin : Result()
        object Unreachable : Result()
    }

    fun pair(host: String, port: Int, pin: String): Result {
        return runCatching {
            val body = """{"pin":"${pin.trim()}"}""".toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("http://$host:$port/api/pair").post(body).build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val text = resp.body?.string() ?: return Result.Unreachable
                        val parsed = json.decodeFromString(PairResponse.serializer(), text)
                        if (parsed.token.isBlank()) Result.Unreachable else Result.Ok(parsed.token, parsed.name)
                    }
                    resp.code == 403 -> Result.WrongPin
                    else -> Result.Unreachable
                }
            }
        }.getOrElse { Result.Unreachable }
    }
}
