package com.framework.innolive.feature.login

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.framework.innolive.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EmailSignUpScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun fillSignup() {
        rule.onNodeWithText("회원가입").performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("member@example.com")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("password123")
        rule.onAllNodes(hasSetTextAction())[2].performTextInput("password123")
        rule.onNodeWithText("인증 메일 보내기").performScrollTo().performClick()
    }

    @Test
    fun signupVerifiesCodeThenReturnsToLoginWithoutAutomaticAuthentication() {
        val sent = CompletableDeferred<String>()
        val verified = CompletableDeferred<Unit>()
        var sends = 0
        var verifies = 0
        var logins = 0
        rule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen({}, { logins++ }, { _, _ -> logins++ },
                    signUp = { _, _ -> sends++; sent.await() },
                    verifyEmail = { token, code ->
                        assertEquals("signup-token", token)
                        assertEquals("012345", code)
                        verifies++
                        verified.await()
                    })
            }
        }
        fillSignup()
        rule.onNodeWithText("인증 메일 보내는 중…").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, sends); sent.complete("signup-token") }
        rule.onNodeWithText("이메일 인증").assertIsDisplayed()
        rule.onNodeWithText("인증 완료").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("012345")
        rule.onNodeWithText("인증 완료").performClick()
        rule.onNodeWithText("인증 확인 중…").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, verifies); verified.complete(Unit) }
        rule.onNodeWithText("이메일로 로그인").assertIsDisplayed()
        rule.onNodeWithText("회원가입이 완료됐습니다. 이메일로 로그인해 주세요.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("member@example.com").assertExists()
        rule.runOnIdle { assertEquals(0, logins) }
    }

    @Test
    fun invalidCodeCanRetryAndRequestAnotherEmail() {
        var verifies = 0
        rule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen({}, {}, { _, _ -> },
                    signUp = { _, _ -> "signup-token" },
                    verifyEmail = { _, _ -> verifies++; throw EmailSignUpException("인증 코드가 틀렸거나 만료됐습니다.") })
            }
        }
        fillSignup()
        rule.onNode(hasSetTextAction()).performTextInput("123456")
        rule.onNodeWithText("인증 완료").performClick()
        rule.onNodeWithText("인증 코드가 틀렸거나 만료됐습니다.").assertIsDisplayed()
        rule.onNodeWithText("인증 완료").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(2, verifies) }
        rule.onNodeWithText("인증 메일 다시 요청").performScrollTo().performClick()
        rule.onNodeWithText("계정 만들기").assertIsDisplayed()
        rule.onNodeWithText("인증 메일 보내기").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun cancelledLateVerificationCannotShowSignupSuccess() {
        val finish = CompletableDeferred<Unit>()
        rule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailLoginScreen({}, {}, { _, _ -> },
                    signUp = { _, _ -> "signup-token" },
                    verifyEmail = { _, _ -> withContext(NonCancellable) { finish.await() } })
            }
        }
        fillSignup()
        rule.onNode(hasSetTextAction()).performTextInput("123456")
        rule.onNodeWithText("인증 완료").performClick()
        rule.onNodeWithText("로그인으로 돌아가기").performScrollTo().performClick()
        rule.runOnIdle { finish.complete(Unit) }
        rule.onNodeWithText("이메일로 로그인").assertIsDisplayed()
        rule.onNodeWithText("회원가입이 완료됐습니다. 이메일로 로그인해 주세요.").assertDoesNotExist()
    }
}
