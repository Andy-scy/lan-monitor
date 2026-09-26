package com.pulse.monitor.ui.charts

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import kotlin.math.min

// ---------- 工具：Catmull-Rom 平滑曲线 ----------

private fun smoothPath(path: Path, pts: List<Offset>) {
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        path.moveTo(pts[0].x, pts[0].y)
        return
    }
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[maxOf(0, i - 1)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[minOf(pts.size - 1, i + 2)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
}

/** 稀疏采样：超过 2 倍像素点数时抽稀，绘制成本恒定 */
private fun decimate(values: FloatArray, maxPoints: Int): FloatArray {
    if (values.size <= maxPoints) return values
    val step = values.size.toFloat() / maxPoints
    val out = FloatArray(maxPoints)
    for (i in 0 until maxPoints) {
        out[i] = values[(i * step).toInt().coerceAtMost(values.size - 1)]
    }
    return out
}

// ---------- Sparkline（迷你曲线，卡片底部） ----------

@Composable
fun Sparkline(
    values: FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    strokeWidth: Dp = 2.dp,
    yMax: Float? = null,
    alpha: Float = 1f,
) {
    val sw = strokeWidth
    Canvas(modifier) {
        if (values.size < 2) {
            // 无数据：一条静态低横线
            val y = size.height * 0.72f
            drawLine(color.copy(alpha = 0.25f * alpha), Offset(0f, y), Offset(size.width, y), sw.toPx(), StrokeCap.Round)
            return@Canvas
        }
        val v = decimate(values, 240)
        val maxV = (yMax ?: v.max().coerceAtLeast(0.0001f))
        val h = size.height
        val w = size.width
        val pts = v.mapIndexed { i, x ->
            val t = if (v.size == 1) 0f else i.toFloat() / (v.size - 1)
            val yv = (x / maxV).coerceIn(0f, 1.05f)
            Offset(t * w, h - 1.dp.toPx() - yv * (h - 2.dp.toPx()))
        }
        val path = Path().also { smoothPath(it, pts) }
        if (filled) {
            val fillPath = Path().apply {
                addPath(path)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(
                fillPath,
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.28f * alpha), color.copy(alpha = 0.0f * alpha)),
                    startY = 0f, endY = h,
                ),
            )
        }
        drawPath(path, color.copy(alpha = alpha), style = Stroke(sw.toPx(), cap = StrokeCap.Round))
    }
}

// ---------- AreaChart（详情页大图，带峰值点） ----------

@Composable
fun AreaChart(
    values: FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    yMax: Float? = null,
    strokeWidth: Dp = 2.dp,
) {
    val sw = strokeWidth
    Canvas(modifier) {
        val v = decimate(if (values.isEmpty()) FloatArray(2) else values, 400)
        val maxV = (yMax ?: maxOf(v.maxOrNull() ?: 1f, 0.0001f))
        val h = size.height
        val w = size.width
        val bottomPad = 2.dp.toPx()

        // 参考线：25% / 50% / 75%
        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            val y = h - bottomPad - f * (h - bottomPad)
            drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y), 1f)
        }
        if (v.size < 2 || v.all { it == 0f }) {
            drawLine(color.copy(alpha = 0.3f), Offset(0f, h - bottomPad), Offset(w, h - bottomPad), sw.toPx(), StrokeCap.Round)
            return@Canvas
        }
        val pts = v.mapIndexed { i, x ->
            val t = i.toFloat() / (v.size - 1)
            val yv = (x / maxV).coerceIn(0f, 1.02f)
            Offset(t * w, h - bottomPad - yv * (h - bottomPad))
        }
        val path = Path().also { smoothPath(it, pts) }
        val fillPath = Path().apply {
            addPath(path)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            fillPath,
            Brush.verticalGradient(
                listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f)),
                startY = 0f, endY = h,
            ),
        )
        drawPath(path, color, style = Stroke(sw.toPx(), cap = StrokeCap.Round))
        // 末端呼吸点
        val last = pts.last()
        drawCircle(color, 3.dp.toPx(), last)
        drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), last)
    }
}

// ---------- ArcGauge（环形仪表：当前值 + 峰值幽灵刻度） ----------

@Composable
fun ArcGauge(
    progress: Float?,
    peak: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 9.dp,
    trackColor: Color = Color.White.copy(alpha = 0.08f),
) {
    val anim by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(650, easing = EaseOutCubic),
        label = "gauge",
    )
    val ghost by animateFloatAsState(
        targetValue = (peak ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(900, easing = EaseOutCubic),
        label = "ghost",
    )
    Canvas(modifier.graphicsLayer(alpha = if (progress == null) 0.35f else 1f)) {
        val startAngle = 140f
        val sweep = 260f
        val sw = strokeWidth.toPx()
        val pad = 2.dp.toPx()
        // 永远画正圆：直径取画布短边，水平/垂直居中（画布更宽时两侧留白）
        val diameter = minOf(size.width, size.height) - sw * 2 - pad * 2
        if (diameter <= 0f) return@Canvas
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        // 轨道
        drawArc(trackColor, startAngle, sweep, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        // 峰值幽灵
        if (peak != null && ghost > 0.02f) {
            drawArc(color.copy(alpha = 0.22f), startAngle, sweep * ghost, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        // 当前值
        if (progress != null) {
            drawArc(color, startAngle, sweep * anim, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

// ---------- BarMeter（横向进度条：内存/磁盘） ----------

@Composable
fun BarMeter(
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    val anim by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(650, easing = EaseOutCubic),
        label = "bar",
    )
    Canvas(modifier.graphicsLayer(alpha = if (progress == null) 0.35f else 1f)) {
        val h = height.toPx()
        val w = size.width
        val trackRect = Rect(0f, (size.height - h) / 2, w, (size.height + h) / 2)
        drawRoundRect(Color.White.copy(alpha = 0.08f), trackRect.topLeft, Size(w, h), CornerRadius(h / 2, h / 2))
        if (progress != null && anim > 0.005f) {
            drawRoundRect(color, trackRect.topLeft, Size(w * anim, h), CornerRadius(h / 2, h / 2))
        }
    }
}

// ---------- CoreBars（每核使用率：单个 Canvas 画全部核心） ----------

@Composable
fun CoreBars(
    cores: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    highColor: Color = Color(0xFFFF7A6B),
    warmColor: Color = Color(0xFFFFC46B),
) {
    Canvas(modifier) {
        if (cores.isEmpty()) return@Canvas
        val cols = min(8, cores.size)
        val rows = (cores.size + cols - 1) / cols
        val gap = 6.dp.toPx()
        val cw = (size.width - gap * (cols - 1)) / cols
        val ch = (size.height - gap * (rows - 1)) / rows
        val barW = cw * 0.42f
        cores.forEachIndexed { i, v ->
            val r = i / cols
            val c = i % cols
            val x = c * (cw + gap)
            val y = r * (ch + gap)
            val trackRect = Rect(x + (cw - barW) / 2, y, x + (cw + barW) / 2, y + ch)
            drawRoundRect(Color.White.copy(alpha = 0.09f), trackRect.topLeft, Size(barW, ch), CornerRadius(barW / 2, barW / 2))
            val frac = (v / 100f).coerceIn(0f, 1f)
            if (frac > 0.01f) {
                val barColor = when {
                    v >= 85f -> highColor
                    v >= 60f -> warmColor
                    else -> color
                }
                val hh = ch * frac
                val barRect = Rect(trackRect.left, y + ch - hh, trackRect.right, y + ch)
                drawRoundRect(barColor, barRect.topLeft, Size(barW, hh), CornerRadius(barW / 2, barW / 2))
            }
        }
    }
}
