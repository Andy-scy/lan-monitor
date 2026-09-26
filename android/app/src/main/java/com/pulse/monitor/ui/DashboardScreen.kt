package com.pulse.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.monitor.data.connection.DeviceConnection
import com.pulse.monitor.data.connection.LinkState
import com.pulse.monitor.data.model.Snapshot
import com.pulse.monitor.ui.charts.ArcGauge
import com.pulse.monitor.ui.charts.BarMeter
import com.pulse.monitor.ui.charts.Sparkline
import com.pulse.monitor.ui.common.Hairline
import com.pulse.monitor.ui.common.MetricCard
import com.pulse.monitor.ui.common.StateBadge
import com.pulse.monitor.ui.common.VDivider
import com.pulse.monitor.ui.common.fmtBps
import com.pulse.monitor.ui.common.fmtMB
import com.pulse.monitor.ui.common.fmtPct
import com.pulse.monitor.ui.common.fmtPct1
import com.pulse.monitor.ui.common.fmtUptime
import com.pulse.monitor.ui.theme.Label
import com.pulse.monitor.ui.theme.LocalPulseColors
import com.pulse.monitor.ui.theme.MetricNumber
import com.pulse.monitor.ui.theme.SmallNumber

private fun Modifier.dim(a: Float): Modifier = this.alpha(a)

@Composable
fun DashboardScreen(
    connection: DeviceConnection,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenRemote: () -> Unit = {},
) {
    val pulse = LocalPulseColors.current
    val state by connection.state.collectAsState()
    val snap by connection.snap.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var optMsg by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(pulse.bg)
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DashboardHeader(connection, state, onBack)
        Hairline()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GaugeRow(snap, state, onOpenDetail)
            Hairline()
            MemorySection(snap, state, onOpenDetail)
            Hairline()
            NetworkSection(snap, state, onOpenDetail)
            Hairline()
            DiskSection(snap, state)
            Hairline()
            RemoteSection(state, onOpenRemote)
            FooterInfo(snap)
            Spacer(Modifier.height(12.dp))
        }
        }
        // 一键优化悬浮按钮（右下角，M3 FAB）
        FloatingActionButton(
            onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { connection.optimize() }
                    optMsg = when {
                        r == null -> "设备离线，无法优化"
                        (r.freedMB ?: 0.0) > 0 -> "内存优化完成：释放 %.1f MB · 整理 %d 个进程".format(java.util.Locale.US, r.freedMB ?: 0.0, r.swept ?: 0)
                        else -> "优化完成：整理 %d 个进程，可用内存 %.1f GB".format(java.util.Locale.US, r.swept ?: 0, (r.afterAvailMB ?: 0.0) / 1024)
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp),
            containerColor = pulse.cpu,
            contentColor = pulse.bg,
        ) {
            Icon(Icons.Filled.Build, "一键优化")
        }
        optMsg?.let { m ->
            AlertDialog(
                onDismissRequest = { optMsg = null },
                confirmButton = { TextButton(onClick = { optMsg = null }) { Text("好的") } },
                title = { Text(m, style = com.pulse.monitor.ui.theme.Body) },
            )
        }
    }
}

@Composable
private fun DashboardHeader(connection: DeviceConnection, state: LinkState, onBack: () -> Unit) {
    val pulse = LocalPulseColors.current
    val snap by connection.snap.collectAsState()
    val sys = snap?.sys
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pulse.textPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                connection.displayName,
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = pulse.textPrimary,
            )
            Text(
                listOfNotNull(sys?.os, sys?.ip).joinToString(" · ").ifEmpty { connection.host },
                style = SmallNumber, color = pulse.textTertiary,
            )
        }
        when (state) {
            LinkState.ONLINE -> StateBadge("在线", pulse.online, breathing = true)
            LinkState.CONNECTING -> StateBadge("连接中", pulse.cpu, true)
            LinkState.RECONNECTING -> StateBadge("重连中", pulse.loadWarm, true)
            LinkState.STALE -> StateBadge("数据滞后", pulse.loadWarm)
            LinkState.NEEDS_PAIRING -> StateBadge("待配对", pulse.gpu)
            LinkState.OFFLINE -> StateBadge("离线", null)
        }
    }
}

/** 双仪表：无卡片，垂直细线分隔的仪器面板 */
@Composable
private fun GaugeRow(snap: Snapshot?, state: LinkState, onOpenDetail: (String) -> Unit) {
    val pulse = LocalPulseColors.current
    val live = state == LinkState.ONLINE || state == LinkState.STALE
    val a = if (live) 1f else 0.45f

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).dim(a),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cpu = snap?.cpu
        val gpu = snap?.gpus?.firstOrNull()
        GaugeColumn(
            Modifier.weight(1f),
            title = "CPU",
            progress = (cpu?.usage?.toFloat())?.div(100f),
            peak = rememberPeak(cpu?.usage?.toFloat()),
            color = pulse.cpu,
            valueText = fmtPct(cpu?.usage),
            subText = listOfNotNull(
                cpu?.freqMhz?.let { String.format(java.util.Locale.US, "%.1fGHz", it / 1000) },
                cpu?.temp?.let { String.format(java.util.Locale.US, "%.0f°", it) },
            ).joinToString("  ").ifEmpty { null },
            spark = LocalCpuSeries.current,
            sparkColor = pulse.cpu,
            onClick = { onOpenDetail("cpu") },
        )
        VDivider(160)
        GaugeColumn(
            Modifier.weight(1f),
            title = "GPU",
            progress = (gpu?.usage?.toFloat())?.div(100f),
            peak = null,
            color = pulse.gpu,
            valueText = fmtPct(gpu?.usage),
            subText = gpu?.name?.takeIf { gpu.usage != null },
            spark = LocalGpuSeries.current,
            sparkColor = pulse.gpu,
            onClick = { onOpenDetail("gpu") },
        )
    }
}

@Composable
private fun GaugeColumn(
    modifier: Modifier,
    title: String,
    progress: Float?,
    peak: Float?,
    color: androidx.compose.ui.graphics.Color,
    valueText: String,
    subText: String?,
    spark: FloatArray,
    sparkColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val pulse = LocalPulseColors.current
    Column(modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Label, color = pulse.textSecondary)
            Spacer(Modifier.width(8.dp))
            subText?.let {
                Text(
                    it,
                    style = SmallNumber,
                    color = pulse.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            ArcGauge(progress, peak, color, Modifier.height(132.dp).fillMaxWidth(), strokeWidth = 9.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    valueText,
                    style = com.pulse.monitor.ui.theme.DisplayNumber,
                    color = if (progress == null) pulse.textTertiary else pulse.textPrimary,
                )
                if (progress == null) {
                    Text("不支持", style = Label, color = pulse.textTertiary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Sparkline(spark, sparkColor, Modifier.fillMaxWidth().height(32.dp), alpha = if (progress == null) 0.3f else 1f)
    }
}

@Composable
private fun MemorySection(snap: Snapshot?, state: LinkState, onOpenDetail: (String) -> Unit) {
    val pulse = LocalPulseColors.current
    val mem = snap?.mem
    val live = state == LinkState.ONLINE || state == LinkState.STALE
    MetricCard(
        title = "内存",
        accent = null,
        onClick = { onOpenDetail("mem") },
        modifier = Modifier.dim(if (live) 1f else 0.45f),
        trailing = {
            Text(
                fmtPct1(mem?.usagePct),
                style = MetricNumber, color = if (mem?.usagePct == null) pulse.textTertiary else pulse.mem,
            )
        },
    ) {
        Text(
            "${fmtMB(mem?.usedMB)} / ${fmtMB(mem?.totalMB)}",
            style = MetricNumber.copy(fontSize = 24.sp),
            color = if (mem?.usedMB == null) pulse.textTertiary else pulse.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        BarMeter((mem?.usagePct?.toFloat())?.div(100f), pulse.mem, Modifier.fillMaxWidth(), height = 6.dp)
        val commit = mem?.let { m ->
            if (m.commitUsedMB != null && m.commitLimitMB != null)
                "提交 ${fmtMB(m.commitUsedMB)} / ${fmtMB(m.commitLimitMB)}" else null
        }
        if (commit != null) {
            Spacer(Modifier.height(8.dp))
            Text(commit, style = SmallNumber, color = pulse.textTertiary)
        }
    }
}

@Composable
private fun NetworkSection(snap: Snapshot?, state: LinkState, onOpenDetail: (String) -> Unit) {
    val pulse = LocalPulseColors.current
    val net = snap?.net
    val live = state == LinkState.ONLINE || state == LinkState.STALE
    MetricCard(
        title = "网络",
        accent = null,
        onClick = { onOpenDetail("net") },
        modifier = Modifier.dim(if (live) 1f else 0.45f),
        trailing = {
            Text(net?.activeDesc ?: "", style = SmallNumber, color = pulse.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("↓", style = MetricNumber, color = pulse.netDown)
            Spacer(Modifier.width(6.dp))
            Text(fmtBps(net?.downloadBps), style = MetricNumber.copy(fontSize = 24.sp), color = pulse.textPrimary)
            Spacer(Modifier.width(24.dp))
            Text("↑", style = MetricNumber, color = pulse.netUp)
            Spacer(Modifier.width(6.dp))
            Text(fmtBps(net?.uploadBps), style = MetricNumber, color = pulse.textPrimary)
        }
        Spacer(Modifier.height(10.dp))
        Box {
            Sparkline(LocalDownSeries.current, pulse.netDown, Modifier.fillMaxWidth().height(42.dp))
            Sparkline(
                LocalUpSeries.current, pulse.netUp,
                Modifier.fillMaxWidth().height(42.dp),
                filled = false, strokeWidth = 1.5.dp,
            )
        }
    }
}

@Composable
private fun DiskSection(snap: Snapshot?, state: LinkState) {
    val pulse = LocalPulseColors.current
    val disks = snap?.disks
    if (disks.isNullOrEmpty()) return
    val live = state == LinkState.ONLINE || state == LinkState.STALE
    MetricCard(
        title = "磁盘",
        accent = null,
        modifier = Modifier.dim(if (live) 1f else 0.45f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            disks.forEachIndexed { _, d ->
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${d.drive} ${d.label ?: ""}".trim(),
                            style = MetricNumber, color = pulse.textPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${fmtMB(d.usedMB)} / ${fmtMB(d.totalMB)}",
                            style = SmallNumber, color = pulse.textTertiary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(fmtPct(d.usagePct), style = MetricNumber, color = pulse.disk)
                    }
                    Spacer(Modifier.height(6.dp))
                    BarMeter((d.usagePct?.toFloat())?.div(100f), pulse.disk, Modifier.fillMaxWidth(), height = 5.dp)
                    val rates = listOfNotNull(
                        d.readBps?.takeIf { it > 1024 }?.let { "读 ${fmtBps(it)}" },
                        d.writeBps?.takeIf { it > 1024 }?.let { "写 ${fmtBps(it)}" },
                    ).joinToString("   ")
                    if (rates.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(rates, style = SmallNumber, color = pulse.textTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteSection(state: LinkState, onOpenRemote: () -> Unit) {
    val pulse = LocalPulseColors.current
    MetricCard(
        title = "遥控",
        accent = null,
        onClick = onOpenRemote,
        modifier = Modifier.dim(if (state == LinkState.ONLINE || state == LinkState.STALE) 1f else 0.45f),
        trailing = { Text("触控板 · 键盘 ›", style = SmallNumber, color = pulse.cpu) },
    ) {
        Text("把手机变成这台电脑的触控板和键盘", style = SmallNumber, color = pulse.textTertiary)
    }
}

@Composable
private fun FooterInfo(snap: Snapshot?) {
    val pulse = LocalPulseColors.current
    val sys = snap?.sys
    val parts = buildList {
        add("运行 ${fmtUptime(snap?.uptimeSec)}")
        sys?.agent?.let { add("Agent v$it") }
        sys?.ip?.let { add(it) }
    }
    Text(
        parts.joinToString("   ·   "),
        style = SmallNumber,
        color = pulse.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        textAlign = TextAlign.Center,
    )
}

// 峰值记忆（幽灵刻度）
private var cpuPeakCache = 0f
private fun rememberPeak(v: Float?): Float? {
    if (v == null) return null
    if (v > cpuPeakCache) cpuPeakCache = v
    return cpuPeakCache
}

val LocalCpuSeries = androidx.compose.runtime.staticCompositionLocalOf<FloatArray> { FloatArray(0) }
val LocalGpuSeries = androidx.compose.runtime.staticCompositionLocalOf<FloatArray> { FloatArray(0) }
val LocalDownSeries = androidx.compose.runtime.staticCompositionLocalOf<FloatArray> { FloatArray(0) }
val LocalUpSeries = androidx.compose.runtime.staticCompositionLocalOf<FloatArray> { FloatArray(0) }
