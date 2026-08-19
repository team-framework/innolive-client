package com.framework.innolive.app

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthenticatedNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun savedSessionOpensLiveUntilLogout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = GoogleSessionStore(context)
        val existingSession = store.load()

        try {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.CAMERA,
            )
            store.save(
                GoogleSessionStore.Session(
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    tokenType = "Bearer",
                    expiresIn = 3600,
                    refreshExpiresIn = 86400,
                    profileName = "InnoLive User",
                    profileEmail = "user@example.com",
                ),
            )
            composeRule.activityRule.scenario.recreate()

            composeRule.onNodeWithText("user@example.com").assertIsDisplayed()
            composeRule.onNodeWithText("로그아웃").performClick()
            composeRule.onNodeWithText("Google로 계속하기").assertIsDisplayed()
        } finally {
            store.clear()
            existingSession?.let(store::save)
        }
    }
}
