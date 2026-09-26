package com.pulse.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.monitor.data.connection.DeviceConnection
import com.pulse.monitor.data.connection.LinkState
import com.pulse.monitor.data.connection.Series
import com.pulse.monitor.ui.charts.AreaChart
import com.pulse.monitor.ui.charts.BarMeter
import com.pulse.monitor.ui.charts.CoreBars
import com.pulse.monitor.ui.common.MetricCard
import com.pulse.monitor.ui.common.StateBadge
import com.pulse.monitor.ui.common.StatChip
import com.pulse.monitor.ui.common.UnsupportedValue
import com.pulse.monitor.ui.common.fmtBps
import com.pulse.monitor.ui.common.fmtFreqMhz
import com.pulse.monitor.ui.common.fmtMB
import com.pulse.monitor.ui.common.fmtPct
import com.pulse.monitor.ui.common.fmtPct1
import com.pulse.monitor.ui.common.fmtTemp
import com.pulse.monitor.ui.common.fmtUptime
import com.pulse.monitor.ui.theme.Label
import com.pulse.monitor.ui.theme.LocalPulseColors
import com.pulse.monitor.ui.theme.MetricNumber
import com.pulse.monitor.ui.theme.SmallNumber
import kotlinx.coroutines.delay

/** 1 / 5 / 30 分钟时间窗 */
enum class Range(val seconds: Int, val label: String) {
    M1(60, "1分钟"), M5(300, "5分钟"), M30(1800, "30分钟")
}

/** 从环形缓冲截取窗口序列（按时间戳过滤，间隔自适应） */
private fun window(series: Series, seconds: Int): FloatArray {
    val (vals, stamps) = series.snapshot()
    if (vals.isEmpty()) return FloatArray(0)
    val cutoff = stamps.last() - seconds * 1000L
    var from = 0
    while (from < stamps.size && stamps[from] < cutoff) from++
    return vals.copyOfRange(from, vals.size)
}

@Composable
fun DetailHeader(title: String, accentColor: androidx.compose.ui.graphics.Color, state: LinkState, onBack: () -> Unit) {
    val pulse = LocalPulseColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pulse.textPrimary) }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = accentColor)
        Spacer(Modifier.weight(1f))
        when (state) {
            LinkState.ONLINE -> StateBadge("在线", pulse.online, true)
            LinkState.RECONNECTING -> StateBadge("重连中", pulse.loadWarm, true)
            LinkState.STALE -> StateBadge("数据滞后", pulse.loadWarm)
            LinkState.NEEDS_PAIRING -> StateBadge("待配对", pulse.gpu)
            else -> StateBadge("离线", null)
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
fun RangeTabs(selected: Range, onSelect: (Range) -> Unit) {
    val pulse = LocalPulseColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Range.entries.forEach { r ->
            val active = r == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) pulse.cpu.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.dp, if (active) androidx.compose.ui.graphics.Color.Transparent else pulse.hairline, RoundedCornerShape(10.dp))
                    .clickable { onSelect(r) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(r.label, style = Label, color = if (active) pulse.cpu else pulse.textSecondary)
            }
        }
    }
}

// ---------------- CPU ----------------

@Composable
fun CpuDetail(connection: DeviceConnection, onBack: () -> Unit) {
    val pulse = LocalPulseColors.current
    val snap by connection.snap.collectAsState()
    val state by connection.state.collectAsState()
    var range by remember { mutableStateOf(Range.M5) }
    // 60Hz 轻刷新驱动图表窗口增长
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    Column(
            Modifier
                .fillMaxSize()
                .background(pulse.bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
        DetailHeader("CPU", pulse.cpu, state, onBack)
        val cpu = snap?.cpu
        StatTriple(
            Triple("当前占用", fmtPct1(cpu?.usage), pulse.cpu),
            Triple("频率", cpu?.freqMhz?.let { fmtFreqMhz(it) }, null),
            Triple("温度", cpu?.temp?.let { "%.0f°C".format(java.util.Locale.US, it) }, cpu?.temp?.let { pulse.temp }),
        )
        MetricCard(title = "使用率", accent = pulse.cpu, modifier = Modifier) {
            RangeTabs(range, { range = it })
            Spacer(Modifier.height(12.dp))
            key(tick, range) {
                AreaChart(window(connection.cpuHist, range.seconds), pulse.cpu, Modifier.fillMaxWidth().height(150.dp), yMax = 100f)
            }
        }
        MetricCard(
            title = "每核使用率",
            accent = pulse.cpu,
            modifier = Modifier,
            trailing = { cpu?.coresLogical?.let { Text("${it} 线程", style = SmallNumber, color = pulse.textTertiary) } },
        ) {
            val cores = cpu?.perCore
            if (cores == null) {
                UnsupportedValue()
            } else {
                CoreBars(cores.map { it.toFloat() }, pulse.cpu, Modifier.fillMaxWidth().height(150.dp))
            }
        }
        MetricCard(title = "处理器", accent = null, modifier = Modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("型号", cpu?.name)
                InfoRow("线程数", cpu?.coresLogical?.toString())
                InfoRow("系统运行时间", fmtUptime(snap?.uptimeSec))
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun InfoTile(label: String, value: String?, color: androidx.compose.ui.graphics.Color?, modifier: Modifier = Modifier) {
    val pulse = LocalPulseColors.current
    Column(modifier) {
        Text(label, style = Label, color = pulse.textSecondary)
        Spacer(Modifier.height(6.dp))
        if (value == null) UnsupportedValue()
        else Text(value, style = MetricNumber.copy(fontSize = 18.sp), color = color ?: pulse.textPrimary, maxLines = 1)
    }
}

/** 三联统计行：列间垂直细线，无卡片 */
@Composable
private fun StatTriple(
    a: Triple<String, String?, androidx.compose.ui.graphics.Color?>,
    b: Triple<String, String?, androidx.compose.ui.graphics.Color?>,
    c: Triple<String, String?, androidx.compose.ui.graphics.Color?>,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoTile(a.first, a.second, a.third, Modifier.weight(1f))
        com.pulse.monitor.ui.common.VDivider(52)
        InfoTile(b.first, b.second, b.third, Modifier.weight(1f).padding(start = 16.dp))
        com.pulse.monitor.ui.common.VDivider(52)
        InfoTile(c.first, c.second, c.third, Modifier.weight(1f).padding(start = 16.dp))
    }
    com.pulse.monitor.ui.common.Hairline()
}

@Composable
private fun InfoRow(label: String, value: String?) {
    val pulse = LocalPulseColors.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = SmallNumber, color = pulse.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value ?: "—", style = SmallNumber, color = pulse.textPrimary)
    }
}

// ---------------- GPU ----------------

@Composable
fun GpuDetail(connection: DeviceConnection, onBack: () -> Unit) {
    val pulse = LocalPulseColors.current
    val snap by connection.snap.collectAsState()
    val state by connection.state.collectAsState()
    var range by remember { mutableStateOf(Range.M5) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    val gpus = snap?.gpus
    var selected by remember { mutableIntStateOf(0) }
    val gpu = gpus?.getOrNull(selected.coerceAtMost((gpus.size - 1).coerceAtLeast(0)))

    Column(
            Modifier
                .fillMaxSize()
                .background(pulse.bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
        DetailHeader("GPU", pulse.gpu, state, onBack)
        if (gpus == null || gpus.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无可用显卡数据", style = MetricNumber, color = pulse.textSecondary)
                Spacer(Modifier.height(6.dp))
                Text("未检测到显卡或系统不支持", style = SmallNumber, color = pulse.textTertiary)
            }
            return@Column
        }
        if (gpus.size > 1) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gpus.forEachIndexed { i, g ->
                    val active = i == selected
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) pulse.gpu.copy(alpha = 0.15f) else pulse.surfaceHigh)
                            .clickable { selected = i }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(g.name ?: "显卡 ${i + 1}", style = Label, color = if (active) pulse.gpu else pulse.textSecondary)
                    }
                }
            }
        }
        gpu?.let { g ->
            StatTriple(
                Triple("当前占用", fmtPct1(g.usage), pulse.gpu),
                Triple("温度", g.temp?.let { "%.0f°C".format(java.util.Locale.US, it) }, g.temp?.let { pulse.temp }),
                Triple("功耗", g.powerW?.let { "%.1f W".format(java.util.Locale.US, it) }, null),
            )
            MetricCard(title = "使用率", accent = pulse.gpu, modifier = Modifier) {
                RangeTabs(range, { range = it })
                Spacer(Modifier.height(12.dp))
                key(tick, range) {
                    AreaChart(window(connection.gpuHist, range.seconds), pulse.gpu, Modifier.fillMaxWidth().height(150.dp), yMax = 100f)
                }
            }
            MetricCard(title = "显存", accent = pulse.gpu, modifier = Modifier) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${fmtMB(g.memUsedMB)} / ${g.memTotalMB?.let { fmtMB(it) } ?: "未知"}", style = MetricNumber.copy(fontSize = 20.sp), color = pulse.textPrimary)
                    Spacer(Modifier.weight(1f))
                    g.memUsedMB?.let { used ->
                        g.memTotalMB?.let { total ->
                            if (total > 0) Text(fmtPct(used / total * 100), style = MetricNumber, color = pulse.gpu)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val frac = if (g.memUsedMB != null && g.memTotalMB != null && g.memTotalMB > 0)
                    (g.memUsedMB / g.memTotalMB).toFloat() else null
                BarMeter(frac, pulse.gpu, Modifier.fillMaxWidth())
                g.memSharedUsedMB?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("共享显存 ${fmtMB(it)}", style = SmallNumber, color = pulse.textTertiary)
                }
            }
            MetricCard(title = "显卡", accent = null, modifier = Modifier) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("型号", g.name)
                    InfoRow("厂商", g.vendor)
                    InfoRow("核心频率", g.clockMhz?.let { fmtFreqMhz(it) })
                    InfoRow("标识", g.id)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------- 内存 ----------------

@Composable
fun MemDetail(connection: DeviceConnection, onBack: () -> Unit) {
    val pulse = LocalPulseColors.current
    val snap by connection.snap.collectAsState()
    val state by connection.state.collectAsState()
    var range by remember { mutableStateOf(Range.M5) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val mem = snap?.mem

    Column(
            Modifier
                .fillMaxSize()
                .background(pulse.bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
        DetailHeader("内存", pulse.mem, state, onBack)
        StatTriple(
            Triple("已用", fmtMB(mem?.usedMB), pulse.mem),
            Triple("可用", fmtMB(mem?.availableMB), null),
            Triple("占用", fmtPct1(mem?.usagePct), pulse.mem),
        )
        MetricCard(title = "使用率", accent = pulse.mem, modifier = Modifier) {
            RangeTabs(range, { range = it })
            Spacer(Modifier.height(12.dp))
            key(tick, range) {
                AreaChart(window(connection.memHist, range.seconds), pulse.mem, Modifier.fillMaxWidth().height(150.dp), yMax = 100f)
            }
        }
        MetricCard(title = "明细", accent = null, modifier = Modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("物理内存总量", mem?.totalMB?.let { fmtMB(it) })
                InfoRow("已使用", mem?.usedMB?.let { fmtMB(it) })
                InfoRow("可用", mem?.availableMB?.let { fmtMB(it) })
                InfoRow("已提交 (Commit)", mem?.commitUsedMB?.let { fmtMB(it) })
                InfoRow("提交上限", mem?.commitLimitMB?.let { fmtMB(it) })
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------- 网络 ----------------

@Composable
fun NetDetail(connection: DeviceConnection, onBack: () -> Unit) {
    val pulse = LocalPulseColors.current
    val snap by connection.snap.collectAsState()
    val state by connection.state.collectAsState()
    var range by remember { mutableStateOf(Range.M5) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val net = snap?.net

    Column(
            Modifier
                .fillMaxSize()
                .background(pulse.bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
        DetailHeader("网络", pulse.netDown, state, onBack)
        StatTriple(
            Triple("下行", fmtBps(net?.downloadBps), pulse.netDown),
            Triple("上行", fmtBps(net?.uploadBps), pulse.netUp),
            Triple("活动网卡", net?.active, null),
        )
        MetricCard(title = "下行流量", accent = pulse.netDown, modifier = Modifier) {
            RangeTabs(range, { range = it })
            Spacer(Modifier.height(12.dp))
            key(tick, range) {
                AreaChart(window(connection.downHist, range.seconds), pulse.netDown, Modifier.fillMaxWidth().height(120.dp))
            }
        }
        MetricCard(title = "上行流量", accent = pulse.netUp, modifier = Modifier) {
            key(tick, range) {
                AreaChart(window(connection.upHist, range.seconds), pulse.netUp, Modifier.fillMaxWidth().height(120.dp))
            }
        }
        MetricCard(title = "网卡", accent = null, modifier = Modifier) {
            val ifaces = net?.interfaces
            if (ifaces.isNullOrEmpty()) {
                UnsupportedValue()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ifaces.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(6.dp).height(6.dp).background(if (f.active) pulse.netDown else pulse.textTertiary.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.desc ?: f.name, style = SmallNumber, color = if (f.active) pulse.textPrimary else pulse.textSecondary, maxLines = 1)
                            }
                            Text(
                                "↓ ${fmtBps(f.downBps)}   ↑ ${fmtBps(f.upBps)}",
                                style = SmallNumber,
                                color = if (f.active) pulse.netDown else pulse.textTertiary,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
