package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AnonymizationControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun offUsesSelectionCallbackAndFailureAllowsExplicitRetryWithoutDisconnect() {
        val change = mutableStateOf(AnonymizationChange())
        var requests = 0
        compose.setContent {
            MaterialTheme {
                AnonymizationControls(
                    anonymizationControlsState(WebRtcConnectionState.CONNECTED, AnonymizationState.ENABLED,
                        true, true, change.value),
                    "미리보기 연결됨", change.value.errorMessage,
                    onSelect = {
                        assertFalse(it)
                        requests++
                        change.value = AnonymizationChange(status = AnonymizationChangeStatus.CHANGING)
                    },
                )
            }
        }
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("On", substring = false).assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("Off").assertIsNotEnabled()
        compose.onNodeWithText("연결 종료").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, requests)
            change.value = AnonymizationChange(status = AnonymizationChangeStatus.FAILED, errorMessage = "변경 실패")
        }
        compose.onNodeWithText("변경 실패").assertIsDisplayed()
        compose.onNodeWithText("On", substring = false).assertIsSelected()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "anonymization-controls.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Off").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, requests) }
    }
    @Test fun offlineChoiceUsesViewModelWithoutManualConnectionControls() {
        val session = WebRtcSessionViewModel()
        val preference = AnonymizationPreference(compose.activity)
        val original = preference.enabled
        compose.runOnIdle { session.restoreAnonymizationSelection(compose.activity) }
        try {
            compose.setContent {
                MaterialTheme {
                    AnonymizationControls(
                        anonymizationControlsState(session.connectionState, session.anonymizationState,
                            session.selectedAnonymizationEnabled, session.isAnonymizationSelectionLoaded,
                            session.anonymizationChange),
                        session.connectionStatus, session.anonymizationChange.errorMessage,
                        onSelect = { session.selectAnonymization(compose.activity, it) },
                    )
                }
            }
            compose.onNodeWithText("Off").performClick().assertIsSelected()
            compose.runOnIdle {
                assertFalse(preference.enabled)
                assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
                assertEquals(AnonymizationState.UNKNOWN, session.anonymizationState)
            }
            compose.onNodeWithText("미리보기 연결").assertDoesNotExist()
            compose.onNodeWithText("연결 취소").assertDoesNotExist()
            compose.onNodeWithText("연결 종료").assertDoesNotExist()
        } finally {
            compose.runOnIdle { session.close(); preference.enabled = original }
        }
    }

}
