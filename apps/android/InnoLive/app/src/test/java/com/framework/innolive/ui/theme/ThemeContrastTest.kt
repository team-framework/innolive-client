package com.framework.innolive.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun lightThemeTextAndButtonLabelsHaveReadableContrast() = assertReadable(LightColorScheme)

    @Test
    fun darkThemeTextAndButtonLabelsHaveReadableContrast() = assertReadable(DarkColorScheme)

    private fun assertReadable(scheme: androidx.compose.material3.ColorScheme) {
        listOf(
            scheme.background to scheme.onBackground,
            scheme.surface to scheme.onSurface,
            scheme.primary to scheme.onPrimary,
            scheme.secondary to scheme.onSecondary,
        ).forEach { (background, foreground) ->
            assertEquals("Background must be opaque", 1f, background.alpha, 0f)
            assertEquals("Text must be opaque", 1f, foreground.alpha, 0f)
            assertTrue("Text contrast must be at least 4.5:1", contrast(background, foreground) >= 4.5f)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val light = maxOf(first.luminance(), second.luminance())
        val dark = minOf(first.luminance(), second.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }
}
