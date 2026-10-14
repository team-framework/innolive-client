package com.framework.innolive.feature.live.consent

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.LiveScreen
import com.framework.innolive.feature.live.LiveScreenProps
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.WebRtcSessionViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 동의 기록이 없는 기기에서 YouTube 방송 준비를 눌렀을 때 동의·안내를 거쳐야 연결을 시작합니다. */
class BroadcastConsentFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val broadcastConsents = BroadcastConsentRule(accepted = emptySet())
    private lateinit var session: WebRtcSessionViewModel
    private val refresh = CompletableDeferred<Unit>()
    private var authenticationCalls = 0
    private var savedProvider: String? = null
    private val store by lazy { BroadcastConsentStore(compose.activity) }

    @Before fun showLiveScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = instrumentation.targetContext.getSharedPreferences("innolive_broadcast_provider", 0)
        savedProvider = preferences.getString("selected", null)
        preferences.edit().putString("selected", "YouTube").commit()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
        }
        compose.setContent {
            MaterialTheme {
                LiveScreen(
                    LiveScreenProps(
                        cameraLensFacing = CameraLensFacing.BACK,
                        cameraResolution = null,
                        broadcastSettings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22"),
                        onBroadcastSettingsChanged = {},
                        youtubeChannelTitle = "검증 채널",
                        hasYouTubeAccount = true,
                        youtubeAccountStatus = "검증 채널",
                        isYouTubeReconnectRequired = false,
                        isYouTubeAccountActionInProgress = false,
                        isYouTubeConnectEnabled = true,
                        onConnectYouTube = {},
                        onRefreshAccessToken = {
                            authenticationCalls++
                            refresh.await()
                            error("검증용 인증 실패")
                        },
                        onOpenSettings = {},
                    ),
                    session,
                )
            }
        }
    }

    @After fun restoreProviderPreference() {
        refresh.complete(Unit)
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("innolive_broadcast_provider", 0)
            .edit().putString("selected", savedProvider).commit()
    }

    @Test fun cancellingMediaConsentKeepsSettingsOpenWithoutConnecting() {
        confirmPreparation()
        compose.onNodeWithTag("broadcastConsent.MEDIA_TRANSMISSION").assertIsDisplayed()

        compose.onNodeWithTag("broadcastConsent.cancel").performClick()

        compose.onNodeWithTag("broadcastConsent.MEDIA_TRANSMISSION").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
        assertNotConnected()
        assertFalse(store.hasAccepted(BroadcastConsentStep.MEDIA_TRANSMISSION))

        // 설정 창에서 다시 준비를 누르면 같은 동의를 다시 묻습니다.
        compose.onNode(hasText(label(R.string.action_prepare_broadcast)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("broadcastConsent.MEDIA_TRANSMISSION").assertIsDisplayed()
    }

    @Test fun youtubePreparationAsksMediaConsentThenYouTubeNoticeBeforeConnecting() {
        confirmPreparation()
        accept("MEDIA_TRANSMISSION")
        assertTrue(store.hasAccepted(BroadcastConsentStep.MEDIA_TRANSMISSION))
        compose.onNodeWithTag("broadcastConsent.YOUTUBE_TRANSMISSION").assertIsDisplayed()
        assertNotConnected()

        accept("YOUTUBE_TRANSMISSION")

        assertTrue(store.hasAccepted(BroadcastConsentStep.YOUTUBE_TRANSMISSION))
        assertFalse(store.hasAccepted(BroadcastConsentStep.CHZZK_TRANSMISSION))
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, authenticationCalls)
            assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
        }
    }

    @Test fun acceptedConsentsAreNotAskedAgain() {
        store.record(BroadcastConsentStep.MEDIA_TRANSMISSION)
        store.record(BroadcastConsentStep.YOUTUBE_TRANSMISSION)

        confirmPreparation()

        compose.onNodeWithTag("broadcastConsent.MEDIA_TRANSMISSION").assertDoesNotExist()
        compose.onNodeWithTag("broadcastConsent.YOUTUBE_TRANSMISSION").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, authenticationCalls) }
    }

    @Test fun preparationWithoutConsentIsRejectedBeforeConnecting() {
        compose.runOnIdle {
            assertFalse(
                session.prepareBroadcast(
                    compose.activity,
                    BroadcastSettings("검증 방송", "검증 설명", "private", false, "22"),
                ) { error("연결하면 안 됩니다.") },
            )
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
        }
    }

    private fun confirmPreparation() {
        compose.onNodeWithText(label(R.string.action_prepare_broadcast)).performClick()
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.action_prepare_broadcast)) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun accept(step: String) {
        compose.onNodeWithTag("broadcastConsent.$step").assertIsDisplayed()
        compose.onNodeWithTag("broadcastConsent.privacyPolicy").performScrollTo()
        compose.onNodeWithTag("broadcastConsent.accept").assertIsEnabled().performClick()
    }

    private fun assertNotConnected() = compose.runOnIdle {
        assertEquals(0, authenticationCalls)
        assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
    }

    private fun label(id: Int): String = compose.activity.getString(id)
}
