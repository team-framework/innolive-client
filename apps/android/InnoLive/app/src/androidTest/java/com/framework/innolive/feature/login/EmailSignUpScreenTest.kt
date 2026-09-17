package com.framework.innolive.feature.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.framework.innolive.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EmailSignUpScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun fillSignup() {
        rule.onNodeWithText("회원가입").performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("member@example.com")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("password123")
        rule.onAllNodes(hasSetTextAction())[2].performTextInput("password123")
        rule.onNodeWithText("인증 메일 보내기").performScrollTo().performClick()
    }

    @Test
    fun verifiedSignupAuthenticatesAndNavigatesWithoutReenteringCredentials() {
        val signupFinished = CompletableDeferred<Unit>()
        val verificationFinished = CompletableDeferred<Unit>()
        var signups = 0
        var verifications = 0
        var navigations = 0
        rule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen(
                    onBack = {},
                    onLogin = { navigations++ },
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

        fillSignup()
        rule.onNodeWithText("인증 메일 보내는 중…").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, signups); signupFinished.complete(Unit) }
        rule.onNodeWithText("이메일을 확인해 주세요").assertIsDisplayed()
        rule.onNodeWithText("member@example.com").assertIsDisplayed()
        rule.onNodeWithText("인증하고 시작하기").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("012345")
        rule.onNodeWithText("인증하고 시작하기").performClick()
        rule.runOnIdle { assertEquals(1, verifications); verificationFinished.complete(Unit) }
        rule.waitUntil { navigations == 1 }
    }

    @Test
    fun resendStaysOnVerificationAndClearsOnlyTheCode() {
        var resends = 0
        rule.setContent {
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
        rule.onNodeWithText("인증 코드 다시 보내기").performClick()

        rule.waitUntil { resends == 1 }
        rule.onNodeWithText("이메일을 확인해 주세요").assertIsDisplayed()
        rule.onNodeWithText("member@example.com").assertIsDisplayed()
        rule.onNodeWithText("인증 코드를 다시 보냈어요.").assertIsDisplayed()
        rule.onNodeWithText("인증하고 시작하기").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, resends) }
    }

    @Test
    fun verificationBackCancelsPendingSignupAndRestoresSignupForm() {
        var cancellations = 0
        rule.setContent {
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
        rule.onNodeWithContentDescription("뒤로").performClick()

        rule.onNodeWithText("계정 만들기").assertIsDisplayed()
        rule.onNodeWithText("member@example.com").assertIsDisplayed()
        rule.onNodeWithText("인증 메일 보내기").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun pendingVerificationDisablesBackAndNavigatesAfterSuccess() {
        val finish = CompletableDeferred<Unit>()
        var navigations = 0
        rule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen(
                    onBack = {},
                    onLogin = { navigations++ },
                    signIn = { _, _ -> },
                    signUp = { _, _ -> },
                    verifyEmail = { withContext(NonCancellable) { finish.await() } },
                    resendSignup = {},
                )
            }
        }

        fillSignup()
        rule.onNode(hasSetTextAction()).performTextInput("123456")
        rule.onNodeWithText("인증하고 시작하기").performClick()
        rule.onNodeWithContentDescription("뒤로").assertIsNotEnabled()
        rule.runOnIdle { finish.complete(Unit) }
        rule.waitUntil { navigations == 1 }
        rule.onNodeWithText("이메일로 로그인").assertDoesNotExist()
    }
}
