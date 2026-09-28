package com.framework.innolive.feature.login

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.GoogleSignInState
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.theme.MyApplicationTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

private const val TEST_POLICY_URL = "data:text/html,%3Chtml%3E%3Cbody%3E%3Ch1%3EPrivacy%20Policy%3C%2Fh1%3E%3C%2Fbody%3E%3C%2Fhtml%3E"
private const val LONG_TEST_POLICY_URL = "data:text/html,%3Chtml%3E%3Cbody%20style%3D%22height%3A5000px%22%3E%3Ch1%3EPrivacy%20Policy%3C%2Fh1%3E%3C%2Fbody%3E%3C%2Fhtml%3E"

class EmailAuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(@StringRes id: Int, vararg formatArgs: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *formatArgs)

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAccountConsentPolicyUrl provides TEST_POLICY_URL,
                LocalAccountConsentPolicyLoader provides { url -> Uri.decode(url.substringAfter(",")) }) {
                content()
            }
        }
    }

    private fun acceptConsent() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(string(R.string.account_consent_accept)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).performClick()
    }

    @Test
    fun emailContinueOpensSignInAndBackReturnsToLoginOptions() {
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(LoginScreenProps(onLogin = {}, onGoogleLogin = {}))
            }
        }

        composeRule.onNodeWithText(string(R.string.continue_with_email)).performClick()

        composeRule.onNodeWithText(string(R.string.email_sign_in_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.email_sign_in_description)).assertIsDisplayed()
        composeRule.onNodeWithText("name@example.com").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_sign_in)).assertIsNotEnabled()

        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        composeRule.onNodeWithText(string(R.string.continue_with_google)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.continue_with_email)).assertIsDisplayed()
    }

    @Test
    fun emailAuthenticationSwitchesBetweenSignInAndSignUpForms() {
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailAuthScreen(onBack = {})
            }
        }

        composeRule.onNodeWithText(string(R.string.action_sign_up)).performClick()
        acceptConsent()

        composeRule.onNodeWithText(string(R.string.email_sign_up_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.label_password_confirmation)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_send_verification_email))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.already_have_account)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.action_sign_in)).performClick()
        composeRule.onNodeWithText(string(R.string.email_sign_in_title)).assertIsDisplayed()
    }

    @Test
    fun talkBackLabelsUseLocalizedAccessibilityResources() {
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(LoginScreenProps(onLogin = {}, onGoogleLogin = {}))
            }
        }

        composeRule.onNodeWithText(string(R.string.continue_with_email)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            string(R.string.content_description_show_value, string(R.string.label_password)),
        ).performClick()
        composeRule.onNodeWithContentDescription(
            string(R.string.content_description_hide_value, string(R.string.label_password)),
        ).assertIsDisplayed()
    }

    @Test
    fun completedGoogleSignInNavigatesAndAcknowledgesTheViewModelState() {
        var navigations = 0
        var acknowledgements = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(
                    LoginScreenProps(
                        onLogin = { navigations++ },
                        onGoogleLogin = {},
                        onGoogleSignInSuccess = { acknowledgements++ },
                        googleSignInState = GoogleSignInState.Succeeded,
                    ),
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(1, navigations)
            assertEquals(1, acknowledgements)
        }
    }

    @Test
    fun googleSignInWaitsForConsentAndCancelDoesNotStartAuthentication() {
        var attempts = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(LoginScreenProps(onLogin = {}, onGoogleLogin = { attempts++ }))
            }
        }
        composeRule.onNodeWithText(string(R.string.continue_with_google)).performClick()
        composeRule.onNodeWithTag("accountConsent.policy").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.account_consent_cancel)).performClick()
        composeRule.runOnIdle { assertEquals(0, attempts) }
        composeRule.onNodeWithText(string(R.string.continue_with_google)).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(string(R.string.account_consent_accept)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, attempts) }
        composeRule.onNodeWithText(string(R.string.continue_with_google)).performClick()
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun fullPolicyMustBeScrolledBeforeAccepting() {
        var acceptances = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAccountConsentPolicyUrl provides LONG_TEST_POLICY_URL,
                LocalAccountConsentPolicyLoader provides { url -> Uri.decode(url.substringAfter(",")) }) {
                MyApplicationTheme(dynamicColor = false) {
                    AccountConsentDialog(onAccept = { acceptances++ }, onDismiss = {})
                }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("accountConsent.loading").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsNotEnabled()
        val hint = composeRule.onNodeWithText(string(R.string.account_consent_scroll_hint))
        val accept = composeRule.onNodeWithText(string(R.string.account_consent_accept))
        hint.assertIsDisplayed()
        val initialHintBounds = hint.getUnclippedBoundsInRoot()
        val initialButtonBounds = accept.getUnclippedBoundsInRoot()
        val initialPolicyBounds = composeRule.onNodeWithTag("accountConsent.policy").getUnclippedBoundsInRoot()
        repeat(12) {
            if (composeRule.onAllNodes(hasText(string(R.string.account_consent_accept)) and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()) return@repeat
            composeRule.onNodeWithTag("accountConsent.policy").performTouchInput { swipeUp() }
        }
        hint.assertIsDisplayed()
        assertEquals(initialHintBounds, hint.getUnclippedBoundsInRoot())
        assertEquals(initialButtonBounds, accept.getUnclippedBoundsInRoot())
        assertEquals(initialPolicyBounds, composeRule.onNodeWithTag("accountConsent.policy").getUnclippedBoundsInRoot())
        accept.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, acceptances) }
    }

    private fun showPolicyWithFooter(contactHeight: Int = 0, onAccept: () -> Unit = {}) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAccountConsentPolicyUrl provides "https://innolive.studio/ko/privacy",
                LocalAccountConsentPolicyLoader provides {
                    val contactStyle = if (contactHeight > 0) "height:${contactHeight}px" else ""
                    """<html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                        <body><main data-page="policy"><section><article>
                        <h1>Privacy Policy</h1><p>Collection, purpose, retention and rights.</p>
                        </article></section></main><footer>
                        <p style="$contactStyle;font-size:12px">Framework CEO: Chae Geun-yeong / Privacy Officer: Kwon Dae-hyeong / 010-2732-9514 / contact@innolive.studio</p>
                        <img alt="De-Identification" style="height:5000px;width:100px"
                        src="data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%201%201'%3E%3C/svg%3E">
                        </footer></body></html>"""
                },
            ) {
                MyApplicationTheme(dynamicColor = false) {
                    AccountConsentDialog(onAccept = onAccept, onDismiss = {})
                }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("accountConsent.policy").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("accountConsent.loading").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun decorativeWordmarkDoesNotRequireExtraScrolling() {
        var acceptances = 0
        showPolicyWithFooter(onAccept = { acceptances++ })
        // Policy and contact details fit; only the decorative image is excessively tall.
        composeRule.onNodeWithText(string(R.string.account_consent_scroll_hint)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, acceptances) }
    }

    @Test
    fun footerContactInformationRemainsInTheScrollDocument() {
        showPolicyWithFooter(contactHeight = 5000)
        // Hiding the entire footer would incorrectly enable consent without reading its details.
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsNotEnabled()
    }

    @Test
    fun policyLanguageLinksStayInDialogAndResetScrollWithWebsiteHeaderHidden() {
        val loadedUrls = java.util.concurrent.CopyOnWriteArrayList<String>()
        var acceptances = 0
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAccountConsentPolicyUrl provides "https://innolive.studio/ko/privacy",
                LocalAccountConsentPolicyLoader provides { url ->
                    loadedUrls.add(url)
                    val longBody = if (url.contains("/ko/")) "" else "<div style='height:5000px'>Full policy</div>"
                    """<html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                        <body style="margin:0">
                        <header data-fixed-header style="position:fixed;top:0;width:100%;height:200px;z-index:999;background:black">Website header</header>
                        <div style="padding-top:101px"><main data-page="policy"><section style="padding-top:240px">
                        <a style="display:block;height:48px" href="https://innolive.studio/en/privacy">English</a>
                        <a style="display:block;height:48px" href="https://innolive.studio/ja/privacy">日本語</a>
                        $longBody</section></main></div></body></html>"""
                },
            ) {
                MyApplicationTheme(dynamicColor = false) {
                    AccountConsentDialog(onAccept = { acceptances++ }, onDismiss = {})
                }
            }
        }
        fun waitForDocument(url: String) {
            composeRule.waitUntil(10_000) {
                loadedUrls.lastOrNull() == url &&
                    composeRule.onAllNodesWithTag("accountConsent.loading").fetchSemanticsNodes().isEmpty()
            }
        }
        waitForDocument("https://innolive.studio/ko/privacy")
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsEnabled()
        // Real taps exercise WebView navigation. The fixed header would cover these links.
        composeRule.onNodeWithTag("accountConsent.policy").performTouchInput {
            click(Offset(50f * density, 40f * density))
        }
        waitForDocument("https://innolive.studio/en/privacy")
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsNotEnabled()
        composeRule.onNodeWithTag("accountConsent.policy").performTouchInput {
            click(Offset(50f * density, 88f * density))
        }
        waitForDocument("https://innolive.studio/ja/privacy")
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsNotEnabled()
        repeat(15) {
            if (composeRule.onAllNodes(hasText(string(R.string.account_consent_accept)) and isEnabled())
                    .fetchSemanticsNodes().isEmpty()) {
                composeRule.onNodeWithTag("accountConsent.policy").performTouchInput { swipeUp() }
            }
        }
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("https://innolive.studio/ko/privacy", "https://innolive.studio/en/privacy", "https://innolive.studio/ja/privacy"), loadedUrls.toList())
            assertEquals(1, acceptances)
        }
    }

    @Test
    fun failedPolicyLoadAndRetryNeverAllowConsent() {
        var acceptances = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAccountConsentPolicyUrl provides "https://innolive.studio/ko/privacy",
                LocalAccountConsentPolicyLoader provides { throw java.io.IOException("Policy unavailable") }) {
                MyApplicationTheme(dynamicColor = false) {
                    AccountConsentDialog(onAccept = { acceptances++ }, onDismiss = {})
                }
            }
        }
        val error = string(R.string.account_consent_load_failed)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(error)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.action_retry)).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(error)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(0, acceptances) }
    }

    @Test
    fun pendingLoginPreventsDuplicateSubmissionAndNavigatesAfterSuccess() {
        val response = CompletableDeferred<Unit>()
        var calls = 0
        var navigations = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(LoginScreenProps(onLogin = { navigations++ }, onGoogleLogin = {},
                    onEmailLogin = { email, password ->
                        assertEquals("member@example.com", email)
                        assertEquals(" pass word ", password)
                        calls++
                        response.await()
                    }))
            }
        }
        composeRule.onNodeWithText(string(R.string.continue_with_email)).performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(" member@example.com ")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput(" pass word ")
        composeRule.onNodeWithText(string(R.string.action_sign_in)).assertIsEnabled().performClick()
        composeRule.onNodeWithText(string(R.string.action_signing_in)).assertIsNotEnabled().performClick()
        composeRule.onNodeWithText(string(R.string.action_sign_up)).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, calls); response.complete(Unit) }
        composeRule.waitUntil { navigations == 1 }
    }

    @Test
    fun failureAllowsRetryWithoutNavigating() {
        var calls = 0
        var navigations = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(LoginScreenProps(onLogin = { navigations++ }, onGoogleLogin = {},
                    onEmailLogin = { _, _ ->
                        calls++
                        throw EmailSignInException(UiText.Resource(R.string.error_email_credentials))
                    }))
            }
        }
        composeRule.onNodeWithText(string(R.string.continue_with_email)).performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("member@example.com")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("wrong-password")
        composeRule.onNodeWithText(string(R.string.action_sign_in)).performClick()
        composeRule.onNodeWithText(string(R.string.error_email_credentials)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_sign_in)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(2, calls); assertEquals(0, navigations) }
    }

    @Test
    fun leavingEmailScreenCancelsPendingLogin() {
        val response = CompletableDeferred<Unit>()
        var navigations = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(LoginScreenProps(onLogin = { navigations++ }, onGoogleLogin = {},
                    onEmailLogin = { _, _ -> response.await() }))
            }
        }
        composeRule.onNodeWithText(string(R.string.continue_with_email)).performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("member@example.com")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("password")
        composeRule.onNodeWithText(string(R.string.action_sign_in)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        composeRule.runOnIdle { response.complete(Unit) }
        composeRule.onNodeWithText(string(R.string.continue_with_google)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, navigations) }
    }
}
