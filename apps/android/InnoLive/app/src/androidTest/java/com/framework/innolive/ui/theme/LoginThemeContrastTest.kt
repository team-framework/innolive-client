package com.framework.innolive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.framework.innolive.feature.login.LoginScreen
import com.framework.innolive.feature.login.LoginScreenProps
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LoginThemeContrastTest(private val darkTheme: Boolean, private val dynamicColor: Boolean) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loginHeadingContrastsWithItsBackground() {
        var background = Color.Unspecified
        composeRule.setContent {
            MyApplicationTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                background = MaterialTheme.colorScheme.background
                Surface(color = background) {
                    LoginScreen(LoginScreenProps(onLogin = {}, onGoogleLogin = {}))
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("라이브 방송을 안전하게\n만드는 쉬운 방법")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        composeRule.runOnIdle {
            val text = layouts.single().layoutInput.style.color
            val ratio = (maxOf(text.luminance(), background.luminance()) + 0.05f) /
                (minOf(text.luminance(), background.luminance()) + 0.05f)
            assertTrue("Login heading contrast was $ratio", ratio >= 4.5f)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "dark={0}, dynamic={1}")
        fun themes() = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true))
    }
}
