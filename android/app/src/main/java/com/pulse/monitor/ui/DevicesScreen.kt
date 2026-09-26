package com.pulse.monitor.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.monitor.data.connection.DeviceConnection
import com.pulse.monitor.data.connection.LinkState
import com.pulse.monitor.data.discovery.PairingApi
import com.pulse.monitor.data.store.StoredDevice
import com.pulse.monitor.ui.common.StateBadge
import com.pulse.monitor.ui.common.StatusDot
import com.pulse.monitor.ui.theme.LocalPulseColors
import com.pulse.monitor.ui.theme.Label
import com.pulse.monitor.ui.theme.MetricNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DevicesScreen(
    devices: List<StoredDevice>,
    connections: Map<String, DeviceConnection>,
    onOpen: (String) -> Unit,
    onAddManual: (host: String, port: Int) -> Unit,
    onAddDiscovered: (host: String, port: Int) -> Unit,
    onPair: (id: String, pin: String) -> Unit,
    onForget: (String) -> Unit,
    themeMode: com.pulse.monitor.ui.theme.ThemeMode,
    onCycleTheme: () -> Unit,
) {
    val pulse = LocalPulseColors.current
    var scanning by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<com.pulse.monitor.data.discovery.Discovery.Found>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var pairingDevice by remember { mutableStateOf<StoredDevice?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(devices.size) {
        while (isActive) {
            scanning = true
            // 任何异常都不能杀死扫描循环
            found = runCatching { com.pulse.monitor.data.discovery.Discovery.scan(context) }
                .getOrDefault(emptyList())
            scanning = false
            // 有设备后降频扫描，省电
            delay(if (devices.isEmpty()) 6000 else 30000)
        }
    }

    // 把发现到的设备写回存储（去重）
    LaunchedEffect(found) {
        for (f in found) {
            val existing = devices.firstOrNull { it.id == f.hello.id }
            if (existing == null) {
                onAddDiscovered(f.address, f.hello.port)
            }
        }
    }

    // 列表页轻量摘要轮询：已配对且未在 WS 在线的设备每 3s 拉一次
    LaunchedEffect(devices) {
        while (true) {
            for (d in devices) {
                val c = connections[d.id] ?: continue
                if (c.state.value !in setOf(LinkState.ONLINE, LinkState.STALE)) {
                    if (d.token != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        c.fetchOnce()
                    }
                }
            }
            delay(3000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pulse.bg)
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Pulse", fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = pulse.textPrimary)
                Text("局域网硬件监控", style = Label, color = pulse.textTertiary)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCycleTheme) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "主题切换",
                    tint = pulse.textSecondary,
                )
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            if (devices.isEmpty()) {
                item { RadarScanning(Modifier.fillMaxWidth().height(220.dp)) }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在搜索局域网设备…", style = MetricNumber, color = pulse.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "确保电脑已运行 start.bat，\n且手机与电脑连接同一 Wi-Fi",
                            style = com.pulse.monitor.ui.theme.Body,
                            color = pulse.textTertiary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            items(devices, key = { it.id }) { d ->
                DeviceCard(
                    device = d,
                    connection = connections[d.id],
                    onClick = { onOpen(d.id) },
                    onPairClick = { pairingDevice = d },
                    onForget = { onForget(d.id) },
                )
            }
            if (found.isNotEmpty() && devices.isNotEmpty()) {
                item {
                    Text(
                        "发现 ${found.size} 台设备 · 已自动添加",
                        style = Label, color = pulse.textTertiary,
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 24.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        // 底部：手动添加
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding()) {
            androidx.compose.material3.OutlinedButton(
                onClick = { showAdd = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("手动添加设备（IP + 端口）")
            }
        }
    }

    if (showAdd) {
        ManualAddDialog(
            onDismiss = { showAdd = false },
            onConfirm = { host, port ->
                showAdd = false
                onAddManual(host, port)
            },
        )
    }

    pairingDevice?.let { pd ->
        PinDialog(
            title = "配对 ${pd.name}",
            onDismiss = { pairingDevice = null },
            onConfirm = { pin ->
                onPair(pd.id, pin)
                pairingDevice = null
            },
        )
    }
}

@Composable
private fun RadarScanning(modifier: Modifier = Modifier) {
    val pulse = LocalPulseColors.current
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "sweep",
    )
    val ripple by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "ripple",
    )
    Canvas(modifier.padding(top = 24.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = minOf(size.width, size.height) / 2 * 0.9f
        // 同心圆
        for (f in listOf(0.33f, 0.66f, 1f)) {
            drawCircle(pulse.textTertiary.copy(alpha = 0.18f), r * f, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
        // 扩散波
        drawCircle(
            pulse.cpu.copy(alpha = (1 - ripple) * 0.35f),
            r * ripple,
            Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
        // 扫描扇形
        drawArc(
            brush = Brush.sweepGradient(
                listOf(Color.Transparent, pulse.cpu.copy(alpha = 0.30f)),
            ),
            startAngle = sweep - 70,
            sweepAngle = 70f,
            useCenter = true,
            topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
        )
        // 中心点
        drawCircle(pulse.cpu, 5.dp.toPx(), Offset(cx, cy))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCard(
    device: StoredDevice,
    connection: DeviceConnection?,
    onClick: () -> Unit,
    onPairClick: () -> Unit,
    onForget: () -> Unit,
) {
    val pulse = LocalPulseColors.current
    val state by connection?.state?.collectAsState() ?: remember { mutableStateOf(LinkState.OFFLINE) }
    val snap by connection?.snap?.collectAsState() ?: remember { mutableStateOf(null) }

    val (dotColor, breathing, badge) = when (state) {
        LinkState.ONLINE -> Triple(pulse.online, true, "在线")
        LinkState.STALE -> Triple(pulse.loadWarm, false, "数据滞后")
        LinkState.CONNECTING -> Triple(pulse.cpu, true, "连接中")
        LinkState.RECONNECTING -> Triple(pulse.loadWarm, true, "重连中")
        LinkState.NEEDS_PAIRING -> Triple(pulse.gpu, false, "待配对")
        LinkState.OFFLINE -> Triple(null, false, "离线")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onForget)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dotColor, 11, breathing)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = pulse.textPrimary)
                Text(
                    listOfNotNull(device.os, "${device.host}:${device.port}").joinToString(" · "),
                    style = com.pulse.monitor.ui.theme.SmallNumber,
                    color = pulse.textTertiary,
                )
            }
            StateBadge(badge, dotColor, breathing)
        }
        val s = snap
        if (s != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                MiniStat("CPU", s.cpu?.usage, pulse.cpu)
                MiniStat("GPU", s.gpus?.firstOrNull()?.usage, pulse.gpu)
                MiniStat("内存", s.mem?.usagePct, pulse.mem)
                MiniStat("↓ 网络", s.net?.downloadBps?.let { it / 1048576.0 }, pulse.netDown, suffix = "MB/s")
            }
        }
        if (state == LinkState.NEEDS_PAIRING) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onPairClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pulse.cpu.copy(alpha = 0.15f), contentColor = pulse.cpu),
            ) { Text("输入 PIN 配对") }
        }
    }
    com.pulse.monitor.ui.common.Hairline()
}

@Composable
private fun MiniStat(label: String, value: Double?, color: Color, suffix: String = "%") {
    Column {
        Text(label, style = Label, color = LocalPulseColors.current.textTertiary)
        Spacer(Modifier.height(3.dp))
        androidx.compose.material3.Text(
            when {
                value == null -> "—"
                suffix == "%" -> String.format(java.util.Locale.US, "%.0f%%", value)
                else -> String.format(java.util.Locale.US, "%.1f$suffix", value)
            },
            style = MetricNumber,
            color = if (value == null) LocalPulseColors.current.textTertiary else color,
        )
    }
}

@Composable
private fun ManualAddDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("42710") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加设备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("电脑 IP") }, singleLine = true,
                    placeholder = { Text("192.168.1.100") })
                OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (host.isNotBlank()) onConfirm(host.trim(), port.toIntOrNull() ?: 42710) }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("在电脑端 status.json 或启动窗口中查看 6 位 PIN", style = com.pulse.monitor.ui.theme.Body, color = LocalPulseColors.current.textSecondary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(pin, {
                    if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it
                }, label = { Text("PIN") }, singleLine = true)
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = com.pulse.monitor.ui.theme.SmallNumber)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.length == 6) onConfirm(pin) else error = "请输入 6 位数字 PIN"
            }) { Text("配对") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
