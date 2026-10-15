package com.framework.innolive.feature.live

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.components.ChzzkOAuthDialog
import com.framework.innolive.BuildConfig
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URLEncoder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChzzkOAuthDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val redirect = "https://oauth-fixture.invalid/callback"

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun webView(): WebView? {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        compose.runOnIdle {
            checkNotNull(webView()).evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue("WebView JavaScript callback was not received", done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun showFixture(onCode: (String) -> Unit = {}, onFailure: (UiText) -> Unit = {}, onDismiss: () -> Unit = {}) {
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body>oauth fixture</body></html>"
        val url = "data:text/html;charset=utf-8," + URLEncoder.encode(html, "UTF-8").replace("+", "%20")
        compose.setContent {
            MaterialTheme {
                ChzzkOAuthDialog(ChzzkOAuthConfig(url, redirect), "expected", onCode, onFailure, onDismiss)
            }
        }
    }

    @Test fun pageFillsTheAvailableViewportAndLeavesCancelVisible() {
        showFixture()
        compose.waitUntil(10_000) { evaluate("document.readyState === 'complete'") == "true" }
        compose.runOnIdle {
            val view = checkNotNull(webView())
            assertTrue("OAuth WebView has no native viewport", view.height > 0)
            assertEquals("OAuth WebView did not fill its reserved viewport", (view.parent as View).height, view.height)
        }
        assertEquals("true", evaluate("innerHeight > 0 && document.body.innerText.includes('oauth fixture')"))
        compose.onNodeWithText(string(R.string.chzzk_oauth_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_cancel)).assertIsDisplayed()
    }

    @Test fun validCallbackIsReturnedOnlyOnce() {
        val codes = mutableListOf<String>()
        val failures = mutableListOf<UiText>()
        showFixture(onCode = { codes.add(it) }, onFailure = { failures.add(it) })
        compose.waitUntil(10_000) { evaluate("document.readyState === 'complete'") == "true" }
        evaluate("location.href = '$redirect?code=fixture&state=expected'; true")
        compose.waitUntil(10_000) { codes.size == 1 }
        compose.runOnIdle { assertEquals(listOf("fixture"), codes); assertTrue(failures.isEmpty()) }
        // onPageStarted and shouldOverrideUrlLoading may both observe the callback.
        evaluate("location.href = '$redirect?code=duplicate&state=wrong'; true")
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("fixture"), codes); assertTrue(failures.isEmpty()) }
    }

    @Test fun wrongStateNeverReturnsAnAuthorizationCode() {
        val codes = mutableListOf<String>()
        val failures = mutableListOf<UiText>()
        showFixture(onCode = { codes.add(it) }, onFailure = { failures.add(it) })
        compose.waitUntil(10_000) { evaluate("document.readyState === 'complete'") == "true" }
        evaluate("location.href = '$redirect?code=fixture&state=wrong'; true")
        compose.waitUntil(10_000) { failures.size == 1 }
        compose.runOnIdle { assertTrue(codes.isEmpty()); assertEquals(UiText.Resource(R.string.chzzk_oauth_invalid_callback), failures.single()) }
    }

    @Test fun networkFailureShowsRecoveryAndCanBeCancelled() {
        var dismissed = 0
        var returnedCode = false
        compose.setContent {
            MaterialTheme {
                ChzzkOAuthDialog(
                    ChzzkOAuthConfig("https://127.0.0.1:1/oauth", redirect), "expected",
                    onCode = { returnedCode = true }, onFailure = {}, onDismiss = { dismissed++ },
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.chzzk_oauth_load_failed))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(string(R.string.action_retry)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, dismissed); assertTrue(!returnedCode) }
    }

    @Test fun configuredOAuthPageRendersOnTheDevice() {
        // Explicit opt-in: normal regression tests never contact the configured server.
        assumeTrue(InstrumentationRegistry.getArguments().getString("chzzkLiveOAuthSmoke") == "true")
        val state = newChzzkOAuthState()
        val config = ChzzkApi(BuildConfig.INNOLIVE_SERVER_URL).use { api -> runBlocking { api.config(state) } }
        compose.setContent {
            MaterialTheme { ChzzkOAuthDialog(config, state, onCode = {}, onFailure = {}, onDismiss = {}) }
        }
        compose.waitUntil(20_000) {
            evaluate("document.readyState === 'complete' && innerHeight > 0 && document.body.innerText.length > 0") == "true"
        }
        compose.onNodeWithText(string(R.string.chzzk_oauth_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_cancel)).assertIsDisplayed()
        evaluate("Array.from(document.querySelectorAll('button')).find(button => button.innerText.trim() === '확인')?.click(); true")
        compose.waitUntil(20_000) {
            evaluate("location.hostname === 'nid.naver.com' && document.readyState === 'complete' && innerHeight > 0") == "true"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
            File(instrumentation.targetContext.cacheDir, "chzzk-oauth-smoke.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }
}
