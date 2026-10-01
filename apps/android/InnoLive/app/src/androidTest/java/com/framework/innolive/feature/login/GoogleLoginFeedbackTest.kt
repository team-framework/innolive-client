package com.framework.innolive.feature.login

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.GoogleSignInController
import com.framework.innolive.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GoogleLoginFeedbackTest {
    @get:Rule val composeRule = createComposeRule()

    private fun string(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun acceptConsent() {
        composeRule.onNodeWithText(string(R.string.continue_with_google)).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(string(R.string.account_consent_accept)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).performClick()
    }

    private fun showLogin(authenticate: suspend () -> Unit, onLogin: () -> Unit = {}) {
        composeRule.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val controller = remember { GoogleSignInController(scope) { authenticate() } }
            val state by controller.state.collectAsState()
            CompositionLocalProvider(
                LocalAccountConsentPolicyUrl provides "data:text/html,test-policy",
                LocalAccountConsentPolicyLoader provides { "<html><body>Test privacy policy</body></html>" },
            ) {
                MyApplicationTheme(dynamicColor = false) {
                    LoginScreen(LoginScreenProps(
                        onLogin = onLogin,
                        onGoogleLogin = { controller.start(context) },
                        onGoogleSignInSuccess = controller::acknowledgeSuccess,
                        googleSignInState = state,
                    ))
                }
            }
        }
    }

    @Test
    fun pendingGoogleResultDisplaysProgressAndPreventsDuplicateRequests() {
        val completion = CompletableDeferred<Unit>()
        var requests = 0
        showLogin(authenticate = { requests++; completion.await() })
        acceptConsent()

        composeRule.onNodeWithText(string(R.string.action_signing_in)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.continue_with_google)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.continue_with_email)).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, requests) }
        completion.complete(Unit)
    }

    @Test
    fun providerCancellationShowsFeedbackAndRetryCanCompleteLogin() {
        var requests = 0
        var navigations = 0
        showLogin(authenticate = {
            requests++
            if (requests == 1) {
                // Google Play services may report account reauthentication failures as cancellation.
                throw GetCredentialCancellationException("16: Account reauth failed")
            }
        }, onLogin = { navigations++ })
        acceptConsent()

        composeRule.onNodeWithText(string(R.string.google_login_failed)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, navigations) }
        composeRule.onNodeWithText(string(R.string.continue_with_google)).performClick()
        composeRule.onNodeWithText(string(R.string.account_consent_accept)).assertDoesNotExist()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(2, requests)
            assertEquals(1, navigations)
        }
    }
}
