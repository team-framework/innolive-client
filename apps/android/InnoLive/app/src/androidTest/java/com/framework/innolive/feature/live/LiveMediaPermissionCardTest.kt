package com.framework.innolive.feature.live

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class LiveMediaPermissionCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** 권한 요청 창을 띄우지 않고 요청 횟수만 셉니다. 결과는 테스트가 직접 돌려줍니다. */
    private class RecordingRegistry : ActivityResultRegistry() {
        val requestCodes = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            if (input is Array<*> && Manifest.permission.CAMERA in input) requestCodes += requestCode
        }
    }

    /** 카메라·마이크 권한이 없는 기기처럼 보이게 하고, 앱 설정 화면을 연 기록을 남깁니다. */
    private class DeniedMediaContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.CAMERA || permission == Manifest.permission.RECORD_AUDIO) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkPermission(permission, pid, uid)
            }

        override fun startActivity(intent: Intent) {
            started += intent
            super.startActivity(intent)
        }
    }

    /** 권한 안내 버튼은 넘기는 영역에 가리지 않고 눌리며, 다시 묻지 않음 상태에서는 앱 설정을 엽니다. */
    @Test fun permissionButtonRequestsAgainThenOpensAppSettingsWhenDeniedPermanently() {
        val registry = RecordingRegistry()
        lateinit var context: DeniedMediaContext
        lateinit var session: WebRtcSessionViewModel
        compose.runOnIdle {
            context = DeniedMediaContext(compose.activity)
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry: ActivityResultRegistry = registry
                },
            ) {
                MaterialTheme {
                    LiveScreen(
                        LiveScreenProps(
                            cameraLensFacing = CameraLensFacing.BACK,
                            cameraResolution = null,
                            broadcastSettings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22"),
                            onBroadcastSettingsChanged = {},
                            youtubeChannelTitle = "검증 채널",
                            hasYouTubeAccount = true,
                            youtubeAccountStatus = "",
                            isYouTubeReconnectRequired = false,
                            isYouTubeAccountActionInProgress = false,
                            isYouTubeConnectEnabled = true,
                            onConnectYouTube = {},
                            onRefreshAccessToken = { error("검증에서는 연결하지 않습니다") },
                            onOpenSettings = {},
                        ),
                        session,
                    )
                }
            }
        }
        // 화면에 들어오면 한 번 요청합니다. 요청 창이 떠 있는 동안처럼 결과는 아직 돌려주지 않습니다.
        compose.waitUntil(5_000) { registry.requestCodes.size == 1 }
        val allow = hasText(label(R.string.action_allow_permissions)) and hasClickAction()
        compose.onNode(allow).assertIsDisplayed()
        capture("permission-1-card")

        compose.onNode(allow).performClick()
        compose.waitForIdle()
        capture("permission-2-after-tap")
        assertEquals(2, registry.requestCodes.size)

        // 다시 묻지 않음으로 거부하면 시스템이 요청 창을 띄우지 않으므로 앱 설정으로 보냅니다.
        compose.runOnIdle {
            registry.dispatchResult(
                registry.requestCodes.last(),
                mapOf(Manifest.permission.CAMERA to false, Manifest.permission.RECORD_AUDIO to false),
            )
        }
        val openSettings = hasText(label(R.string.action_open_permission_settings)) and hasClickAction()
        compose.onNode(openSettings).assertIsDisplayed()
        capture("permission-3-denied-permanently")
        compose.onNode(openSettings).performClick()
        compose.runOnIdle {
            assertEquals(2, registry.requestCodes.size)
            val intent = context.started.single()
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:${context.packageName}", intent.dataString)
        }
        SystemClock.sleep(1_500)
        capture("permission-4-app-settings")
    }

    private fun label(id: Int): String = instrumentation.targetContext.getString(id)

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
