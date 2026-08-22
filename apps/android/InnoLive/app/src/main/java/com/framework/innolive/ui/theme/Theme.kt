package com.framework.innolive.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import com.framework.innolive.utils.getFonts
private val suitFont = getFonts()
private val defaultTypography = Typography()

private fun TextStyle.withSuitFont() = copy(fontFamily = suitFont)

val InnoLiveTypography = Typography(
    displayLarge = defaultTypography.displayLarge.withSuitFont(),
    displayMedium = defaultTypography.displayMedium.withSuitFont(),
    displaySmall = defaultTypography.displaySmall.withSuitFont(),
    headlineLarge = defaultTypography.headlineLarge.withSuitFont(),
    headlineMedium = defaultTypography.headlineMedium.withSuitFont(),
    headlineSmall = defaultTypography.headlineSmall.withSuitFont(),
    titleLarge = defaultTypography.titleLarge.withSuitFont(),
    titleMedium = defaultTypography.titleMedium.withSuitFont(),
    titleSmall = defaultTypography.titleSmall.withSuitFont(),
    bodyLarge = defaultTypography.bodyLarge.withSuitFont(),
    bodyMedium = defaultTypography.bodyMedium.withSuitFont(),
    bodySmall = defaultTypography.bodySmall.withSuitFont(),
    labelLarge = defaultTypography.labelLarge.withSuitFont(),
    labelMedium = defaultTypography.labelMedium.withSuitFont(),
    labelSmall = defaultTypography.labelSmall.withSuitFont(),
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    secondary = Color(0x656565FF),
    background = Color(0x050505FF),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0x000000FF),
    secondary = Color(0x2B2B2BFF),
    background = Color(0xFFFFFFFF)

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = InnoLiveTypography,
        content = content
    )
}
