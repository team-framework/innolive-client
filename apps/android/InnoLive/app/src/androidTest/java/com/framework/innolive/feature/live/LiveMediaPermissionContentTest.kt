package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.framework.innolive.R
import org.junit.Rule
import org.junit.Test

class LiveMediaPermissionContentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cameraPreviewRemainsVisibleWhenOnlyMicrophonePermissionIsMissing() {
        var permissions by mutableStateOf(MediaPermissionState(true, false))
        rule.setContent {
            LiveMediaPermissionContent(permissions, onRequestPermissions = {}) {
                Box(Modifier.fillMaxSize().testTag("camera-preview"))
            }
        }

        rule.onNodeWithTag("camera-preview").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.permission_microphone_required))
            .assertIsDisplayed()

        rule.runOnIdle { permissions = MediaPermissionState(false, true) }
        rule.onNodeWithTag("camera-preview").assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.permission_camera_required))
            .assertIsDisplayed()
    }
}
