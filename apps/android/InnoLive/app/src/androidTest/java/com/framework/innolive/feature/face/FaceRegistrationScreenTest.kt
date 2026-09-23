package com.framework.innolive.feature.face

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceRegistrationScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersEnrollmentCopyAndExposesCloseActionWithoutUploading() {
        val closed = AtomicBoolean(false)

        composeRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    FaceRegistrationScreen(
                        cameraLensFacing = CameraLensFacing.FRONT,
                        onGetAccessToken = { null },
                        onRefreshAccessToken = { error("refresh must not run") },
                        onBack = {
                            closed.set(true)
                            visible = false
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_register_face)).assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.face_registration_description))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.action_close))
            .assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(closed.get())
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_register_face)).assertDoesNotExist()
    }
}
