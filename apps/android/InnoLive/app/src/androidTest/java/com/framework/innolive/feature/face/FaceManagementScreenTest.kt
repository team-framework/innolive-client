package com.framework.innolive.feature.face

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceManagementScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun opensRegistrationFromManagementWithoutStartingNetworkRequests() {
        composeRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    FaceManagementScreen(
                        cameraLensFacing = CameraLensFacing.FRONT,
                        onGetAccessToken = { null },
                        onRefreshAccessToken = { error("refresh must not run") },
                        onBack = { visible = false },
                    )
                }
            }
        }

        composeRule.onNodeWithText("얼굴 관리").assertIsDisplayed()
        composeRule.onNodeWithText("얼굴 등록").performClick()
        composeRule
            .onNodeWithText("등록하면 기존에 등록한 얼굴이 교체됩니다.")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("닫기").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("얼굴 관리").assertIsDisplayed()
    }
}
