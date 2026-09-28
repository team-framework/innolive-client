package com.framework.innolive.feature.live

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraPreviewColorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun warmLookColorsTheCameraPreviewLayer() {
        val settings = mutableStateOf(BroadcastVideoQualitySettings())
        compose.setContent {
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply {
                        addView(
                            View(context).apply { setBackgroundColor(Color.rgb(110, 110, 110)) },
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                },
                update = { frame -> applyCameraPreviewColor(frame, settings.value) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { Color.red(screenshotCenterPixel()) in 105..115 }
        val neutral = screenshotCenterPixel()
        compose.runOnIdle { settings.value = BroadcastVideoQualitySettings(warmth = 1f) }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            val pixel = screenshotCenterPixel()
            Color.red(pixel) > Color.red(neutral) + 15 && Color.blue(pixel) < Color.blue(neutral) - 15
        }
        val warm = screenshotCenterPixel()
        assertTrue("neutral=$neutral warm=$warm", Color.red(warm) > Color.red(neutral) + 15)
        assertTrue("neutral=$neutral warm=$warm", Color.blue(warm) < Color.blue(neutral) - 15)
    }

    private fun screenshotCenterPixel(): Int {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }
}
