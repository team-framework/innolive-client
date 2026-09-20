package com.framework.innolive.feature.live

import android.Manifest
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BroadcastPreparationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var session: WebRtcSessionViewModel
    private val settings = mutableStateOf(BroadcastSettings("검증 방송", "검증 설명", "private", null, "22"))
    private val channel = mutableStateOf<String?>("검증 채널")
    private val hasAccount = mutableStateOf(true)
    private val reconnectRequired = mutableStateOf(false)
    private val accountBusy = mutableStateOf(false)
    private val refresh = CompletableDeferred<Unit>()
    private var authenticationCalls = 0

    @Before fun showLiveScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
                        broadcastSettings = settings.value,
                        onBroadcastSettingsChanged = { settings.value = it },
                        youtubeChannelTitle = channel.value,
                        hasYouTubeAccount = hasAccount.value,
                        youtubeAccountStatus = "연동 필요",
                        isYouTubeReconnectRequired = reconnectRequired.value,
                        isYouTubeAccountActionInProgress = accountBusy.value,
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

    @Test fun cancelAndReopenSettingsDoesNotConnectUntilValidConfirmation() {
        compose.onNodeWithText("방송 준비").assertIsEnabled().performClick()
        compose.onNodeWithText("Youtube").performClick()
        compose.onNodeWithText("라이브 설정").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("라이브 설정").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, authenticationCalls)
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
        }
        compose.onNodeWithText("방송 준비").performClick()
        compose.onNodeWithText("라이브 설정").assertIsDisplayed()
        val confirm = hasText("방송 준비") and hasAnyAncestor(isDialog())
        compose.onNode(confirm).performClick()
        compose.runOnIdle {
            assertEquals(0, authenticationCalls)
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
        }
        compose.onNodeWithText("선택 필요").performClick()
        compose.onNodeWithText("아동용 아님").performClick()
        compose.onNode(confirm).performClick()
        compose.onNodeWithText("라이브 설정").assertDoesNotExist()
        compose.onNodeWithText("방송 준비 중").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, authenticationCalls)
            assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
            refresh.complete(Unit)
        }
        compose.waitUntil { !session.isPreparingBroadcast }
        compose.onNodeWithText("방송 준비").assertIsEnabled().performClick()
        compose.onNodeWithText("라이브 설정").assertIsDisplayed()
    }

    @Test fun unlinkedAccountCannotStartConnection() {
        compose.runOnIdle { hasAccount.value = false; settings.value = settings.value.copy(madeForKids = false) }
        compose.onNodeWithText("방송 준비").performClick()
        compose.onNodeWithText("Youtube").performClick()
        compose.onNode(hasText("방송 준비") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        compose.onNodeWithText("YouTube 계정을 연동한 뒤 방송을 준비해 주세요.").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, authenticationCalls)
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
        }
    }

    @Test fun linkedAccountWithoutTitleCanPrepareButReconnectAndBusyStatesBlockIt() {
        compose.runOnIdle {
            channel.value = ""
            settings.value = settings.value.copy(madeForKids = false)
        }
        compose.onNodeWithText("방송 준비").performClick()
        compose.onNodeWithText("Youtube").performClick()
        val confirm = hasText("방송 준비") and hasAnyAncestor(isDialog())
        compose.onNode(confirm).assertIsEnabled()
        compose.onNodeWithText("연동").assertDoesNotExist()
        compose.runOnIdle { reconnectRequired.value = true }
        compose.onNode(confirm).assertIsNotEnabled()
        compose.onNodeWithText("재연동").assertExists()
        compose.runOnIdle { reconnectRequired.value = false; accountBusy.value = true }
        compose.onNode(confirm).assertIsNotEnabled()
        compose.runOnIdle { accountBusy.value = false; channel.value = null }
        compose.onNode(confirm).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, authenticationCalls)
            assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
            session.close()
        }
    }
}
