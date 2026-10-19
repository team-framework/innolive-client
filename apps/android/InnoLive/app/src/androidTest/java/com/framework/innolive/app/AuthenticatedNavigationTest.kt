package com.framework.innolive.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialPreferences
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

    // 첫 실행 방송 준비 안내는 화면 터치를 막는다. 이 테스트는 안내를 이미 본 사용자로 시작한다.
    private val finishedBroadcastGuide = object : ExternalResource() {
        private lateinit var preferences: BroadcastTutorialPreferences
        private var previous = false

        override fun before() {
            preferences = BroadcastTutorialPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
            previous = preferences.hasFinishedPreparationGuide
            preferences.hasFinishedPreparationGuide = true
        }

        override fun after() {
            // apply()는 비동기라 테스트 프로세스가 먼저 끝나면 복원이 저장되지 않는다. 바로 저장한다.
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("broadcast_tutorial", Context.MODE_PRIVATE)
                .edit().putBoolean("broadcast_preparation_v1", previous).commit()
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(mediaPermissions).around(finishedBroadcastGuide).around(composeRule)

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

            // 설정은 기본 화면에서 왼쪽으로 넘겨 엽니다.
            composeRule.onNode(
                hasText(composeRule.activity.getString(R.string.action_prepare_broadcast)) and hasClickAction(),
            ).performTouchInput { swipeLeft() }
            composeRule.onNodeWithText("user@example.com").assertIsDisplayed()
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_logout)).performClick()
            composeRule.onNodeWithText(
                composeRule.activity.getString(R.string.continue_with_google),
            ).assertIsDisplayed()
        } finally {
            store.clear()
            existingSession?.let(store::save)
        }
    }
}
