package com.framework.innolive.ui.theme

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.framework.innolive.feature.login.LoginScreen
import com.framework.innolive.feature.login.LoginScreenProps
import com.framework.innolive.feature.login.oauth.google.GoogleSignInState
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.feature.settings.SettingsScreen
import com.framework.innolive.feature.settings.SettingsScreenProps
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class ScreenThemeContrastTest(private val dark: Boolean, private val dynamic: Boolean) {
    @get:Rule val compose = createComposeRule()

    private fun string(@StringRes id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun content(body: @Composable () -> Unit) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark, dynamicColor = dynamic) {
                Surface(color = MaterialTheme.colorScheme.background, content = body)
            }
        }
    }

    @Test fun loginEnabledAndInProgress() {
        content {
            LoginScreen(
                LoginScreenProps(
                    onLogin = {},
                    onGoogleLogin = {},
                    googleSignInState = GoogleSignInState.InProgress,
                ),
            )
        }
        compose.onNodeWithText(string(R.string.continue_with_google)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.continue_with_email)).assertIsNotEnabled()
        // 비활성 글자는 의도적으로 대비가 낮으므로 별도로 측정합니다.
        contrast(string(R.string.continue_with_google), minimum = 1.5f)
        contrast(string(R.string.continue_with_email), minimum = 1.5f)
    }

    @Test fun loginFailure() {
        content {
            LoginScreen(
                LoginScreenProps(
                    onLogin = {},
                    onGoogleLogin = {},
                    googleSignInState = GoogleSignInState.Failed(
                        UiText.Resource(R.string.google_login_failed),
                    ),
                ),
            )
        }
        contrast(string(R.string.google_login_failed))
        compose.onNodeWithText(string(R.string.continue_with_google)).assertIsEnabled()
        contrast(string(R.string.continue_with_google))
    }

    @Test fun settings() {
        content { SettingsScreen(SettingsScreenProps({}, {}, {}, "테스트 사용자", "test@example.invalid", {})) }
        listOf(
            string(R.string.settings_title),
            "테스트 사용자",
            "test@example.invalid",
            string(R.string.action_logout),
            string(R.string.settings_camera_audio),
            string(R.string.settings_broadcast),
        ).forEach { contrast(it) }
    }

    @Test fun youtubeSettings() {
        content {
            YouTubeLiveSettingsDialog(
                settings = BroadcastSettings("테스트 제목", "테스트 설명", "public", false, "22"),
                youtubeChannelTitle = null,
                hasYouTubeAccount = false,
                youtubeAccountStatus = "연결 안 됨",
                isYouTubeReconnectRequired = false,
                isYouTubeAccountActionInProgress = false,
                isYouTubeConnectEnabled = true,
                onSettingsChanged = {}, onConnectYouTube = {}, onDismissRequest = {},
            )
        }
        listOf(
            string(R.string.live_settings_title),
            "테스트 제목",
            "테스트 설명",
            string(R.string.label_broadcast_title),
            string(R.string.label_broadcast_description),
            string(R.string.label_broadcast_privacy),
            string(R.string.label_made_for_kids),
            string(R.string.label_account_information),
            "연결 안 됨",
            string(R.string.action_connect),
            string(R.string.action_save_and_close),
        ).forEach { contrast(it) }
        compose.onNodeWithText(string(R.string.privacy_public), useUnmergedTree = true).performClick()
        contrast(string(R.string.privacy_unlisted))
        contrast(string(R.string.privacy_private))
    }

    private fun contrast(label: String, minimum: Float = 4.5f) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val pixels = node.captureToImage().toPixelMap()
        // 글자 영역에서 가장 많이 나타나는 픽셀 색상을 실제 배경색으로 간주합니다.
        val counts = mutableMapOf<Int, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val argb = pixels[x, y].toArgb()
            counts[argb] = (counts[argb] ?: 0) + 1
        }
        val background = Color(counts.maxBy { it.value }.key)
        val text = layouts.single().layoutInput.style.color.compositeOver(background)
        val ratio = (maxOf(text.luminance(), background.luminance()) + 0.05f) /
            (minOf(text.luminance(), background.luminance()) + 0.05f)
        Log.i("ThemeContrast", "dark=$dark dynamic=$dynamic label=$label minimum=$minimum ratio=$ratio background=${background.toArgb()} text=${text.toArgb()}")
        assertTrue("$label dark=$dark dynamic=$dynamic contrast=$ratio (minimum=$minimum)", ratio >= minimum)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}, dynamic={1}")
        fun themes() = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true))
    }
}
