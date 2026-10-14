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
import com.framework.innolive.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.framework.innolive.feature.live.consent.BroadcastConsentRule

class BroadcastPreparationUiTest {
    @get:Rule val broadcastConsents = BroadcastConsentRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var session: WebRtcSessionViewModel
    private val settings = mutableStateOf(BroadcastSettings("검증 방송", "검증 설명", "private", null, "22"))
    private val channel = mutableStateOf<String?>("검증 채널")
    private val hasAccount = mutableStateOf(true)
    private val reconnectRequired = mutableStateOf(false)
    private val accountBusy = mutableStateOf(false)
    private val refresh = CompletableDeferred<Unit>()
    private var authenticationCalls = 0
    private var savedProvider: String? = null
    private var savedOnDevice: Boolean? = null

    @Before fun showLiveScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = instrumentation.targetContext.getSharedPreferences("innolive_broadcast_provider", 0)
        savedProvider = preferences.getString("selected", null)
        preferences.edit().remove("selected").commit()
        // 기기에 저장된 온디바이스 선택이 있으면 직접 송출 메뉴가 열리므로 서버 송출로 고정합니다.
        val aiProcessing = instrumentation.targetContext.getSharedPreferences("live_ai_processing", 0)
        savedOnDevice = aiProcessing.getBoolean("on_device", false).takeIf { aiProcessing.contains("on_device") }
        aiProcessing.edit().putBoolean("on_device", false).commit()
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

    @After fun restoreProviderPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("innolive_broadcast_provider", 0)
            .edit().putString("selected", savedProvider).commit()
        context.getSharedPreferences("live_ai_processing", 0).edit().apply {
            savedOnDevice?.let { putBoolean("on_device", it) } ?: remove("on_device")
        }.commit()
    }

    @Test fun settingsMenuOpensEachEditorWithoutConnecting() {
        compose.onNodeWithText(label(R.string.session_usage_warning_test_show)).performClick()
        compose.onNodeWithText(label(R.string.session_notice_monthly_usage_80)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_prepare_broadcast)).performClick()
        // 방송 준비는 YouTube·치지직·송출할 플랫폼 메뉴를 엽니다(서버 송출은 화질을 고르지 않습니다).
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.broadcast_menu_chzzk)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.direct_mode_targets_title)).assertIsDisplayed()
        // 메뉴의 첫 줄이 YouTube입니다. 송출할 플랫폼 줄 요약에도 YouTube가 있어 첫 항목을 고릅니다.
        compose.onAllNodes(hasText("YouTube") and hasClickAction() and hasAnyAncestor(isDialog()))[0].performClick()
        compose.onNodeWithText(label(R.string.live_settings_title)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(label(R.string.live_settings_title)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.session_notice_monthly_usage_80)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(BroadcastProvider.YOUTUBE, session.selectedProvider)
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
            assertEquals(0, authenticationCalls)
        }
    }

    @Test fun cancelAndReopenSettingsDoesNotConnectUntilValidConfirmation() {
        compose.onNodeWithText(label(R.string.action_prepare_broadcast)).assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, authenticationCalls)
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
        }
        compose.onNodeWithText(label(R.string.action_prepare_broadcast)).performClick()
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
        val confirm = hasText(label(R.string.action_prepare_broadcast)) and hasAnyAncestor(isDialog())
        compose.onNode(confirm).performClick()
        // 아동용 여부가 비어 있어 연결하지 않고 YouTube 설정 창을 엽니다.
        compose.onNodeWithText(label(R.string.live_settings_title)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, authenticationCalls)
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
        }
        compose.onNodeWithText(label(R.string.audience_required)).performClick()
        compose.onNodeWithText(label(R.string.audience_not_made_for_kids)).performClick()
        compose.onNodeWithText(label(R.string.action_save_and_close)).performClick()
        compose.onNodeWithText(label(R.string.live_settings_title)).assertDoesNotExist()
        compose.onNode(confirm).performClick()
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.broadcast_state_preparing)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, authenticationCalls)
            assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
            refresh.complete(Unit)
        }
        compose.waitUntil { !session.isPreparingBroadcast }
        compose.onNodeWithText(label(R.string.action_prepare_broadcast)).assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.broadcast_settings_title)).assertIsDisplayed()
    }

    @Test fun unlinkedAccountCannotStartConnection() {
        compose.runOnIdle { hasAccount.value = false; settings.value = settings.value.copy(madeForKids = false) }
        compose.onNodeWithText(label(R.string.action_prepare_broadcast)).performClick()
        compose.onNode(hasText(label(R.string.action_prepare_broadcast)) and hasAnyAncestor(isDialog())).assertIsNotEnabled()
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
        compose.onNodeWithText(label(R.string.action_prepare_broadcast)).performClick()
        val confirm = hasText(label(R.string.action_prepare_broadcast)) and hasAnyAncestor(isDialog())
        compose.onNode(confirm).assertIsEnabled()
        compose.runOnIdle { reconnectRequired.value = true }
        compose.onNode(confirm).assertIsNotEnabled()
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

    private fun label(id: Int): String = compose.activity.getString(id)
}
