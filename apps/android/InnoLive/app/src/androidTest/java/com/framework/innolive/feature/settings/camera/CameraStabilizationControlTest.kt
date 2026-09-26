package com.framework.innolive.feature.settings.camera

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastVideoQualitySettings
import com.framework.innolive.feature.live.VideoQualityCaptureState
import com.framework.innolive.feature.live.VideoStabilizationStatus
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CameraStabilizationControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectedPreferenceAndReportedSupportRemainDistinct() {
        val settings = mutableStateOf(BroadcastVideoQualitySettings())
        val state = mutableStateOf(VideoQualityCaptureState(stabilizationStatus = VideoStabilizationStatus.UNSUPPORTED))
        compose.setContent {
            MaterialTheme {
                CameraStabilizationControl(
                    CameraSettingProps(
                        onBack = {},
                        selectedResolution = "",
                        selectedCameraDevice = "",
                        selectedAudioDevice = "",
                        onOpenResolutionOptions = {},
                        onOpenCameraDeviceOptions = {},
                        onOpenAudioDeviceOptions = {},
                        videoQualitySettings = settings.value,
                        onVideoQualitySettingsChanged = { settings.value = it },
                        videoQualityCaptureState = state.value,
                    ),
                )
            }
        }
        val label = compose.activity.getString(R.string.video_stabilization)
        compose.onNodeWithText(label).assertIsOn()
        compose.onNodeWithText(compose.activity.getString(R.string.video_stabilization_unsupported)).assertExists()
        compose.onNodeWithText(label).performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(settings.value.stabilizationEnabled)
            state.value = VideoQualityCaptureState(stabilizationStatus = VideoStabilizationStatus.INACTIVE)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.video_stabilization_off)).assertExists()
    }
}
