package com.framework.innolive.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Compose colors use ARGB: the leading FF keeps every surface opaque.
internal val DarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF050505),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF050505),
    background = Color(0xFF050505),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF5F5F5),
)

internal val LightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2B2B2B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
)

// Some system palettes provide primary/onPrimary pairs below readable text contrast.
internal fun ColorScheme.withReadablePrimaryContent(): ColorScheme {
    fun contrast(content: Color): Float {
        val backgroundLuminance = primary.luminance()
        val contentLuminance = content.luminance()
        return (maxOf(backgroundLuminance, contentLuminance) + 0.05f) /
            (minOf(backgroundLuminance, contentLuminance) + 0.05f)
    }
    if (contrast(onPrimary) >= 4.5f) return this
    return copy(onPrimary = if (contrast(Color.Black) >= contrast(Color.White)) Color.Black else Color.White)
}
