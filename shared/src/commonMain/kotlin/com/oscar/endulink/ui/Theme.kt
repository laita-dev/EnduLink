package com.oscar.endulink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object EnduPalette {
    val Background = Color(0xFF0E, 0x11, 0x17)
    val Surface = Color(0xFF18, 0x1C, 0x24)
    val SurfaceElevated = Color(0xFF22, 0x27, 0x33)
    val SurfaceHigh = Color(0xFF22, 0x27, 0x33)
    val Outline = Color(0xFF2D, 0x33, 0x43)

    val Accent = Color(0xFF, 0x6B, 0x00) // Naranja Trail
    val AccentBlue = Color(0x2A, 0x85, 0xFF)
    val Success = Color(0x00, 0xD0, 0x84)
    val Danger = Color(0xFF, 0x3B, 0x30)
    val Warning = Color(0xFF, 0xCC, 0x00)

    val TextPrimary = Color(0xFF, 0xFF, 0xFF)
    val TextSecondary = Color(0x8E, 0x95, 0xA5)
    val TextMuted = Color(0x5A, 0x62, 0x73)
}

private val DarkColorScheme = darkColorScheme(
    primary = EnduPalette.Accent,
    onPrimary = Color.White,
    secondary = EnduPalette.AccentBlue,
    background = EnduPalette.Background,
    surface = EnduPalette.Surface,
    onBackground = EnduPalette.TextPrimary,
    onSurface = EnduPalette.TextPrimary,
    outline = EnduPalette.Outline
)

@Composable
fun EnduLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

