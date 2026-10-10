package com.framework.innolive.feature.live.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ChzzkOAuthConfig
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 앱 언어를 en·ja로 바꿔 실행하면 치지직 화면에 한국어 고정 문구가 남아 있지 않은지 확인한다. */
class ChzzkLocalizationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chzzkSettingsDialogUsesTheAppLanguage() {
        var changePlatform = 0
        compose.setContent {
            MyApplicationTheme {
                ChzzkSettingsDialog(
                    // 검증 안내까지 보이도록 형식에 맞지 않는 태그를 넣는다.
                    settings = ChzzkBroadcastSettings(title = "Test", tags = listOf("bad tag")),
                    accountLabel = string(R.string.chzzk_account_required),
                    canPrepare = false,
                    canConnect = true,
                    canDisconnect = false,
                    isBusy = false,
                    onChanged = {},
                    onRefreshAccount = {},
                    onConnect = {},
                    onDisconnect = {},
                    onSearch = { emptyList() },
                    onPrepare = {},
                    onDismiss = {},
                    onChangePlatform = { changePlatform++ },
                )
            }
        }

        compose.onNodeWithText(string(R.string.chzzk_settings_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.chzzk_account_required)).assertIsDisplayed()
        // 방송 준비 안내가 부르는 버튼 이름과 실제 버튼이 같다.
        compose.onNodeWithText(string(R.string.action_prepare_broadcast)).assertExists()
        compose.onNodeWithText(string(R.string.action_change_platform)).performClick()
        compose.runOnIdle { assertEquals(1, changePlatform) }
        assertNoHardcodedKorean()
        saveScreenshot("chzzk-settings")
    }

    @Test
    fun chzzkOAuthDialogUsesTheAppLanguage() {
        compose.setContent {
            MyApplicationTheme {
                ChzzkOAuthDialog(
                    // 존재하지 않는 주소로 불러오기 실패 안내를 띄운다.
                    config = ChzzkOAuthConfig(
                        authorizeUrl = "https://invalid.localhost/authorize",
                        redirectUri = "innolive://chzzk/callback",
                    ),
                    state = "state",
                    onCode = {},
                    onFailure = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText(string(R.string.chzzk_oauth_title)).assertIsDisplayed()
        compose.waitUntil(LOAD_FAILURE_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTextCount(string(R.string.chzzk_oauth_load_failed)) > 0
        }
        compose.onNodeWithText(string(R.string.action_retry)).assertIsDisplayed()
        assertNoHardcodedKorean()
        saveScreenshot("chzzk-oauth")
    }

    @Test
    fun youtubeSettingsChangePlatformUsesTheAppLanguage() {
        compose.setContent {
            MyApplicationTheme {
                YouTubeLiveSettingsDialog(
                    settings = BroadcastSettings("Test", "Description", "public", false, "22"),
                    youtubeChannelTitle = null,
                    hasYouTubeAccount = false,
                    youtubeAccountStatus = string(R.string.youtube_status_no_account),
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onSettingsChanged = {},
                    onConnectYouTube = {},
                    onDismissRequest = {},
                    onPrepare = {},
                    onChangePlatform = {},
                )
            }
        }

        compose.onNodeWithText(string(R.string.action_change_platform)).assertIsDisplayed()
        assertNoHardcodedKorean()
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    /** 한국어가 아닌 앱 언어에서 화면 글자에 한글이 남아 있으면 실패한다. */
    private fun assertNoHardcodedKorean() {
        val language = compose.activity.resources.configuration.locales[0].language
        if (language == "ko") return
        val texts = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { root -> root.allTexts() }
        val korean = texts.filter { HANGUL.containsMatchIn(it) }
        assertTrue("$language 화면에 한국어 문구: $korean", korean.isEmpty())
    }

    private fun SemanticsNode.allTexts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.EditableText)?.let { listOf(it.text) }.orEmpty() +
            children.flatMap { it.allTexts() }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SCREENSHOT_SETTLE_MILLIS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "chzzk-localization").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val HANGUL = Regex("[가-힣]")
        const val LOAD_FAILURE_TIMEOUT_MILLIS = 20_000L
        const val SCREENSHOT_SETTLE_MILLIS = 700L
    }
}
