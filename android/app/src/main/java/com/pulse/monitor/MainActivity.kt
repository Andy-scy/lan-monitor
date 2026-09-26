package com.pulse.monitor

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pulse.monitor.data.connection.DeviceConnection
import com.pulse.monitor.data.discovery.PairingApi
import com.pulse.monitor.data.store.StoredDevice
import com.pulse.monitor.ui.DashboardScreen
import com.pulse.monitor.ui.DevicesScreen
import com.pulse.monitor.ui.CpuDetail
import com.pulse.monitor.ui.GpuDetail
import com.pulse.monitor.ui.MemDetail
import com.pulse.monitor.ui.NetDetail
import com.pulse.monitor.ui.RemoteControlScreen
import com.pulse.monitor.ui.LocalCpuSeries
import com.pulse.monitor.ui.LocalDownSeries
import com.pulse.monitor.ui.LocalGpuSeries
import com.pulse.monitor.ui.LocalUpSeries
import com.pulse.monitor.ui.theme.PulseTheme
import com.pulse.monitor.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("pulse_ui", MODE_PRIVATE) }
    private val pipMode = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        addOnPictureInPictureModeChangedListener { info -> pipMode.value = info.isInPictureInPictureMode }
        val app = application as PulseApp
        val initialTheme = ThemeMode.valueOf(prefs.getString("theme", "SYSTEM") ?: "SYSTEM")

        setContent {
            var themeMode by remember { mutableStateOf(initialTheme) }
            PulseTheme(themeMode) {
                AppRoot(
                    app = app,
                    themeMode = themeMode,
                    pipMode = pipMode,
                    onCycleTheme = {
                        val next = when (themeMode) {
                            ThemeMode.SYSTEM -> ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.LIGHT
                            ThemeMode.LIGHT -> ThemeMode.SYSTEM
                        }
                        themeMode = next
                        prefs.edit().putString("theme", next.name).apply()
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as PulseApp).pauseAll()
    }
}

private sealed interface Screen {
    data object Devices : Screen
    data class Dashboard(val deviceId: String) : Screen
    data class Detail(val deviceId: String, val metric: String) : Screen
    data class Remote(val deviceId: String) : Screen
}

@Composable
private fun AppRoot(app: PulseApp, themeMode: ThemeMode, pipMode: kotlinx.coroutines.flow.StateFlow<Boolean>, onCycleTheme: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by app.store.devices.collectAsState(initial = emptyList())
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Devices)) }
    var toast by remember { mutableStateOf<String?>(null) }

    val current = stack.last()
    val back: () -> Unit = {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.size > 1, onBack = back)

    // Android 14+ 预测式返回：手势期间当前屏缩放+随边滑出，提交才真正返回
    var backProgress by remember { mutableStateOf(0f) }
    var backEdge by remember { mutableStateOf(0) }
    PredictiveBackHandler(enabled = stack.size > 1) { flow ->
        try {
            flow.collect { ev ->
                backProgress = ev.progress
                backEdge = ev.swipeEdge
            }
            backProgress = 0f
            back()
        } catch (e: Exception) {
            backProgress = 0f // 手势取消，复位
        }
    }

    // 回到前台时恢复当前设备的 WS；退后台由 MainActivity.onStop 统一暂停
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                val ids = when (val s = stack.last()) {
                    is Screen.Dashboard -> listOf(s.deviceId)
                    is Screen.Detail -> listOf(s.deviceId)
                    else -> devices.map { it.id }
                }
                app.resumeActive(ids)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 免 PIN 自动配对：发现未持有 token 的设备即静默领取
    androidx.compose.runtime.LaunchedEffect(devices) {
        for (d in devices) {
            if (d.token == null) {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PairingApi.pair(d.host, d.port, "")
                }
                if (r is PairingApi.Result.Ok) {
                    app.store.upsert(d.copy(token = r.token, name = r.name ?: d.name))
                }
            }
        }
    }

    // 屏幕切换或设备列表更新（如配对完成）时刷新活跃连接
    androidx.compose.runtime.LaunchedEffect(current, devices) {
        val ids = when (val s = current) {
            is Screen.Dashboard -> listOf(s.deviceId)
            is Screen.Detail -> listOf(s.deviceId)
            else -> devices.map { it.id }
        }
        app.resumeActive(ids)
    }

    val pip by pipMode.collectAsState()

    fun device(id: String): StoredDevice? = devices.firstOrNull { it.id == id }
    fun conn(id: String): DeviceConnection? = device(id)?.let { app.connectionFor(it) }

    // 手动添加（或重新发现更新 host）
    val addManual: (String, Int, Boolean) -> Unit = { host, port, silent ->
        scope.launch {
            val c = DeviceConnection(UUID.randomUUID().toString(), host, host, port, null)
            when (val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.helloProbe() }) {
                is com.pulse.monitor.data.connection.HelloResult.Online -> {
                    val h = r.hello
                    val existing = app.store.all().firstOrNull { it.id == h.id }
                    val stored = StoredDevice(
                        id = h.id,
                        name = existing?.name ?: (h.host.ifBlank { host }),
                        host = host,
                        port = port,
                        token = existing?.token,
                        os = h.os,
                        lastSeen = System.currentTimeMillis(),
                    )
                    app.store.upsert(stored)
                    c.destroy()
                }
                else -> {
                    c.destroy()
                    if (!silent) toast = "无法连接 $host:$port"
                }
            }
        }
    }

    val pair: (String, String) -> Unit = { id, pin ->
        scope.launch {
            val d = app.store.find(id) ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PairingApi.pair(d.host, d.port, pin)
            }.let { r ->
                when (r) {
                    is PairingApi.Result.Ok -> {
                        app.store.upsert(d.copy(token = r.token, name = r.name ?: d.name))
                        app.connectionFor(d.copy(token = r.token)).markPaired(r.token)
                        toast = "配对成功"
                    }
                    PairingApi.Result.WrongPin -> toast = "PIN 不正确"
                    PairingApi.Result.Unreachable -> toast = "无法连接电脑"
                }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1f - 0.08f * backProgress
            scaleY = 1f - 0.08f * backProgress
            alpha = 1f - 0.25f * backProgress
            translationX = (if (backEdge == 0) 1f else -1f) * backProgress * 150f
        }
    ) {
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            val dir = if (targetState is Screen.Devices) -1 else 1
            (slideInHorizontally { it / 3 * dir } + fadeIn()) togetherWith
                (slideOutHorizontally { -it / 3 * dir } + fadeOut())
        },
        label = "nav",
    ) { s ->
        when (s) {
            is Screen.Devices -> {
                val conns = devices.associate { it.id to app.connectionFor(it) }
                DevicesScreen(
                    devices = devices,
                    connections = conns,
                    onOpen = { id ->
                        if (device(id) != null) stack = stack + Screen.Dashboard(id)
                    },
                    onAddManual = { host, port -> addManual(host, port, false) },
                    onAddDiscovered = { host, port -> addManual(host, port, true) },
                    onPair = { id, pin -> pair(id, pin) },
                    onForget = { id ->
                        app.forget(id)
                        toast = "已移除设备"
                    },
                    themeMode = themeMode,
                    onCycleTheme = onCycleTheme,
                )
            }
            is Screen.Dashboard -> {
                val c = conn(s.deviceId)
                if (c == null) {
                    stack = listOf(Screen.Devices)
                } else {
                    ProvideSeries(c) {
                        DashboardScreen(
                            connection = c,
                            onBack = back,
                            onOpenDetail = { metric -> stack = stack + Screen.Detail(s.deviceId, metric) },
                            onOpenRemote = { stack = stack + Screen.Remote(s.deviceId) },
                        )
                    }
                }
            }
            is Screen.Detail -> {
                val c = conn(s.deviceId)
                if (c == null) {
                    stack = listOf(Screen.Devices)
                } else {
                    when (s.metric) {
                        "cpu" -> CpuDetail(c, back)
                        "gpu" -> GpuDetail(c, back)
                        "mem" -> MemDetail(c, back)
                        "net" -> NetDetail(c, back)
                        else -> CpuDetail(c, back)
                    }
                }
            }
            is Screen.Remote -> {
                val c = conn(s.deviceId)
                if (c == null) {
                    stack = listOf(Screen.Devices)
                } else {
                    RemoteControlScreen(c, back, pip)
                }
            }
        }
    }

    // 轻提示
    toast?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toast = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { toast = null }) {
                    androidx.compose.material3.Text("好的")
                }
            },
            title = { androidx.compose.material3.Text(msg) },
        )
    }
    }
}

/** 把连接的历史序列以快照形式喂给图表（每秒由数据流触发重组） */
@Composable
private fun ProvideSeries(connection: DeviceConnection, content: @Composable () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(connection.token) {
        if (connection.token != null) connection.start()
    }
    val snap by connection.snap.collectAsState()
    val cpuSeries = remember(snap) { connection.cpuHist.last(300) }
    val gpuSeries = remember(snap) { connection.gpuHist.last(300) }
    val downSeries = remember(snap) { connection.downHist.last(300) }
    val upSeries = remember(snap) { connection.upHist.last(300) }
    CompositionLocalProvider(
        LocalCpuSeries provides cpuSeries,
        LocalGpuSeries provides gpuSeries,
        LocalDownSeries provides downSeries,
        LocalUpSeries provides upSeries,
        content = content,
    )
}
