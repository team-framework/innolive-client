package com.framework.innolive.ui.theme

import android.util.Log
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
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.feature.settings.SettingsScreen
import com.framework.innolive.feature.settings.SettingsScreenProps
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class ScreenThemeContrastTest(private val dark: Boolean, private val dynamic: Boolean) {
    @get:Rule val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark, dynamicColor = dynamic) {
                Surface(color = MaterialTheme.colorScheme.background, content = body)
            }
        }
    }

    @Test fun loginEnabledAndInProgress() {
        content { LoginScreen(LoginScreenProps({}, { awaitCancellation() })) }
        contrast("Google로 계속하기")
        contrast("이메일로 계속하기")
        compose.onNodeWithText("Google로 계속하기").performClick()
        compose.onNodeWithText("Google로 계속하기").assertIsNotEnabled()
        compose.onNodeWithText("이메일로 계속하기").assertIsNotEnabled()
        // Disabled text intentionally has lower contrast; measure it separately.
        contrast("Google로 계속하기", minimum = 1.5f)
        contrast("이메일로 계속하기", minimum = 1.5f)
        contrast("Google 로그인 중…")
    }

    @Test fun loginFailure() {
        content { LoginScreen(LoginScreenProps({}, { error("Test login failure") })) }
        compose.onNodeWithText("Google로 계속하기").performClick()
        contrast("Google 로그인에 실패했습니다. 다시 시도해 주세요.")
        compose.onNodeWithText("Google로 계속하기").assertIsEnabled()
        contrast("Google로 계속하기")
    }

    @Test fun settings() {
        content { SettingsScreen(SettingsScreenProps({}, {}, {}, "테스트 사용자", "test@example.invalid", {})) }
        listOf("설정", "테스트 사용자", "test@example.invalid", "로그아웃", "카메라 및 오디오 설정", "방송 설정").forEach { contrast(it) }
    }

    @Test fun youtubeSettings() {
        content {
            YouTubeLiveSettingsDialog(
                settings = BroadcastSettings("테스트 제목", "테스트 설명", "public", false, "22"),
                youtubeChannelTitle = null,
                youtubeAccountStatus = "연결 안 됨",
                isYouTubeReconnectRequired = false,
                isYouTubeAccountActionInProgress = false,
                isYouTubeConnectEnabled = true,
                onSettingsChanged = {}, onConnectYouTube = {}, onDismissRequest = {},
            )
        }
        listOf("라이브 설정", "테스트 제목", "테스트 설명", "방송 제목", "방송 설명", "공개 범위", "아동용 설정", "계정 정보", "연결 안 됨", "연동", "저장 및 닫기").forEach { contrast(it) }
        compose.onNodeWithText("공개", useUnmergedTree = true).performClick()
        contrast("일부 공개")
        contrast("비공개")
    }

    private fun contrast(label: String, minimum: Float = 4.5f) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val pixels = node.captureToImage().toPixelMap()
        // In a text bounding rectangle the most frequent pixel is its actual backdrop.
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
