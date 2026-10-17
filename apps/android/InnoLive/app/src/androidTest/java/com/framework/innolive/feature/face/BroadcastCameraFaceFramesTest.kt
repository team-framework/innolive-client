package com.framework.innolive.feature.face

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraPreview
import com.framework.innolive.feature.live.WebRtcNativeRuntime
import com.framework.innolive.feature.live.supportedCameraResolutions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoSink

/** 방송 카메라를 그대로 둔 채 실제 CameraX 프레임으로 얼굴 등록 프레임을 받는지 확인합니다. */
@RunWith(AndroidJUnit4::class)
class BroadcastCameraFaceFramesTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun registrationSharesBroadcastFramesWithoutStoppingOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.CAMERA,
        )
        // 송출 경로처럼 분석기가 WebRTC 버퍼를 쓰므로 네이티브 라이브러리를 먼저 올립니다.
        WebRtcNativeRuntime.initialize(instrumentation.targetContext)
        val provider = ProcessCameraProvider.getInstance(instrumentation.targetContext).get(10, TimeUnit.SECONDS)
        composeRule.runOnUiThread { provider.unbindAll() }
        // 앱처럼 지원 해상도 중 가장 큰 값으로 방송 카메라를 엽니다.
        val resolution = provider.getCameraInfo(CameraSelector.DEFAULT_FRONT_CAMERA)
            .supportedCameraResolutions().first()
        val analyzer = CameraFrameAnalyzer()
        val broadcastFrames = AtomicInteger()
        analyzer.setProcessedPreviewSink(VideoSink { broadcastFrames.incrementAndGet() })
        val registering = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val faceFrames = AtomicInteger()
        val errors = AtomicInteger()
        composeRule.setContent {
            if (visible.value) {
                CameraPreview(
                    cameraLensFacing = CameraLensFacing.FRONT,
                    cameraResolution = resolution,
                    frameAnalyzer = analyzer,
                    showAdjustedColorPreview = false,
                )
                if (registering.value) {
                    FaceCaptureFrames(
                        broadcastCamera = analyzer,
                        cameraLensFacing = CameraLensFacing.FRONT,
                        enabled = true,
                        onFrame = { faceFrames.incrementAndGet(); it.recycle() },
                        onSourceTooSmall = { errors.incrementAndGet() },
                        onCameraError = { errors.incrementAndGet() },
                    )
                }
            }
        }
        try {
            composeRule.waitUntil(10_000) { broadcastFrames.get() > 0 }

            composeRule.runOnIdle { registering.value = true }
            val broadcastBefore = broadcastFrames.get()
            composeRule.waitUntil(10_000) { faceFrames.get() >= 3 || errors.get() > 0 }
            assertEquals("Broadcast face frame conversion failed", 0, errors.get())
            assertTrue(
                "Broadcast output stopped during face registration",
                broadcastFrames.get() - broadcastBefore >= 10,
            )

            composeRule.runOnIdle { registering.value = false }
            composeRule.waitForIdle()
            val faceFramesAfterClose = faceFrames.get()
            val broadcastAfterClose = broadcastFrames.get()
            composeRule.waitUntil(10_000) { broadcastFrames.get() - broadcastAfterClose >= 10 }
            // 변환 중이던 마지막 한 장까지만 허용하고 그 뒤로는 탭이 해제돼야 합니다.
            assertTrue(
                "Face frame tap was not released",
                faceFrames.get() - faceFramesAfterClose <= 1,
            )
        } finally {
            composeRule.runOnIdle { visible.value = false }
            composeRule.runOnUiThread { provider.unbindAll() }
            analyzer.close()
        }
    }
}
