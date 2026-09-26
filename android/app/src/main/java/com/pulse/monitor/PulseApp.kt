package com.pulse.monitor

import android.app.Application
import com.pulse.monitor.data.connection.DeviceConnection
import com.pulse.monitor.data.store.DeviceStore
import com.pulse.monitor.data.store.StoredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PulseApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: DeviceStore
        private set

    private val connections = LinkedHashMap<String, DeviceConnection>()

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
    }

    /** 取（或创建）设备连接；host/port 变化时原位更新 */
    fun connectionFor(device: StoredDevice): DeviceConnection {
        synchronized(connections) {
            val existing = connections[device.id]
            if (existing != null) {
                existing.host = device.host
                existing.port = device.port
                existing.displayName = device.name
                existing.token = device.token
                return existing
            }
            val c = DeviceConnection(
                deviceId = device.id,
                displayName = device.name,
                host = device.host,
                port = device.port,
                token = device.token,
            )
            connections[device.id] = c
            return c
        }
    }

    fun forget(id: String) {
        synchronized(connections) {
            connections.remove(id)?.destroy()
        }
        appScope.launch { store.remove(id) }
    }

    /** App 退后台：停所有 WS（省电）；回前台由 UI 触发 resume */
    fun pauseAll() {
        synchronized(connections) { connections.values.forEach { it.stop() } }
    }

    fun resumeActive(ids: List<String>) {
        appScope.launch {
            synchronized(connections) {
                ids.forEach { id ->
                    connections[id]?.let { c ->
                        if (c.token != null) c.start()
                    }
                }
            }
        }
    }
}
