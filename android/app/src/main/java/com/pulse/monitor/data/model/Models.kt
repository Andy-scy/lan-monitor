package com.pulse.monitor.data.model

import kotlinx.serialization.Serializable

/**
 * 数据协议 v1（与 docs/数据协议.md 对应）。
 * 所有字段可空：null = 硬件不支持/获取失败，UI 必须显示「不支持」而非 0。
 */
@Serializable
data class Snapshot(
    val v: Int = 1,
    val ts: Long = 0,
    val uptimeSec: Double? = null,
    val sys: SysInfo? = null,
    val cpu: CpuInfo? = null,
    val gpus: List<Gpu>? = null,
    val mem: MemInfo? = null,
    val disks: List<Disk>? = null,
    val net: NetInfo? = null,
)

@Serializable
data class SysInfo(
    val host: String = "",
    val os: String? = null,
    val osVersion: String? = null,
    val ip: String? = null,
    val agent: String? = null,
)

@Serializable
data class CpuInfo(
    val name: String? = null,
    val usage: Double? = null,
    val temp: Double? = null,
    val tempSource: String? = null,
    val freqMhz: Double? = null,
    val baseMhz: Double? = null,
    val coresLogical: Int = 0,
    val coresPhysical: Int = 0,
    val perCore: List<Double>? = null,
)

@Serializable
data class Gpu(
    val id: String = "",
    val name: String? = null,
    val vendor: String? = null,
    val usage: Double? = null,
    val temp: Double? = null,
    val powerW: Double? = null,
    val clockMhz: Double? = null,
    val memUsedMB: Double? = null,
    val memTotalMB: Double? = null,
    val memSharedUsedMB: Double? = null,
)

@Serializable
data class MemInfo(
    val totalMB: Double? = null,
    val usedMB: Double? = null,
    val availableMB: Double? = null,
    val usagePct: Double? = null,
    val commitUsedMB: Double? = null,
    val commitLimitMB: Double? = null,
)

@Serializable
data class Disk(
    val drive: String = "",
    val label: String? = null,
    val removable: Boolean = false,
    val totalMB: Double? = null,
    val usedMB: Double? = null,
    val freeMB: Double? = null,
    val usagePct: Double? = null,
    val readBps: Double? = null,
    val writeBps: Double? = null,
)

@Serializable
data class NetIface(
    val name: String = "",
    val desc: String? = null,
    val downBps: Double? = null,
    val upBps: Double? = null,
    val active: Boolean = false,
)

@Serializable
data class NetInfo(
    val downloadBps: Double? = null,
    val uploadBps: Double? = null,
    val totalRxBytes: Long? = null,
    val totalTxBytes: Long? = null,
    val active: String? = null,
    val activeDesc: String? = null,
    val interfaces: List<NetIface>? = null,
)

/** /api/hello 与 UDP 发现应答共用 */
@Serializable
data class Hello(
    val proto: String? = null,
    val name: String? = null,
    val host: String = "",
    val os: String? = null,
    val port: Int = 42710,
    val id: String = "",
    val agent: String? = null,
    val pairingRequired: Boolean = true,
)

/** /api/pair 响应 */
@Serializable
data class PairResponse(val token: String = "", val name: String? = null, val id: String = "")

/** /api/optimize 响应：一键内存优化结果 */
@Serializable
data class OptimizeResult(
    val beforeAvailMB: Double? = null,
    val afterAvailMB: Double? = null,
    val freedMB: Double? = null,
    val swept: Int? = null,
    val skipped: Int? = null,
)
