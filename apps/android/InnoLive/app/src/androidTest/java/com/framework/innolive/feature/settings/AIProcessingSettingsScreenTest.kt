package com.framework.innolive.feature.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.face.LocalFaceNameField
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class AIProcessingSettingsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun processingModeCanBeSelectedFromSettings() {
        val requests = mutableListOf<Boolean>()
        composeRule.setContent {
            var onDevice by remember { mutableStateOf(false) }
            MyApplicationTheme {
                SettingsScreen(props().copy(
                    onDeviceProcessing = onDevice,
                    canChangeAIProcessing = true,
                    onSelectAIProcessing = { requests += it; onDevice = it },
                ))
            }
        }
        val onDeviceLabel = context.getString(R.string.ai_mode_on_device)
        val serverLabel = context.getString(R.string.ai_mode_server)
        composeRule.onNodeWithText(onDeviceLabel).performScrollTo().performClick().assertIsSelected()
        assertEquals(listOf(true), requests)
        saveScreenshot("ai-processing-settings.png")
        composeRule.onNodeWithText(serverLabel).performClick().assertIsSelected()
        assertEquals(listOf(true, false), requests)
    }

    @Test fun processingModeCannotChangeWhileBroadcastStateDisallowsIt() {
        var requests = 0
        composeRule.setContent {
            MyApplicationTheme {
                SettingsScreen(props().copy(canChangeAIProcessing = false,
                    onSelectAIProcessing = { requests++ }))
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.ai_mode_on_device))
            .performScrollTo().assertIsNotEnabled()
        assertEquals(0, requests)
    }

    @Test fun localFaceNameInputWorksOnBlackBackgroundInLightTheme() {
        composeRule.setContent {
            var name by remember { mutableStateOf("") }
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.background(Color.Black).padding(16.dp)) {
                    LocalFaceNameField(name) { name = it }
                }
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.local_face_name))
            .performTextInput("홍길동")
        composeRule.onNodeWithText("홍길동").assertTextContains("홍길동")
        saveScreenshot("local-face-name-input.png")
    }

    private fun props() = SettingsScreenProps(onBack = {}, onOpenCameraSettings = {},
        onOpenBroadcastSettings = {}, profileName = "InnoLive", profileEmail = "test@example.com", onLogout = {})

    private fun saveScreenshot(name: String) {
        File(context.cacheDir, name).outputStream().use { output ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
