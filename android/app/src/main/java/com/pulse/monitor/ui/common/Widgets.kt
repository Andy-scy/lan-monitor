package com.pulse.monitor.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.monitor.ui.theme.LocalPulseColors
import com.pulse.monitor.ui.theme.Label
import java.util.Locale

// ---------- 格式化 ----------

fun fmtBps(bps: Double?): String {
    if (bps == null) return "—"
    return when {
        bps >= 1_048_576 -> String.format(Locale.US, "%.1f MB/s", bps / 1_048_576)
        bps >= 1_000 -> String.format(Locale.US, "%.0f KB/s", bps / 1024)
        else -> String.format(Locale.US, "%.0f B/s", bps)
    }
}

fun fmtMB(mb: Double?): String {
    if (mb == null) return "—"
    return when {
        mb >= 1024 -> String.format(Locale.US, "%.1f GB", mb / 1024)
        else -> String.format(Locale.US, "%.0f MB", mb)
    }
}

fun fmtPct(pct: Double?): String = if (pct == null) "—" else String.format(Locale.US, "%.0f%%", pct)

fun fmtPct1(pct: Double?): String = if (pct == null) "—" else String.format(Locale.US, "%.1f%%", pct)

fun fmtTemp(t: Double?): String = if (t == null) "不支持" else String.format(Locale.US, "%.0f°C", t)

fun fmtUptime(sec: Double?): String {
    if (sec == null) return "—"
    val s = sec.toLong()
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return when {
        d > 0 -> "${d}天 ${h}小时"
        h > 0 -> "${h}小时 ${m}分钟"
        else -> "${m}分钟"
    }
}

fun fmtFreqMhz(mhz: Double?): String = if (mhz == null) "—" else String.format(Locale.US, "%.2f GHz", mhz / 1000)

// ---------- 细线（扁平面板的分隔语言） ----------

@Composable
fun Hairline(modifier: Modifier = Modifier, indent: Int = 0) {
    val pulse = LocalPulseColors.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = indent.dp)
            .height(1.dp)
            .background(pulse.hairline)
    )
}

@Composable
fun VDivider(height: Int = 120) {
    val pulse = LocalPulseColors.current
    Box(Modifier.width(1.dp).height(height.dp).background(pulse.hairline))
}

// ---------- 扁平信息段（替代旧的大卡片） ----------

@Composable
fun MetricCard(
    title: String,
    accent: Color?, // 兼容旧签名；扁平设计不再渲染装饰点
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val pulse = LocalPulseColors.current
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Label, color = pulse.textSecondary)
            Spacer(Modifier.width(10.dp))
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
    Hairline()
}

// ---------- 呼吸状态点（功能性元素，保留） ----------

@Composable
fun StatusDot(color: Color?, size: Int = 10, breathing: Boolean = true, modifier: Modifier = Modifier) {
    val c = color ?: LocalPulseColors.current.textTertiary
    val alpha by rememberBreath(breathing)
    Box(
        modifier
            .size(size.dp)
            .alpha(if (breathing) 0.55f + 0.45f * alpha else 1f)
            .background(c, CircleShape)
    )
}

@Composable
private fun rememberBreath(enabled: Boolean): androidx.compose.runtime.State<Float> {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "breath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )
}

// ---------- 状态徽标 ----------

@Composable
fun StateBadge(text: String, color: Color?, breathing: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, 7, breathing)
        Spacer(Modifier.width(6.dp))
        Text(text, style = Label, color = color ?: LocalPulseColors.current.textSecondary)
    }
}

// ---------- 小芯片（仅用于极少场景） ----------

@Composable
fun StatChip(text: String, color: Color? = null) {
    Text(text, style = SmallChipText, color = color ?: LocalPulseColors.current.textTertiary)
}

val SmallChipText = TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))

// ---------- 「不支持」占位 ----------

@Composable
fun UnsupportedValue(label: String = "不支持") {
    Text(
        label,
        style = com.pulse.monitor.ui.theme.MetricNumber,
        color = LocalPulseColors.current.textTertiary,
        modifier = Modifier.alpha(0.8f),
    )
}
