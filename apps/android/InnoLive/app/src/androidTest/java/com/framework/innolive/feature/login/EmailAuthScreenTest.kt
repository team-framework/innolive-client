package com.framework.innolive.feature.login

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.GoogleSignInState
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.theme.MyApplicationTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class EmailAuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(@StringRes id: Int, vararg formatArgs: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *formatArgs)

    @Test
    fun emailContinueOpensSignInAndBackReturnsToLoginOptions() {
        composeRule.setContent {
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
        composeRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailAuthScreen(onBack = {})
            }
        }

        composeRule.onNodeWithText(string(R.string.action_sign_up)).performClick()

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
        composeRule.setContent {
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
        composeRule.setContent {
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
    fun pendingLoginPreventsDuplicateSubmissionAndNavigatesAfterSuccess() {
        val response = CompletableDeferred<Unit>()
        var calls = 0
        var navigations = 0
        composeRule.setContent {
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
        composeRule.setContent {
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
        composeRule.setContent {
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
