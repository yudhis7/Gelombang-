package com.yudhis7.gelombang.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF0D0F14)
    val Panel = Color(0xFF12151C)
    val Surface1 = Color(0xFF171B24)
    val Surface2 = Color(0xFF1D222D)
    val Surface3 = Color(0xFF252B38)
    val Border = Color(0xFF2F3646)
    val Text = Color(0xFFE9EDF4)
    val Text2 = Color(0xFFB4BCCB)
    val Muted = Color(0xFF7D869A)
    val Accent = Color(0xFF2DD4BF)
    val AccentInk = Color(0xFF062B27)
    val Violet = Color(0xFF7C6DFF)
    val Warn = Color(0xFFF5B74E)
    val Danger = Color(0xFFFF6B6B)
    val Brand = Brush.linearGradient(listOf(Color(0xFF2DD4BF), Color(0xFF4FA8FF), Color(0xFF7C6DFF)))
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.AccentInk,
    primaryContainer = Color(0xFF0F3B37),
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.Violet,
    onSecondary = Color.White,
    secondaryContainer = Palette.Surface3,
    onSecondaryContainer = Palette.Text,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Bg,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface2,
    onSurfaceVariant = Palette.Text2,
    surfaceContainerLowest = Palette.Bg,
    surfaceContainerLow = Palette.Surface1,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Palette.Surface2,
    surfaceContainerHighest = Palette.Surface3,
    outline = Palette.Border,
    outlineVariant = Color(0xFF232937),
    error = Palette.Danger,
)

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
)

@Composable
fun GelombangTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
