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
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
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
                    onSelect = {
                        assertFalse(it)
                        requests++
                        change.value = AnonymizationChange(status = AnonymizationChangeStatus.CHANGING)
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("비식별화 비활성화").performClick()
        compose.onNodeWithContentDescription("비식별화 비활성화").assertIsNotEnabled()
        compose.onNodeWithContentDescription("비식별화 비활성화").assertIsNotEnabled()
        compose.onNodeWithText("연결 종료").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, requests)
            change.value = AnonymizationChange(
                status = AnonymizationChangeStatus.FAILED,
                errorMessage = UiText.Resource(R.string.error_anonymization_request),
            )
        }
        compose.onNodeWithContentDescription("비식별화 비활성화")
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "anonymization-controls.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("비식별화 비활성화").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, requests) }
    }
    @Test fun offlineChoiceUsesViewModelWithoutManualConnectionControls() {
        val session = WebRtcSessionViewModel()
        val preference = AnonymizationPreference(compose.activity)
        val original = preference.enabled
        preference.enabled = true
        compose.runOnIdle { session.restoreAnonymizationSelection(compose.activity) }
        try {
            compose.setContent {
                MaterialTheme {
                    AnonymizationControls(
                        anonymizationControlsState(session.connectionState, session.anonymizationState,
                            session.selectedAnonymizationEnabled, session.isAnonymizationSelectionLoaded,
                            session.anonymizationChange),
                        onSelect = { session.selectAnonymization(compose.activity, it) },
                    )
                }
            }
            compose.onNodeWithContentDescription("비식별화 비활성화").performClick()
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
