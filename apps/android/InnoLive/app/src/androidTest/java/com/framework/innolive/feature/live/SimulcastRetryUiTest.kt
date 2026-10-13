package com.framework.innolive.feature.live

import android.Manifest
import android.media.AudioDeviceInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 동시 송출에서 한 플랫폼만 준비됐을 때 화면에서 남은 플랫폼 설정을 다시 열 수 있는지 확인합니다. */
class SimulcastRetryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var savedProvider: String? = null
    private val transports = mutableListOf<PartialTransport>()
    private lateinit var session: WebRtcSessionViewModel

    /** YouTube만 준비되고 치지직은 실패한 서버 응답을 흉내 냅니다. */
    private class PartialTransport(val request: LiveBroadcastTransportRequest) : LiveBroadcastTransport {
        val calls = mutableListOf<String>()

        override fun start() {
            request.callbacks.onInitialSignalingStarted()
            request.callbacks.onStateChanged(WebRtcConnectionState.CONNECTED, null)
        }

        override fun prepareTargets(
            youtube: BroadcastSettings?,
            chzzk: ChzzkBroadcastSettings?,
            allowConcurrent: Boolean,
        ): Boolean {
            calls += "prepare:${listOfNotNull(youtube?.let { "youtube" }, chzzk?.let { "chzzk" }).joinToString("+")}"
            request.callbacks.onSessionSnapshotChanged(SessionSnapshot("s", targets = listOf(
                SessionTarget("youtube", "idle", "prepared", null, null),
                SessionTarget("chzzk", "idle", "idle", null, null),
            )))
            request.callbacks.onBroadcastStateChanged(BroadcastState.PREPARED, null)
            return true
        }

        override fun close(onComplete: (() -> Unit)?) {
            onComplete?.invoke()
        }

        override fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) = Unit
        override fun setAnonymizationEnabled(
            enabled: Boolean,
            onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit,
        ) = Unit
        override fun saveBroadcastSettings(settings: BroadcastSettings) = Unit
        override fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean) = false
        override fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings) = false
        override fun goLive(onAccepted: () -> Unit) = false
        override fun pauseBroadcast() = Unit
        override fun resumeBroadcast() = Unit
        override fun stopBroadcast() = Unit
    }

    @Before fun showPartiallyPreparedSimulcast() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = instrumentation.targetContext.getSharedPreferences("innolive_broadcast_provider", 0)
        savedProvider = preferences.getString("selected", null)
        preferences.edit().putString("selected", "SIMULCAST").commit()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        compose.runOnIdle {
            session = WebRtcSessionViewModel { request -> PartialTransport(request).also(transports::add) }
        }
        compose.setContent {
            MaterialTheme {
                LiveScreen(
                    LiveScreenProps(
                        cameraLensFacing = CameraLensFacing.BACK,
                        cameraResolution = null,
                        broadcastSettings = settings,
                        onBroadcastSettingsChanged = {},
                        youtubeChannelTitle = "검증 채널",
                        hasYouTubeAccount = true,
                        youtubeAccountStatus = "연동됨",
                        isYouTubeReconnectRequired = false,
                        isYouTubeAccountActionInProgress = false,
                        isYouTubeConnectEnabled = true,
                        onConnectYouTube = {},
                        onRefreshAccessToken = { "access-token" },
                        onOpenSettings = {},
                    ),
                    session,
                )
            }
        }
        compose.runOnIdle {
            session.selectProviders(setOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK))
            assertTrue(session.prepareSimulcastBroadcast(compose.activity, settings, ChzzkBroadcastSettings("치지직 방송")) {
                "access-token"
            })
        }
        compose.waitUntil(10_000) { session.broadcastState == BroadcastState.PREPARED }
    }

    @After fun restoreProviderPreference() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("innolive_broadcast_provider", 0)
            .edit().putString("selected", savedProvider).commit()
    }

    @Test fun failedPlatformSettingsReopenFromPreparedActions() {
        compose.runOnIdle {
            assertEquals(listOf(BroadcastProvider.CHZZK), session.simulcastRetryProviders)
            assertEquals(listOf("prepare:youtube+chzzk"), transports.single().calls)
        }
        // 같은 문구의 상태 줄이 버튼 아래에도 있으므로 누를 수 있는 버튼만 고릅니다.
        compose.onNode(hasText(label(R.string.broadcast_state_prepared)) and hasClickAction()).performClick()
        compose.onNodeWithText(label(R.string.action_start_broadcast)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.simulcast_action_retry_remaining)).performClick()
        // 준비된 YouTube를 취소하지 않고 방송 설정 창을 남은 치지직 탭으로 엽니다.
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.chzzk_label_category_search)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(BroadcastState.PREPARED, session.broadcastState) }
    }

    private fun label(id: Int): String = compose.activity.getString(id)

    private companion object {
        val settings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22")
    }
}
