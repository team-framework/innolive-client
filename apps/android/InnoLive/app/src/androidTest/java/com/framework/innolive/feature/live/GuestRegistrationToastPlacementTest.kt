package com.framework.innolive.feature.live

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.face.FaceBox
import com.framework.innolive.feature.face.FaceSelectionCandidate
import com.framework.innolive.feature.face.FaceSelectionFrame
import com.framework.innolive.feature.face.FaceSelectionImage
import com.framework.innolive.feature.face.GuestFaceRegistrar
import com.framework.innolive.feature.face.GuestFaceRegistrationBackend
import com.framework.innolive.feature.face.GuestFaceRegistrationException
import com.framework.innolive.feature.face.GuestFaceTarget
import com.framework.innolive.feature.face.GuestRegistrationToast
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.directPlanRows
import com.framework.innolive.feature.live.status.BroadcastLiveStatusChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * 세로 기본 화면 위쪽의 상태 칩·게스트 등록 토스트·카메라 도구 배치를 [Live.kt] 기본 화면과 같은 값으로 띄웁니다.
 * 실패 토스트는 실제 얼굴 선택 없이 가짜 등록으로 만듭니다.
 */
class GuestRegistrationToastPlacementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class FailingBackend : GuestFaceRegistrationBackend {
        override fun prepare(frame: FaceSelectionFrame, faceIndex: Int): ByteArray = byteArrayOf(1)
        override suspend fun register(jpeg: ByteArray, name: String, target: GuestFaceTarget): Boolean =
            throw GuestFaceRegistrationException(R.string.error_face_registration)
        override fun close() = Unit
    }

    private class NoImage : FaceSelectionImage {
        override fun toUprightBitmap(): Bitmap = throw UnsupportedOperationException()
        override fun release() = Unit
    }

    /** 실패 토스트가 칩·카메라 도구와 겹치지 않고, 닫기를 누르면 영상 조절 대신 토스트가 닫힙니다. */
    @Test fun failedToastStaysClearOfStatusChipAndCameraTools() {
        var videoControlsOpened = 0
        lateinit var registrar: GuestFaceRegistrar
        compose.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                registrar = remember { GuestFaceRegistrar(scope, FailingBackend()) }
                val density = LocalDensity.current
                var chipHeightPx by remember { mutableIntStateOf(0) }
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF3A4A5A), Color(0xFF8A7A6A)))),
                ) {
                    val rowTopPx = with(density) { (LiveStudioTopInset + 8.dp).toPx() }
                    Box(
                        Modifier.align(Alignment.TopStart)
                            .offset { IntOffset(0, rowTopPx.roundToInt()) }
                            .onSizeChanged { chipHeightPx = it.height }
                            .padding(start = 16.dp, end = 120.dp),
                    ) {
                        BroadcastLiveStatusChip(directPlanRows(DirectBroadcastPlan()), "1280x720", null)
                    }
                    // Live.kt 기본 화면의 토스트 자리입니다.
                    val toastTopPx = rowTopPx + chipHeightPx + with(density) { 8.dp.toPx() }
                    GuestRegistrationToast(
                        registrar = registrar,
                        onReselect = {},
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(0, toastTopPx.roundToInt()) }
                            .padding(start = 16.dp, end = 120.dp),
                    )
                    LiveCameraTools(
                        modifier = Modifier.align(Alignment.TopEnd)
                            .offset { IntOffset(0, rowTopPx.roundToInt()) }
                            .padding(end = 12.dp),
                        canManageFace = true,
                        onOpenVideoControls = { videoControlsOpened++ },
                        onOpenFaceManagement = {},
                        canSelectFace = true,
                    )
                }
            }
        }
        compose.runOnIdle {
            val frame = FaceSelectionFrame(
                NoImage(), 1080, 1920, listOf(FaceSelectionCandidate(FaceBox(0.3f, 0.3f, 0.6f, 0.5f), true)),
            )
            registrar.register(frame, 0, "게스트 1", GuestFaceTarget.OnDevice(accountScope = null, onChanged = {}))
        }
        val close = hasContentDescription(label(R.string.action_close)) and hasClickAction()
        compose.waitUntil(5_000) { compose.onAllNodes(close).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(close).assertIsDisplayed()
        capture("guest-toast-1-failed")

        val retry = compose.onNode(hasText(label(R.string.action_retry)) and hasClickAction()).getBoundsInRoot()
        val closeBounds = compose.onNode(close).getBoundsInRoot()
        val tools = compose.onNode(hasText(label(R.string.video_controls_title)) and hasClickAction()).getBoundsInRoot()
        val chip = compose.onNode(hasContentDescription("YouTube", substring = true)).getBoundsInRoot()
        compose.onNode(close).performClick()
        compose.waitForIdle()
        capture("guest-toast-2-after-close-tap")
        compose.runOnIdle {
            assertEquals(0, videoControlsOpened)
            assertNull(registrar.status)
        }
        assertFalse(retry.intersects(tools))
        assertFalse(closeBounds.intersects(tools))
        assertFalse(retry.intersects(chip))
    }

    private fun DpRect.intersects(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

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
