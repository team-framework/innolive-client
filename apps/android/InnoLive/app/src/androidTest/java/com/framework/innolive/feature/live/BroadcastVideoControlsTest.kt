package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class BroadcastVideoControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun presetAndResetPreserveStabilizationPreference() {
        val settings = mutableStateOf(BroadcastVideoQualitySettings(stabilizationEnabled = false))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BroadcastVideoAdjustments(
                        settings = settings.value,
                        captureState = VideoQualityCaptureState(),
                        previews = null,
                        onSettingsChanged = { settings.value = it },
                    )
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.video_preset_bright)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.video_preset_bright)).assertIsSelected()
        compose.runOnIdle {
            assertEquals(0.8f, settings.value.exposureEV, 0f)
            assertEquals(0.2f, settings.value.warmth, 0f)
            assertEquals(1.2f, settings.value.saturation, 0f)
            assertFalse(settings.value.stabilizationEnabled)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.video_reset)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(BroadcastVideoQualitySettings(stabilizationEnabled = false), settings.value)
        }
    }

    @Test fun exposureUsesDeviceRangeAndSlidersSendAccessibleChanges() {
        val settings = mutableStateOf(BroadcastVideoQualitySettings(exposureEV = 2f))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BroadcastVideoAdjustments(
                        settings = settings.value,
                        captureState = VideoQualityCaptureState(
                            exposureSupported = true,
                            minExposureEV = -1f,
                            maxExposureEV = 1f,
                            appliedExposureEV = 1f,
                        ),
                        previews = null,
                        onSettingsChanged = { settings.value = it },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.video_exposure))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 1f, range = -1f..1f, steps = 0),
            ))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-0.5f) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.video_warmth))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.video_saturation))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle {
            assertEquals(-0.5f, settings.value.exposureEV, 0.001f)
            assertEquals(0.6f, settings.value.warmth, 0.001f)
            assertEquals(0f, settings.value.saturation, 0f)
        }
    }

    @Test fun unsupportedExposureIsDisabled() {
        compose.setContent {
            MaterialTheme {
                BroadcastVideoAdjustments(
                    settings = BroadcastVideoQualitySettings(),
                    captureState = VideoQualityCaptureState(),
                    previews = null,
                    onSettingsChanged = { error("Disabled exposure must not change settings") },
                )
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.video_exposure))
            .assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.video_exposure_unavailable)).assertExists()
    }
}
