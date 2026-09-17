package com.framework.innolive.feature.login

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
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
        val showingLiveScreen = mutableStateOf(false)
        rule.setContent {
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
        rule.onNodeWithText("라이브 화면").assertIsDisplayed()
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
    fun resendFeedbackDoesNotMoveResendButton() {
        val firstResendFinished = CompletableDeferred<Unit>()
        val resendErrors = listOf(
            "요청이 많습니다. 잠시 후 다시 시도해 주세요.",
            "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요.",
            "이미 가입된 이메일입니다. 로그인해 주세요.",
            "인증 코드가 올바르지 않거나 만료됐습니다.",
            "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "이메일과 비밀번호를 확인해 주세요.",
            "이메일 또는 비밀번호를 확인해 주세요.",
            "요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.",
            "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.",
            "지금은 이메일로 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
            "요청을 완료하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.",
        )
        var resendAttempts = 0
        rule.setContent {
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
        val resendButton = rule.onNodeWithText("인증 코드 다시 보내기")
        val initialTop = resendButton.getUnclippedBoundsInRoot().top
        val feedbackTop = rule.onNodeWithText("메일이 오지 않았다면 스팸함을 확인해 주세요.")
            .getUnclippedBoundsInRoot().top

        resendButton.performClick()
        val sendingFeedback = rule.onNodeWithText("인증 메일 보내는 중...")
        sendingFeedback.assertIsDisplayed()
        assertEquals(feedbackTop, sendingFeedback.getUnclippedBoundsInRoot().top)
        resendButton.assertIsNotEnabled()
        assertEquals(initialTop, resendButton.getUnclippedBoundsInRoot().top)

        rule.runOnIdle { firstResendFinished.complete(Unit) }
        val successFeedback = rule.onNodeWithText("인증 코드를 다시 보냈어요.")
        successFeedback.assertIsDisplayed()
        assertEquals(feedbackTop, successFeedback.getUnclippedBoundsInRoot().top)
        assertEquals(initialTop, resendButton.getUnclippedBoundsInRoot().top)

        resendErrors.forEach { message ->
            resendButton.performClick()
            val errorFeedback = rule.onNodeWithText(message)
            errorFeedback.assertIsDisplayed()
            assertEquals(feedbackTop, errorFeedback.getUnclippedBoundsInRoot().top)
            assertEquals(initialTop, resendButton.getUnclippedBoundsInRoot().top)
        }
        rule.runOnIdle { assertEquals(resendErrors.size + 1, resendAttempts) }
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
        val showingLiveScreen = mutableStateOf(false)
        var navigations = 0
        rule.setContent {
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
        rule.onNodeWithText("인증하고 시작하기").performClick()
        rule.onNodeWithContentDescription("뒤로").assertIsNotEnabled()
        rule.runOnIdle { finish.complete(Unit) }
        rule.onNodeWithText("라이브 화면").assertIsDisplayed()
        rule.onNodeWithText("이메일로 로그인").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, navigations) }
    }

    @Test
    fun signupRequestCannotNavigateBackBeforeItFinishes() {
        val finish = CompletableDeferred<Unit>()
        var signups = 0
        var cancellations = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
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

        fillSignup()
        rule.onNodeWithContentDescription("뒤로").assertIsNotEnabled()
        rule.runOnIdle { checkNotNull(backDispatcher).onBackPressed() }
        rule.onNodeWithText("계정 만들기").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, signups); assertEquals(0, cancellations); finish.complete(Unit) }
        rule.onNodeWithText("이메일을 확인해 주세요").assertIsDisplayed()
    }

    @Test
    fun restoredVerificationWithoutPendingSignupReturnsToSignupForm() {
        val restoration = StateRestorationTester(rule)
        var hasPendingSignup = false
        restoration.setContent {
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

        fillSignup()
        rule.onNodeWithText("이메일을 확인해 주세요").assertIsDisplayed()
        rule.runOnIdle { hasPendingSignup = false }
        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("계정 만들기").assertIsDisplayed()
        rule.onNodeWithText("이메일을 확인해 주세요").assertDoesNotExist()
    }

    @Test
    fun verifiedEmailShowsLoginOnlyRetryAfterAuthenticationFailure() {
        val verified = mutableStateOf(false)
        var verifications = 0
        var logins = 0
        var resends = 0
        val showingLiveScreen = mutableStateOf(false)
        rule.setContent {
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
                                throw EmailSignInException("로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.")
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
        rule.onNodeWithText("인증하고 시작하기").performClick()
        rule.onNodeWithText("이메일 인증 완료").assertIsDisplayed()
        rule.onNodeWithText("로그인 다시 시도").assertIsDisplayed().performClick()
        rule.waitUntil { logins == 1 }
        rule.onNodeWithText("라이브 화면").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, verifications)
            assertEquals(0, resends)
        }
    }
}
