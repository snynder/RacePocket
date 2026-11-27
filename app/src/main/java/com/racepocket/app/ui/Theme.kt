package com.racepocket.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em

data class Palette(
    val bg: Color,
    val card: Color,
    val card2: Color,
    val text: Color,
    val muted: Color,
    val divider: Color,
    val accent: Color,
    val accentText: Color,
    val liveBg: Color,
    val purple: Color,
    val green: Color,
    val dark: Boolean
)

val DarkPalette = Palette(
    bg = Color(0xFF15151E), card = Color(0xFF1F1F28), card2 = Color(0xFF2A2A35), text = Color(0xFFF2F2F2),
    muted = Color(0xFF9A9AA8), divider = Color(0xFF2A2A35), accent = Color(0xFFE10600), accentText = Color(0xFFFF6B5E),
    liveBg = Color(0xFF3A1214), purple = Color(0xFFC38BFF), green = Color(0xFF4CD68C), dark = true
)

val LightPalette = Palette(
    bg = Color(0xFFF4F4F7), card = Color(0xFFFFFFFF), card2 = Color(0xFFECECF1), text = Color(0xFF15151E),
    muted = Color(0xFF5E5E6B), divider = Color(0xFFE3E3EA), accent = Color(0xFFE10600), accentText = Color(0xFFC10500),
    liveBg = Color(0xFFFDE6E5), purple = Color(0xFF8A3FE0), green = Color(0xFF138A4A), dark = false
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun pal(): Palette = LocalPalette.current

@Composable
fun RaceTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.text,
            surface = p.card, onSurface = p.text, surfaceVariant = p.card2, onSurfaceVariant = p.muted, outline = p.divider
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.text,
            surface = p.card, onSurface = p.text, surfaceVariant = p.card2, onSurfaceVariant = p.muted, outline = p.divider
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme) {
            // MaterialTheme hands every Text the bodyLarge style, whose fixed 24sp line height suits 16sp
            // paragraphs but leaves the app's 9-14sp labels floating in empty space (a two-line tower row
            // came out ~60dp). A line height that follows the font size keeps lists compact and paragraphs
            // readable; texts that set their own lineHeight keep it.
            ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.25.em), content)
        }
    }
}
