package com.framework.innolive.feature.login

import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.annotation.StringRes
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.theme.MyApplicationTheme
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private const val TEST_POLICY_URL = "data:text/html,%3Chtml%3E%3Cbody%3E%3Ch1%3EPrivacy%20Policy%3C%2Fh1%3E%3C%2Fbody%3E%3C%2Fhtml%3E"

class EmailSignUpScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun string(@StringRes id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun setTestContent(content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalAccountConsentPolicyUrl provides TEST_POLICY_URL,
                LocalAccountConsentPolicyLoader provides { url -> Uri.decode(url.substringAfter(",")) }) {
                content()
            }
        }
    }

    private fun acceptConsent() {
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText(string(R.string.account_consent_accept)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(string(R.string.account_consent_accept)).assertIsEnabled().performClick()
    }

    private fun fillSignup(waitForVerification: Boolean = true) {
        rule.onNodeWithText(string(R.string.action_sign_up)).performScrollTo().performClick()
        acceptConsent()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("member@example.com")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("password123")
        rule.onAllNodes(hasSetTextAction())[2].performTextInput("password123")
        val sendLabel = string(R.string.action_send_verification_email)
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText(sendLabel) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(sendLabel)
            .assertIsEnabled()
            .performScrollTo()
            .performClick()
        val expectedLabel = if (waitForVerification) {
            string(R.string.verification_title)
        } else {
            string(R.string.action_sending_verification_email)
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(expectedLabel).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun signupShowsConsentBeforeInputAndCancelNeverSubmits() {
        var signups = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailAuthScreen(onBack = {}, onSignUp = { _, _ -> signups++ })
            }
        }
        rule.onNodeWithText(string(R.string.action_sign_up)).performClick()
        rule.onNodeWithText(string(R.string.account_consent_cancel)).performClick()
        rule.onNodeWithText(string(R.string.email_sign_in_title)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, signups) }
        rule.onNodeWithText(string(R.string.action_sign_up)).performClick()
        acceptConsent()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("member@example.com")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("password123")
        rule.onAllNodes(hasSetTextAction())[2].performTextInput("password123")
        rule.onNodeWithText(string(R.string.action_send_verification_email)).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, signups) }
        rule.onNodeWithText(string(R.string.account_consent_accept)).assertDoesNotExist()
        // A failed request may be retried within the same form without another dialog.
        rule.onNodeWithText(string(R.string.action_send_verification_email)).performClick()
        rule.runOnIdle { assertEquals(2, signups) }
        // Leaving the signup flow clears the in-memory acceptance.
        rule.onNodeWithText(string(R.string.action_sign_in)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.action_sign_up)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.account_consent_cancel)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, signups) }
    }

    @Test
    fun verifiedSignupAuthenticatesAndNavigatesWithoutReenteringCredentials() {
        val signupFinished = CompletableDeferred<Unit>()
        val verificationFinished = CompletableDeferred<Unit>()
        var signups = 0
        var verifications = 0
        var navigations = 0
        val showingLiveScreen = mutableStateOf(false)
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                if (showingLiveScreen.value) {
                    Text("라이브 화면")
                } else {
                    EmailLoginScreen(
                        onBack = {},
                        onLogin = { navigations++; showingLiveScreen.value = true },
                        signIn = { _, _ -> },
                        signUp = { _, _ -> signups++; signupFinished.await() },
                        verifyEmail = { code ->
                            assertEquals("012345", code)
                            verifications++
                            verificationFinished.await()
                        },
                        resendSignup = {},
                    )
                }
            }
        }

        fillSignup(waitForVerification = false)
        rule.onNodeWithText(string(R.string.action_sending_verification_email)).assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, signups); signupFinished.complete(Unit) }
        rule.onNodeWithText(string(R.string.verification_title)).assertIsDisplayed()
        rule.onNodeWithText("member@example.com").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_verify_and_start)).assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("012345")
        rule.onNodeWithText(string(R.string.action_verify_and_start)).performClick()
        rule.runOnIdle { assertEquals(1, verifications); verificationFinished.complete(Unit) }
        rule.waitUntil { navigations == 1 }
        rule.onNodeWithText("라이브 화면").assertIsDisplayed()
    }

    @Test
    fun resendStaysOnVerificationAndClearsOnlyTheCode() {
        var resends = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen(
                    onBack = {},
                    onLogin = {},
                    signIn = { _, _ -> },
                    signUp = { _, _ -> },
                    verifyEmail = {},
                    resendSignup = { resends++ },
                )
            }
        }

        fillSignup()
        rule.onNode(hasSetTextAction()).performTextInput("123456")
        rule.onNodeWithText(string(R.string.action_resend_verification)).performClick()

        rule.waitUntil { resends == 1 }
        rule.onNodeWithText(string(R.string.verification_title)).assertIsDisplayed()
        rule.onNodeWithText("member@example.com").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.email_hint_resent)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_verify_and_start)).assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, resends) }
    }

    @Test
    fun resendFeedbackDoesNotMoveResendButton() {
        val firstResendFinished = CompletableDeferred<Unit>()
        val resendErrors = listOf(
            UiText.Resource(R.string.error_too_many_requests),
            UiText.Resource(R.string.error_email_signup_expired),
            UiText.Resource(R.string.error_email_already_registered),
            UiText.Resource(R.string.error_email_verification_code),
            UiText.Resource(R.string.error_email_delivery),
            UiText.Resource(R.string.error_email_signup_credentials),
            UiText.Resource(R.string.error_email_credentials),
            UiText.Resource(R.string.error_request_failed),
            UiText.Resource(R.string.error_sign_in_attempts),
            UiText.Resource(R.string.error_sign_in_unavailable),
            UiText.Resource(R.string.error_request_connection_failed),
        )
        var resendAttempts = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen(
                    onBack = {},
                    onLogin = {},
                    signIn = { _, _ -> },
                    signUp = { _, _ -> },
                    verifyEmail = {},
                    resendSignup = {
                        resendAttempts++
                        if (resendAttempts == 1) {
                            firstResendFinished.await()
                        } else {
                            throw EmailSignUpException(resendErrors[resendAttempts - 2])
                        }
                    },
                )
            }
        }

        fillSignup()
        val resendButton = rule.onNodeWithText(string(R.string.action_resend_verification))
        val initialTop = resendButton.getUnclippedBoundsInRoot().top
        val feedbackTop = rule.onNodeWithText(string(R.string.email_hint_initial))
            .getUnclippedBoundsInRoot().top

        resendButton.performClick()
        val sendingFeedback = rule.onNodeWithText(string(R.string.email_hint_sending))
        sendingFeedback.assertIsDisplayed()
        assertEquals(feedbackTop, sendingFeedback.getUnclippedBoundsInRoot().top)
        resendButton.assertIsNotEnabled()
        assertEquals(initialTop, resendButton.getUnclippedBoundsInRoot().top)

        rule.runOnIdle { firstResendFinished.complete(Unit) }
        val successFeedback = rule.onNodeWithText(string(R.string.email_hint_resent))
        successFeedback.assertIsDisplayed()
        assertEquals(feedbackTop, successFeedback.getUnclippedBoundsInRoot().top)
        assertEquals(initialTop, resendButton.getUnclippedBoundsInRoot().top)

        resendErrors.forEach { error ->
            resendButton.performClick()
            val errorFeedback = rule.onNodeWithText(string(error.id))
            errorFeedback.assertIsDisplayed()
            assertEquals(feedbackTop, errorFeedback.getUnclippedBoundsInRoot().top)
            assertEquals(initialTop, resendButton.getUnclippedBoundsInRoot().top)
        }
        rule.runOnIdle { assertEquals(resendErrors.size + 1, resendAttempts) }
    }

    @Test
    fun verificationBackCancelsPendingSignupAndRestoresSignupForm() {
        var cancellations = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen(
                    onBack = {},
                    onLogin = {},
                    signIn = { _, _ -> },
                    signUp = { _, _ -> },
                    verifyEmail = {},
                    resendSignup = {},
                    cancelSignup = { cancellations++ },
                )
            }
        }

        fillSignup()
        rule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        acceptConsent()

        rule.onNodeWithText(string(R.string.email_sign_up_title)).assertIsDisplayed()
        rule.onNodeWithText("member@example.com").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_send_verification_email)).assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun pendingVerificationDisablesBackAndNavigatesAfterSuccess() {
        val finish = CompletableDeferred<Unit>()
        val showingLiveScreen = mutableStateOf(false)
        var navigations = 0
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                if (showingLiveScreen.value) {
                    Text("라이브 화면")
                } else {
                    EmailLoginScreen(
                        onBack = {},
                        onLogin = {
                            navigations++
                            showingLiveScreen.value = true
                        },
                        signIn = { _, _ -> },
                        signUp = { _, _ -> },
                        verifyEmail = { withContext(NonCancellable) { finish.await() } },
                        resendSignup = {},
                    )
                }
            }
        }

        fillSignup()
        rule.onNode(hasSetTextAction()).performTextInput("123456")
        rule.onNodeWithText(string(R.string.action_verify_and_start)).performClick()
        rule.onNodeWithContentDescription(string(R.string.action_back)).assertIsNotEnabled()
        rule.runOnIdle { finish.complete(Unit) }
        rule.onNodeWithText("라이브 화면").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.email_sign_in_title)).assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, navigations) }
    }

    @Test
    fun signupRequestCannotNavigateBackBeforeItFinishes() {
        val finish = CompletableDeferred<Unit>()
        var signups = 0
        var cancellations = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                EmailLoginScreen(
                    onBack = {},
                    onLogin = {},
                    signIn = { _, _ -> },
                    signUp = { _, _ -> signups++; finish.await() },
                    verifyEmail = {},
                    resendSignup = {},
                    cancelSignup = { cancellations++ },
                )
            }
        }

        fillSignup(waitForVerification = false)
        rule.onNodeWithContentDescription(string(R.string.action_back)).assertIsNotEnabled()
        rule.runOnIdle { checkNotNull(backDispatcher).onBackPressed() }
        rule.onNodeWithText(string(R.string.email_sign_up_title)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, signups); assertEquals(0, cancellations); finish.complete(Unit) }
        rule.onNodeWithText(string(R.string.verification_title)).assertIsDisplayed()
    }

    @Test
    fun restoredVerificationWithoutPendingSignupReturnsToSignupForm() {
        val restoration = StateRestorationTester(rule)
        var hasPendingSignup = false
        restoration.setContent {
            CompositionLocalProvider(LocalAccountConsentPolicyUrl provides TEST_POLICY_URL,
                LocalAccountConsentPolicyLoader provides { url -> Uri.decode(url.substringAfter(",")) }) {
                MyApplicationTheme(dynamicColor = false) {
                    EmailLoginScreen(
                        onBack = {},
                        onLogin = {},
                        signIn = { _, _ -> },
                        signUp = { _, _ -> hasPendingSignup = true },
                        verifyEmail = {},
                        resendSignup = {},
                        hasPendingSignup = { hasPendingSignup },
                    )
                }
            }
        }

        fillSignup()
        rule.onNodeWithText(string(R.string.verification_title)).assertIsDisplayed()
        rule.runOnIdle { hasPendingSignup = false }
        restoration.emulateSavedInstanceStateRestore()
        acceptConsent()

        rule.onNodeWithText(string(R.string.email_sign_up_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.verification_title)).assertDoesNotExist()
    }

    @Test
    fun verifiedEmailShowsLoginOnlyRetryAfterAuthenticationFailure() {
        val verified = mutableStateOf(false)
        var verifications = 0
        var logins = 0
        var resends = 0
        val showingLiveScreen = mutableStateOf(false)
        setTestContent {
            MyApplicationTheme(dynamicColor = false) {
                if (showingLiveScreen.value) {
                    Text("라이브 화면")
                } else {
                    EmailLoginScreen(
                        onBack = {},
                        onLogin = { logins++; showingLiveScreen.value = true },
                        signIn = { _, _ -> },
                        signUp = { _, _ -> },
                        verifyEmail = {
                            if (!verified.value) {
                                verified.value = true
                                verifications++
                                throw EmailSignInException(
                                    UiText.Resource(R.string.error_request_failed),
                                )
                            }
                        },
                        resendSignup = { resends++ },
                        isSignupVerified = { verified.value },
                    )
                }
            }
        }

        fillSignup()
        rule.onNode(hasSetTextAction()).performTextInput("123456")
        rule.onNodeWithText(string(R.string.action_verify_and_start)).performClick()
        rule.onNodeWithText(string(R.string.verification_complete_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_sign_in_again)).assertIsDisplayed().performClick()
        rule.waitUntil { logins == 1 }
        rule.onNodeWithText("라이브 화면").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, verifications)
            assertEquals(0, resends)
        }
    }
}
