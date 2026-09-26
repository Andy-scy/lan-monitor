package com.pulse.monitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "pulse_devices")

@Serializable
data class StoredDevice(
    val id: String,          // Agent 设备 ID（唯一键）
    val name: String,        // 显示名（默认主机名）
    val host: String,
    val port: Int,
    val token: String? = null,
    val os: String? = null,
    val lastSeen: Long = 0,
)

class DeviceStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("devices_json")

    val devices: Flow<List<StoredDevice>> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(StoredDevice.serializer()), raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun all(): List<StoredDevice> = devices.first()

    suspend fun upsert(device: StoredDevice) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(StoredDevice.serializer()), raw) }.getOrNull()
            } ?: emptyList()
            val merged = current.filterNot { it.id == device.id } + device
            prefs[key] = json.encodeToString(ListSerializer(StoredDevice.serializer()), merged)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(StoredDevice.serializer()), raw) }.getOrNull()
            } ?: emptyList()
            prefs[key] = json.encodeToString(
                ListSerializer(StoredDevice.serializer()),
                current.filterNot { it.id == id }
            )
        }
    }

    suspend fun find(id: String): StoredDevice? = all().firstOrNull { it.id == id }
}
