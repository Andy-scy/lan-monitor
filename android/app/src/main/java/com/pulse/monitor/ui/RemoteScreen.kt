package com.pulse.monitor.ui

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.util.Rational
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.monitor.data.connection.DeviceConnection
import com.pulse.monitor.data.connection.InputChannel
import com.pulse.monitor.data.connection.LinkState
import com.pulse.monitor.ui.common.Hairline
import com.pulse.monitor.ui.common.StateBadge
import com.pulse.monitor.ui.theme.Label
import com.pulse.monitor.ui.theme.LocalPulseColors
import com.pulse.monitor.ui.theme.SmallNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Composable
fun RemoteControlScreen(connection: DeviceConnection, onBack: () -> Unit, pip: Boolean) {
    val pulse = LocalPulseColors.current
    val state by connection.state.collectAsState()
    val host = connection.host
    val port = connection.port
    val token = connection.token
    val context = LocalContext.current
    val activity = remember { context as? android.app.Activity }

    val channel = remember(host, port, token) { InputChannel(host, port, token) }
    LaunchedEffect(connection) { if (connection.token != null) connection.start() }

    var showScreen by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var mirrorButtons by remember { mutableStateOf(false) }

    // JPEG 拉流（PiP 与镜像共用）
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

    // 隐藏按钮 3 秒自动消失
    LaunchedEffect(mirrorButtons) {
        if (mirrorButtons) { delay(3000); mirrorButtons = false }
    }
    // 横竖屏跟随放大模式
    LaunchedEffect(expanded) {
        activity?.requestedOrientation = if (expanded)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    fun enterPip() {
        showScreen = true
        expanded = false
        try {
            activity?.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        } catch (_: Exception) { }
    }

    // ===== 系统画中画：只渲染镜像 =====
    if (pip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        return
    }

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
                if (expanded) expanded = false
                onBack()
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = pulse.textPrimary) }
            Column(Modifier.weight(1f)) {
                Text(
                    if (expanded) "遥控 · 横屏" else "遥控",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = pulse.textPrimary,
                )
                Text("触控板 · 键盘 → ${connection.displayName}", style = SmallNumber, color = pulse.textTertiary)
            }
            if (expanded) {
                Text(
                    "还原竖屏",
                    style = Label,
                    color = pulse.cpu,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(8.dp))
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
                LinkState.ONLINE -> StateBadge("在线", pulse.online, true)
                LinkState.RECONNECTING -> StateBadge("重连中", pulse.loadWarm, true)
                LinkState.STALE -> StateBadge("数据滞后", pulse.loadWarm)
                else -> StateBadge("离线", null)
            }
        }
        Hairline()

        if (expanded) {
            // ===== 横屏放大：左侧大镜像 + 底部透明输入框；右侧小触控板 =====
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    MirrorBox(
                        frame = frame,
                        buttonsVisible = mirrorButtons,
                        onToggle = { mirrorButtons = !mirrorButtons },
                        onPip = { enterPip() },
                        onExpand = null,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    Hairline()
                    InputBox(
                        channel, state == LinkState.ONLINE, transparent = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(Modifier.width(230.dp)) {
                    Touchpad(
                        channel = channel,
                        connected = state == LinkState.ONLINE,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            AnimatedVisibility(visible = showScreen) {
                MirrorBox(
                    frame = frame,
                    buttonsVisible = mirrorButtons,
                    onToggle = { mirrorButtons = !mirrorButtons },
                    onPip = { enterPip() },
                    onExpand = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Touchpad(
                channel = channel,
                connected = state == LinkState.ONLINE,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            InputBox(
                channel, state == LinkState.ONLINE, transparent = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------- 屏幕镜像（点画面呼出隐藏按钮，3 秒自动消失） ----------

private class TapState {
    var at = 0L
    var x = 0f
    var y = 0f
}

@Composable
private fun MirrorBox(
    frame: ImageBitmap?,
    buttonsVisible: Boolean,
    onToggle: () -> Unit,
    onPip: () -> Unit,
    onExpand: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val pulse = LocalPulseColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(pulse.surface)
            .border(1.dp, pulse.hairline, RoundedCornerShape(14.dp))
    ) {
        if (frame != null) {
            Image(
                frame, null,
                Modifier.fillMaxSize().clickable { onToggle() },
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                "正在连接电脑屏幕…\n（点按此处开启镜像）",
                style = SmallNumber,
                color = pulse.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).clickable { onToggle() },
            )
        }
        if (buttonsVisible) {
            Text(
                "画中画",
                style = Label,
                color = pulse.textPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onPip() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            onExpand?.let {
                Text(
                    "放大",
                    style = Label,
                    color = pulse.textPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { it() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

// ---------- 触控板 ----------

private class TapState2 {
    var at = 0L
    var x = 0f
    var y = 0f
}

@Composable
private fun Touchpad(channel: InputChannel, connected: Boolean, modifier: Modifier = Modifier) {
    val pulse = LocalPulseColors.current
    val tapState = remember { TapState2() }

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

// ---------- 文本注入（组词感知 + 最小差异同步） ----------

@Composable
private fun InputBox(
    channel: InputChannel,
    enabled: Boolean,
    transparent: Boolean,
    modifier: Modifier = Modifier,
) {
    val pulse = LocalPulseColors.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    // 已推送到 PC 的「已提交文本」镜像；组词中的内容不参与同步
    var sent by remember { mutableStateOf("") }

    fun syncCommitted(v: TextFieldValue) {
        val compEnd = v.composition?.min ?: v.text.length
        val committed = v.text.substring(0, compEnd)
        var i = 0
        val n = minOf(sent.length, committed.length)
        while (i < n && sent[i] == committed[i]) i++
        repeat(sent.length - i) { channel.key("backspace") }
        if (committed.length > i) channel.text(committed.substring(i))
        sent = committed
    }

    if (transparent) {
        Box(modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (field.text.isEmpty()) {
                Text(
                    "点此输入 → 文字直达电脑…",
                    style = SmallNumber,
                    color = pulse.textTertiary,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                )
            }
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    if (enabled) syncCommitted(v)
                    field = v
                },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = pulse.textPrimary.copy(alpha = 0.9f), fontSize = 15.sp),
                cursorBrush = SolidColor(pulse.cpu),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { channel.key("enter") }),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    } else {
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
                textStyle = TextStyle(color = pulse.textPrimary, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { channel.key("enter") }),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            KeyStrip(channel, enabled)
        }
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
