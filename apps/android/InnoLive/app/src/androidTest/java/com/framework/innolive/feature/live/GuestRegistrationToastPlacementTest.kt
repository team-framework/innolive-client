package com.framework.innolive.feature.live

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
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
import com.framework.innolive.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import kotlin.math.roundToInt

/**
 * 기본 화면의 상태 칩·카메라 도구·하단 방송 버튼과 게스트 등록 토스트 배치를 [Live.kt] 기본 화면과 같은 값으로 띄웁니다.
 * 실패 토스트는 실제 얼굴 선택 없이 가짜 등록으로 만듭니다.
 */
class GuestRegistrationToastPlacementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 세로 화면 실패 토스트는 방송 버튼 바로 위에 두 줄 이하로 놓이고, 다시 시도와 닫기가 토스트에 전달됩니다. */
    @Test fun failedToastSitsAboveBroadcastButtonInPortrait() = checkFailedToast(compose, "guest-toast-portrait")
}

class GuestRegistrationToastLandscapePlacementTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** 가로 화면으로 띄우도록 화면을 띄우기 전에 돌리고, 끝나면 세로로 되돌립니다. */
    private val landscape = object : ExternalResource() {
        private val uiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

        override fun before() {
            uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            SystemClock.sleep(1_000)
        }

        override fun after() {
            uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(landscape).around(compose)

    /** 가로 화면 실패 토스트는 좌우 조작 열 사이 하단에 두 줄 이하로 놓이고, 다시 시도와 닫기가 토스트에 전달됩니다. */
    @Test fun failedToastSitsBetweenSideControlsInLandscape() = checkFailedToast(compose, "guest-toast-landscape")
}

private class FailingBackend : GuestFaceRegistrationBackend {
    var attempts = 0

    override fun prepare(frame: FaceSelectionFrame, faceIndex: Int): ByteArray = byteArrayOf(1)

    override suspend fun register(jpeg: ByteArray, name: String, target: GuestFaceTarget): Boolean {
        attempts++
        throw GuestFaceRegistrationException(R.string.error_face_registration)
    }

    override fun close() = Unit
}

private class NoImage : FaceSelectionImage {
    override fun toUprightBitmap(): Bitmap = throw UnsupportedOperationException()
    override fun release() = Unit
}

/** Live.kt 기본 화면의 칩·카메라 도구·하단 방송 버튼·토스트 자리입니다. 작은 창과 채팅은 없는 경우입니다. */
@Composable
private fun HomePageHarness(registrar: GuestFaceRegistrar, onOpenVideoControls: () -> Unit, onBroadcast: () -> Unit) {
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var bottomControlsHeightPx by remember { mutableFloatStateOf(0f) }
    var leftControlsWidthPx by remember { mutableFloatStateOf(0f) }
    var rightControlsWidthPx by remember { mutableFloatStateOf(0f) }
    val presentation = buildLiveScreenPresentation(
        connectionState = WebRtcConnectionState.IDLE,
        broadcastState = BroadcastState.IDLE,
        selectedPlatform = "YouTube",
        broadcastStatus = UiText.Dynamic(""),
    )
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4A5A), Color(0xFF8A7A6A)))),
    ) {
        val rowTopPx = with(density) { (LiveStudioTopInset + 8.dp).toPx() }
        if (!landscape) {
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset { IntOffset(0, rowTopPx.roundToInt()) }
                    .padding(start = 16.dp, end = 120.dp),
            ) { BroadcastLiveStatusChip(directPlanRows(DirectBroadcastPlan()), "1280x720", null) }
            LiveCameraTools(
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(0, rowTopPx.roundToInt()) }
                    .padding(end = 12.dp),
                canManageFace = true,
                onOpenVideoControls = onOpenVideoControls,
                onOpenFaceManagement = {},
                canSelectFace = true,
            )
        } else {
            LiveCameraTools(
                modifier = Modifier.align(Alignment.CenterStart)
                    .onSizeChanged { leftControlsWidthPx = it.width.toFloat() }
                    .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                canManageFace = true,
                onOpenVideoControls = onOpenVideoControls,
                onOpenFaceManagement = {},
                canSelectFace = true,
            )
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .onSizeChanged { rightControlsWidthPx = it.width.toFloat() }
                    .padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                    .widthIn(min = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { BroadcastActionControls(presentation, onBroadcast, vertical = true) }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { bottomControlsHeightPx = it.height.toFloat() }
                .then(if (landscape) Modifier.widthIn(max = 420.dp) else Modifier)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!landscape) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp))
                    BroadcastActionControls(presentation, onBroadcast, Modifier.weight(1f))
                    Box(Modifier.size(48.dp))
                }
            }
            StableBroadcastFeedback {}
        }
        val statusStart = if (landscape) with(density) { leftControlsWidthPx.toDp() } + 8.dp else 24.dp
        val statusEnd = if (landscape) with(density) { rightControlsWidthPx.toDp() } + 8.dp else 24.dp
        val statusBottom = with(density) { bottomControlsHeightPx.toDp() } + 12.dp
        GuestRegistrationToast(
            registrar = registrar,
            onReselect = {},
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = statusStart, end = statusEnd, bottom = statusBottom),
        )
    }
}

private fun checkFailedToast(compose: ComposeContentTestRule, name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    fun label(id: Int): String = instrumentation.targetContext.getString(id)
    fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot
    val backend = FailingBackend()
    var videoControlsOpened = 0
    var broadcastTapped = 0
    lateinit var registrar: GuestFaceRegistrar
    compose.setContent {
        MaterialTheme {
            val scope = rememberCoroutineScope()
            registrar = remember { GuestFaceRegistrar(scope, backend) }
            HomePageHarness(registrar, { videoControlsOpened++ }, { broadcastTapped++ })
        }
    }
    compose.runOnIdle {
        val frame = FaceSelectionFrame(
            NoImage(), 1080, 1920, listOf(FaceSelectionCandidate(FaceBox(0.3f, 0.3f, 0.6f, 0.5f), true)),
        )
        registrar.register(frame, 0, "게스트 1", GuestFaceTarget.OnDevice(accountScope = null, onChanged = {}))
    }
    val close = hasContentDescription(label(R.string.action_close)) and hasClickAction()
    val retry = hasText(label(R.string.action_retry)) and hasClickAction()
    compose.waitUntil(5_000) { compose.onAllNodes(close).fetchSemanticsNodes().isNotEmpty() }
    compose.onNode(close).assertIsDisplayed()
    capture("$name-1-failed")

    // 토스트 문구는 두 줄 이하이고, 토스트는 칩·카메라 도구·방송 버튼과 겹치지 않습니다.
    val message = compose.onNode(hasText(label(R.string.error_face_registration)))
    val layouts = mutableListOf<TextLayoutResult>()
    message.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    assertTrue("문구 ${layouts.single().lineCount}줄", layouts.single().lineCount <= 2)
    val toastParts = listOf(message.bounds(), compose.onNode(retry).bounds(), compose.onNode(close).bounds())
    val others = buildList {
        add(compose.onNode(hasText(label(R.string.video_controls_title)) and hasClickAction()).bounds())
        add(compose.onNode(hasText(label(R.string.action_prepare_broadcast)) and hasClickAction()).bounds())
        compose.onAllNodes(hasContentDescription("YouTube", substring = true)).fetchSemanticsNodes()
            .forEach { add(it.boundsInRoot) }
    }
    toastParts.forEach { part -> others.forEach { other -> assertFalse("$part overlaps $other", part.overlaps(other)) } }

    // 다시 시도는 같은 이미지로 다시 등록하고, 닫기는 영상 조절 대신 토스트를 닫습니다.
    compose.onNode(retry).performClick()
    compose.waitUntil(5_000) { backend.attempts == 2 && compose.onAllNodes(close).fetchSemanticsNodes().isNotEmpty() }
    compose.onNode(close).performClick()
    compose.waitUntil(5_000) { compose.onAllNodes(close).fetchSemanticsNodes().isEmpty() }
    // 닫힌 화면이 그려진 뒤 캡처합니다.
    SystemClock.sleep(500)
    capture("$name-2-after-close-tap")
    compose.runOnIdle {
        assertEquals(0, videoControlsOpened)
        assertEquals(0, broadcastTapped)
        assertNull(registrar.status)
    }
}

private fun capture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val screenshot = instrumentation.uiAutomation.takeScreenshot()
    File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    screenshot.recycle()
}
