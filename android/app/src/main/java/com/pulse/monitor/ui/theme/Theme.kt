package com.pulse.monitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- 设计令牌 ----------
// 深石墨中性底（不用纯黑），每个指标一个低饱和专属色相；
// 负荷语义色独立于指标色相：正常 / 偏高 / 高压。

data class PulseColors(
    val cpu: Color,
    val gpu: Color,
    val mem: Color,
    val netDown: Color,
    val netUp: Color,
    val disk: Color,
    val temp: Color,
    val loadGood: Color,
    val loadWarm: Color,
    val loadHigh: Color,
    val online: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val track: Color,
)

val DarkPulse = PulseColors(
    cpu = Color(0xFF5CA8FF),
    gpu = Color(0xFFFFB168),
    mem = Color(0xFF63D8C8),
    netDown = Color(0xFF6FDDA7),
    netUp = Color(0xFFE8C468),
    disk = Color(0xFF93A4BC),
    temp = Color(0xFFFF9A7B),
    loadGood = Color(0xFF66D19E),
    loadWarm = Color(0xFFFFC46B),
    loadHigh = Color(0xFFFF7A6B),
    online = Color(0xFF5AD394),
    hairline = Color(0x14FFFFFF),
    textPrimary = Color(0xFFE8ECF2),
    textSecondary = Color(0xFF8B95A5),
    textTertiary = Color(0xFF566072),
    bg = Color(0xFF0C0F14),
    surface = Color(0xFF141922),
    surfaceHigh = Color(0xFF1B2230),
    track = Color(0x1AFFFFFF),
)

val LightPulse = PulseColors(
    cpu = Color(0xFF3D82E0),
    gpu = Color(0xFFD97E22),
    mem = Color(0xFF1FA291),
    netDown = Color(0xFF2FA968),
    netUp = Color(0xFFB58A1C),
    disk = Color(0xFF64748B),
    temp = Color(0xFFD96040),
    loadGood = Color(0xFF2FA968),
    loadWarm = Color(0xFFD99A22),
    loadHigh = Color(0xFFD95040),
    online = Color(0xFF2FA968),
    hairline = Color(0x14000000),
    textPrimary = Color(0xFF171B22),
    textSecondary = Color(0xFF5A6472),
    textTertiary = Color(0xFF9AA3B0),
    bg = Color(0xFFF3F5F8),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDF0F4),
    track = Color(0x1A000000),
)

val LocalPulseColors = staticCompositionLocalOf { DarkPulse }

/** 负荷 → 语义色 */
fun PulseColors.loadColor(pct: Double?): Color? {
    if (pct == null) return null
    return when {
        pct >= 85 -> loadHigh
        pct >= 60 -> loadWarm
        else -> loadGood
    }
}

// ---------- 字体 ----------
// 数字全部启用 tabular figures，等宽防抖动

private val tnum = "tnum"

val DisplayNumber = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 34.sp,
    fontFeatureSettings = tnum,
    letterSpacing = (-0.5).sp,
)

val MetricNumber = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 17.sp,
    fontFeatureSettings = tnum,
)

val SmallNumber = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    fontFeatureSettings = tnum,
)

val Label = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    letterSpacing = 1.2.sp,
)

val Body = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
)

private val AppTypography = Typography()

// ---------- 形状 ----------

val PulseShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ---------- 主题入口 ----------

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PulseTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val pulse = if (dark) DarkPulse else LightPulse

    // 状态栏图标颜色随应用主题联动（App 内切换深浅色时保持可见）
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = pulse.cpu,
            background = pulse.bg,
            surface = pulse.surface,
            surfaceContainer = pulse.surface,
            surfaceContainerHigh = pulse.surfaceHigh,
            onBackground = pulse.textPrimary,
            onSurface = pulse.textPrimary,
            onSurfaceVariant = pulse.textSecondary,
            outline = pulse.hairline,
            error = pulse.loadHigh,
        )
    } else {
        lightColorScheme(
            primary = pulse.cpu,
            background = pulse.bg,
            surface = pulse.surface,
            surfaceContainer = pulse.surface,
            surfaceContainerHigh = pulse.surfaceHigh,
            onBackground = pulse.textPrimary,
            onSurface = pulse.textPrimary,
            onSurfaceVariant = pulse.textSecondary,
            outline = pulse.hairline,
            error = pulse.loadHigh,
        )
    }
    CompositionLocalProvider(LocalPulseColors provides pulse) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = PulseShapes,
            content = content,
        )
    }
}
