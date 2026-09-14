package com.framework.innolive.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthenticatedNavigationTest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val mediaPermissions = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val packageName = instrumentation.targetContext.packageName
            // Live requests both permissions, including when a session already exists at launch.
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.RECORD_AUDIO)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(mediaPermissions).around(composeRule)

    @Test
    fun activityStartsWithBothMediaPermissionsGranted() {
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                activity.checkSelfPermission(Manifest.permission.CAMERA),
            )
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
            )
        }
    }

    @Test
    fun savedSessionOpensLiveUntilLogout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = GoogleSessionStore(context)
        val existingSession = store.load()

        try {
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
            composeRule.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[AuthenticationSessionViewModel::class.java].reload()
            }
            composeRule.activityRule.scenario.recreate()

            composeRule.onNodeWithContentDescription("settings").performClick()
            composeRule.onNodeWithText("user@example.com").assertIsDisplayed()
            composeRule.onNodeWithText("로그아웃").performClick()
            composeRule.onNodeWithText("Google로 계속하기").assertIsDisplayed()
        } finally {
            store.clear()
            existingSession?.let(store::save)
        }
    }
}
