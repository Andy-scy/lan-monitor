package com.pulse.monitor.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.monitor.data.connection.DeviceConnection
import com.pulse.monitor.data.connection.InputChannel
import com.pulse.monitor.ui.common.Hairline
import com.pulse.monitor.ui.common.StateBadge
import com.pulse.monitor.ui.theme.Label
import com.pulse.monitor.ui.theme.LocalPulseColors
import com.pulse.monitor.ui.theme.SmallNumber
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 遥控屏：上半 = 触控板手势区，下半 = 文本注入 + 快捷键条。
 * 手势：单指移动/点按左键/双击左键/长按拖拽（按住左键）/双指滑动滚动/双指点按右键。
 */
@Composable
fun RemoteControlScreen(connection: DeviceConnection, onBack: () -> Unit) {
    val pulse = LocalPulseColors.current
    val state by connection.state.collectAsState()
    val host = connection.host
    val port = connection.port
    val token = connection.token

    val channel = remember(host, port, token) {
        InputChannel(host, port, token)
    }
    var showScreen by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    if (showScreen) {
        LaunchedEffect(showScreen, host, port, token) {
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            withContext(Dispatchers.IO) {
                while (isActive) {
                    val t = token ?: break
                    runCatching {
                        val req = Request.Builder().url("http://$host:$port/api/screen?token=$t").build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.bytes()?.let { bytes ->
                                    if (bytes.size > 100) {
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            ?.let { frame = it.asImageBitmap() }
                                    }
                                }
                            }
                        }
                    }
                    delay(40)
                }
            }
        }
    }
    LaunchedEffect(connection) { if (connection.token != null) connection.start() }

    Column(
        Modifier
            .fillMaxSize()
            .background(pulse.bg)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                channel.close()
                onBack()
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pulse.textPrimary) }
            Column(Modifier.weight(1f)) {
                Text("遥控", fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = pulse.textPrimary)
                Text("触控板 · 键盘 → ${connection.displayName}", style = SmallNumber, color = pulse.textTertiary)
            }
            Text(
                if (showScreen) "屏幕 ✓" else "屏幕",
                style = Label,
                color = if (showScreen) pulse.cpu else pulse.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showScreen = !showScreen }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            when (state) {
                com.pulse.monitor.data.connection.LinkState.ONLINE -> StateBadge("在线", pulse.online, true)
                com.pulse.monitor.data.connection.LinkState.RECONNECTING -> StateBadge("重连中", pulse.loadWarm, true)
                com.pulse.monitor.data.connection.LinkState.STALE -> StateBadge("数据滞后", pulse.loadWarm)
                else -> StateBadge("离线", null)
            }
        }
        Hairline()

        AnimatedVisibility(visible = showScreen) {
            ScreenPanel(frame)
        }

        // 触控板手势区
        Touchpad(
            channel = channel,
            connected = state == com.pulse.monitor.data.connection.LinkState.ONLINE,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // 文本注入
        TextKeyInput(channel, enabled = state == com.pulse.monitor.data.connection.LinkState.ONLINE)
    }
}

// ---------- 屏幕镜像面板 ----------

@Composable
private fun ScreenPanel(frame: ImageBitmap?) {
    val pulse = LocalPulseColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(pulse.surface)
            .border(1.dp, pulse.hairline, RoundedCornerShape(14.dp))
    ) {
        if (frame != null) {
            Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(
                "正在连接电脑屏幕…",
                style = SmallNumber,
                color = pulse.textTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

// ---------- 触控板 ----------

private class TapState {
    var at = 0L
    var x = 0f
    var y = 0f
}

@Composable
private fun Touchpad(channel: InputChannel, connected: Boolean, modifier: Modifier = Modifier) {
    val pulse = LocalPulseColors.current
    // 双击检测状态（跨手势保留）
    val tapState = remember { TapState() }

    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(pulse.surface)
            .border(1.dp, pulse.hairline, RoundedCornerShape(14.dp))
            .pointerInput(channel, connected) {
                if (!connected) return@pointerInput
                val sensitivity = 1.9f
                val touchSlop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var travel = 0f
                    var multi = false
                    var scrolled = false
                    var btnHeld = false
                    var lastSendX = 0f
                    var lastSendY = 0f
                    var scrollAcc = 0f
                    val startTime = down.uptimeMillis

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val nowMs = event.changes.maxOfOrNull { it.uptimeMillis } ?: startTime
                        if (pressed.isEmpty()) {
                            if (btnHeld) channel.button(0, false)
                            val elapsed = nowMs - startTime
                            // 单指快速点按 → 左键（带双击检测）
                            if (!multi && !btnHeld && travel < touchSlop * 2 && elapsed < 240) {
                                val wall = System.currentTimeMillis()
                                val isDouble = wall - tapState.at < 350 &&
                                    abs(down.position.x - tapState.x) < 60 &&
                                    abs(down.position.y - tapState.y) < 60
                                tapState.at = wall
                                tapState.x = down.position.x
                                tapState.y = down.position.y
                                channel.click(0, isDouble)
                            }
                            // 双指快速点按 → 右键
                            if (multi && !scrolled && elapsed < 280) {
                                channel.click(1, false)
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            multi = true
                            if (btnHeld) { channel.button(0, false); btnHeld = false }
                            val dy = pressed.fold(0f) { acc, c -> acc + c.positionChange().y }
                            scrollAcc += dy * 1.6f
                            if (abs(scrollAcc) >= 8f) {
                                channel.wheel(scrollAcc)
                                scrolled = true
                                scrollAcc = 0f
                            }
                            pressed.forEach { it.consume() }
                        } else {
                            val c = pressed.first()
                            val delta = c.positionChange()
                            travel += delta.getDistance()
                            if (!multi) {
                                // 长按 → 按住左键进入拖拽
                                if (!btnHeld && travel < touchSlop &&
                                    nowMs - startTime >= longPressMs
                                ) {
                                    btnHeld = true
                                    channel.button(0, true)
                                }
                                if (btnHeld || travel > touchSlop) {
                                    lastSendX += delta.x
                                    lastSendY += delta.y
                                    if (abs(lastSendX) + abs(lastSendY) >= 1f) {
                                        channel.move(lastSendX * sensitivity, lastSendY * sensitivity)
                                        lastSendX = 0f
                                        lastSendY = 0f
                                    }
                                }
                            }
                            c.consume()
                        }
                    }
                }
            }
    ) {
        Text(
            "单指 移动 / 点按=左键 / 双击=双击\n长按拖拽 · 双指滑动=滚轮 · 双指点按=右键",
            style = SmallNumber,
            color = pulse.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

// ---------- 文本注入 + 快捷键 ----------

@Composable
private fun TextKeyInput(channel: InputChannel, enabled: Boolean) {
    val pulse = LocalPulseColors.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    // 已推送到 PC 的「已提交文本」镜像；组词中的内容不参与同步
    var sent by remember { mutableStateOf("") }

    fun syncCommitted(v: TextFieldValue) {
        // 组词区间之外的部分 = 已真正落进字段的文本
        val compEnd = v.composition?.min ?: v.text.length
        val committed = v.text.substring(0, compEnd)
        // 最小差异：只删/发真正变化的尾巴（容忍 IME 对句中任意位置的改写）
        var i = 0
        val n = minOf(sent.length, committed.length)
        while (i < n && sent[i] == committed[i]) i++
        repeat(sent.length - i) { channel.key("backspace") }
        if (committed.length > i) channel.text(committed.substring(i))
        sent = committed
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { v ->
                if (enabled) syncCommitted(v)
                field = v
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (enabled) "在此输入，文字将直接打到电脑上…" else "电脑离线，连接后可用", style = SmallNumber, color = pulse.textTertiary) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = pulse.textPrimary, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { channel.key("enter") }),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        KeyStrip(channel, enabled)
    }
}

private data class KeyDef(val label: String, val keys: List<String> = emptyList(), val single: String? = null)

private val KEY_ROW_1 = listOf(
    KeyDef("Esc", single = "esc"),
    KeyDef("Tab", single = "tab"),
    KeyDef("Del", single = "delete"),
    KeyDef("⌫", single = "backspace"),
    KeyDef("⏎", single = "enter"),
    KeyDef("Win", single = "win"),
    KeyDef("↑", single = "up"),
    KeyDef("↓", single = "down"),
    KeyDef("←", single = "left"),
    KeyDef("→", single = "right"),
    KeyDef("Home", single = "home"),
    KeyDef("End", single = "end"),
    KeyDef("PgUp", single = "pageup"),
    KeyDef("PgDn", single = "pagedown"),
)

private val KEY_ROW_2 = listOf(
    KeyDef("Ctrl+C", keys = listOf("ctrl", "c")),
    KeyDef("Ctrl+V", keys = listOf("ctrl", "v")),
    KeyDef("Ctrl+X", keys = listOf("ctrl", "x")),
    KeyDef("Ctrl+Z", keys = listOf("ctrl", "z")),
    KeyDef("Ctrl+A", keys = listOf("ctrl", "a")),
    KeyDef("Ctrl+S", keys = listOf("ctrl", "s")),
    KeyDef("Ctrl+W", keys = listOf("ctrl", "w")),
    KeyDef("Alt+Tab", keys = listOf("alt", "tab")),
    KeyDef("Win+D", keys = listOf("win", "d")),
    KeyDef("Win+E", keys = listOf("win", "e")),
    KeyDef("Alt+F4", keys = listOf("alt", "f4")),
)

@Composable
private fun KeyStrip(channel: InputChannel, enabled: Boolean) {
    val pulse = LocalPulseColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(KEY_ROW_1, KEY_ROW_2).forEach { row ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(row) { k ->
                    Text(
                        k.label,
                        style = SmallNumber.copy(fontSize = 13.sp),
                        color = if (enabled) pulse.textPrimary else pulse.textTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(pulse.surfaceHigh)
                            .border(1.dp, pulse.hairline, RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled) {
                                if (k.single != null) channel.key(k.single)
                                else channel.combo(k.keys)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
