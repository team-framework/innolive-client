package com.framework.innolive.feature.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test

class EmailAuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emailContinueOpensSignInAndBackReturnsToLoginOptions() {
        composeRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                LoginScreen(LoginScreenProps(onLogin = {}, onGoogleLogin = {}))
            }
        }

        composeRule.onNodeWithText("이메일로 계속하기").performClick()

        composeRule.onNodeWithText("이메일로 로그인").assertIsDisplayed()
        composeRule.onNodeWithText("InnoLive에서 라이브를 이어가세요.").assertIsDisplayed()
        composeRule.onNodeWithText("name@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("로그인").assertIsNotEnabled()

        composeRule.onNodeWithContentDescription("뒤로").performClick()
        composeRule.onNodeWithText("Google로 계속하기").assertIsDisplayed()
        composeRule.onNodeWithText("이메일로 계속하기").assertIsDisplayed()
    }

    @Test
    fun emailAuthenticationSwitchesBetweenSignInAndSignUpForms() {
        composeRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                EmailAuthScreen(onBack = {})
            }
        }

        composeRule.onNodeWithText("회원가입").performClick()

        composeRule.onNodeWithText("계정 만들기").assertIsDisplayed()
        composeRule.onNodeWithText("비밀번호 확인").assertIsDisplayed()
        composeRule.onNodeWithText("인증 메일 보내기").assertIsNotEnabled()
        composeRule.onNodeWithText("이미 계정이 있으신가요?").assertIsDisplayed()

        composeRule.onNodeWithText("로그인").performClick()
        composeRule.onNodeWithText("이메일로 로그인").assertIsDisplayed()
    }
}
