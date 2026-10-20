package de.hoefer.nookbot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Farben des NookBot-Designs – dunkel (Standard aus dem Prototyp) und hell. Akzente sind in beiden gleich. */
data class Pal(
    val dark: Boolean,
    val bg: Color, val card: Color, val field: Color, val line: Color, val chip: Color,
    val fg: Color, val fg2: Color, val mut: Color,
)

val DarkPal = Pal(true, Color(0xFF0E0E10), Color(0xFF17171A), Color(0xFF1C1C1F), Color(0xFF2C2C30), Color(0xFF26262A),
    Color(0xFFF3F2EF), Color(0xFFC9C8CD), Color(0xFF8D8C93))
val LightPal = Pal(false, Color(0xFFF6F5F2), Color(0xFFFFFFFF), Color(0xFFEDECE8), Color(0xFFDEDCD6), Color(0xFFE6E4DF),
    Color(0xFF17161A), Color(0xFF45444A), Color(0xFF7A7980))

object A {
    val accent = Color(0xFFFF3B4E)
    val amber = Color(0xFFF5A524)
    val green = Color(0xFF2FBF71)
    val blue = Color(0xFF3B8CFF)
    val violet = Color(0xFF9B6BFF)
    val grey = Color(0xFF8F8E95)
    /** Farbauswahl für Bots */
    val bots = listOf(0xFFFF3B4E, 0xFF3B8CFF, 0xFF2FBF71, 0xFFF5A524, 0xFF9B6BFF, 0xFF8F8E95, 0xFF14B8A6, 0xFFEC4899)
}

val LocalPal = staticCompositionLocalOf { DarkPal }

/** Kurzer Zugriff in Composables: P.bg, P.fg … */
val P: Pal @Composable get() = LocalPal.current

@Composable
fun NookTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "hell" -> false
        "dunkel" -> true
        else -> isSystemInDarkTheme()
    }
    val pal = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(primary = A.accent, background = pal.bg, surface = pal.bg, onSurface = pal.fg, surfaceVariant = pal.field)
    else lightColorScheme(primary = A.accent, background = pal.bg, surface = pal.bg, onSurface = pal.fg, surfaceVariant = pal.field)
    CompositionLocalProvider(LocalPal provides pal) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
