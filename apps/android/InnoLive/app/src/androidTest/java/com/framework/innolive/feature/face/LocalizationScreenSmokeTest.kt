package com.framework.innolive.feature.face

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.login.LoginScreen
import com.framework.innolive.feature.login.LoginScreenProps
import com.framework.innolive.feature.settings.camera.CameraSetting
import com.framework.innolive.feature.settings.camera.CameraSettingProps
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class LocalizationScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loginAndEmailFormFollowLanguageChangesWithoutLeavingTheForm() {
        val context = mutableStateOf(localizedContext("ko"))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MyApplicationTheme(dynamicColor = false) {
                    LoginScreen(LoginScreenProps(onLogin = {}, onGoogleLogin = {}))
                }
            }
        }

        composeRule.onNodeWithText(context.value.getString(R.string.continue_with_email))
            .performClick()
        for (language in listOf("ko", "en", "ja")) {
            val localized = localizedContext(language)
            composeRule.runOnIdle { context.value = localized }
            composeRule.onNodeWithText(localized.getString(R.string.email_sign_in_title))
                .assertIsDisplayed()
            composeRule.onNodeWithText(localized.getString(R.string.action_sign_in))
                .assertExists()
            composeRule.onNodeWithContentDescription(localized.getString(R.string.action_back))
                .assertIsDisplayed()
            composeRule.onNodeWithContentDescription(
                localized.getString(
                    R.string.content_description_show_value,
                    localized.getString(R.string.label_password),
                ),
            ).assertExists()
        }
    }

    @Test
    fun faceManagementLabelsAndActionsFollowLanguageChanges() {
        val context = mutableStateOf(localizedContext("ko"))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MyApplicationTheme(dynamicColor = false) {
                    FaceManagementScreen(
                        cameraLensFacing = CameraLensFacing.FRONT,
                        onGetAccessToken = { null },
                        onRefreshAccessToken = { error("refresh must not run") },
                        onBack = {},
                    )
                }
            }
        }

        for (language in listOf("ko", "en", "ja")) {
            val localized = localizedContext(language)
            composeRule.runOnIdle { context.value = localized }
            composeRule.onNodeWithText(localized.getString(R.string.face_management))
                .assertIsDisplayed()
            composeRule.onNodeWithContentDescription(
                localized.getString(R.string.action_register_face),
            ).assertExists()
            composeRule.onNodeWithContentDescription(localized.getString(R.string.action_close))
                .assertExists()
        }
    }

    @Test
    fun cameraSettingsLocalizeLabelsButPreserveDeviceNames() {
        val context = mutableStateOf(localizedContext("ko"))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MyApplicationTheme(dynamicColor = false) {
                    CameraSetting(
                        CameraSettingProps(
                            onBack = {},
                            selectedResolution = "1920×1080",
                            selectedCameraDevice = "External Camera",
                            selectedAudioDevice = "Studio Microphone",
                            onOpenResolutionOptions = {},
                            onOpenCameraDeviceOptions = {},
                            onOpenAudioDeviceOptions = {},
                        ),
                    )
                }
            }
        }

        for (language in listOf("ko", "en", "ja")) {
            val localized = localizedContext(language)
            composeRule.runOnIdle { context.value = localized }
            listOf(
                R.string.settings_camera_audio,
                R.string.label_camera_resolution,
                R.string.label_camera_device,
                R.string.label_audio_device,
            ).forEach { id ->
                composeRule.onNodeWithText(localized.getString(id)).assertExists()
            }
            composeRule.onNodeWithText("External Camera").assertExists()
            composeRule.onNodeWithText("Studio Microphone").assertExists()
        }
    }

    @Test
    fun cameraSettingsRemainReachableAtLargeFontScale() {
        val localized = localizedContext("ja")
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            ) {
                Box(Modifier.width(320.dp).height(560.dp)) {
                    CameraSetting(
                        CameraSettingProps(
                            onBack = {},
                            selectedResolution = "1920×1080",
                            selectedCameraDevice = "External Camera",
                            selectedAudioDevice = "Studio Microphone",
                            onOpenResolutionOptions = {},
                            onOpenCameraDeviceOptions = {},
                            onOpenAudioDeviceOptions = {},
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText(localized.getString(R.string.label_audio_device))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Studio Microphone")
            .performScrollTo().assertIsDisplayed()
    }

    private fun localizedContext(language: String): Context {
        val configuration = Configuration(composeRule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return composeRule.activity.createConfigurationContext(configuration)
    }
}
