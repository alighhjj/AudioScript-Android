package com.looplooptech.audioscript.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 配色直接取自桌面端 theme.css，保证两端看起来是同一个产品。 */
object Palette {
    val Surface0 = Color(0xFF1E1E1E)
    val Surface1 = Color(0xFF2A2A2A)
    val Surface2 = Color(0xFF333333)
    val SurfaceSelected = Color(0xFF192B3A)
    val Ink0 = Color(0xFFFFFFFF)
    val Ink1 = Color(0xFF999999)
    val Ink2 = Color(0xFF7A7A7A)
    val Border = Color(0xFF3A3A3A)
    val Accent = Color(0xFF007AFF)
    val AccentDim = Color(0xFF0063D1)
    val Success = Color(0xFF30D158)
    val Warn = Color(0xFFFF9F0A)
    val Danger = Color(0xFFFF453A)
    val SegmentPartial = Color(0xFF8E8E93)
    val Translation = Color(0xFF7FB2FF)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Color.White,
    secondary = Palette.Accent,
    background = Palette.Surface0,
    onBackground = Palette.Ink0,
    surface = Palette.Surface0,
    onSurface = Palette.Ink0,
    surfaceVariant = Palette.Surface1,
    onSurfaceVariant = Palette.Ink1,
    outline = Palette.Border,
    outlineVariant = Palette.Border,
    error = Palette.Danger,
    onError = Color.White,
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun AudioScriptTheme(content: @Composable () -> Unit) {
    // 主界面与悬浮字幕都固定深色（桌面端同样如此），不跟随系统亮色主题
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
