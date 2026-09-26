package com.framework.innolive.feature.live

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.framework.innolive.ui.theme.MyApplicationTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Uses CameraX's real capture callback and ImageProxy planes; no account or WebRTC session. */
class CameraVideoQualityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun offlineCameraProducesPresetsAfterSettingsChangeAndRebind() {
        grantCameraPermission()
        val analyzer = CameraFrameAnalyzer()
        val settings = mutableStateOf(BroadcastVideoQualitySettings())
        val visible = mutableStateOf(true)
        val captureState = AtomicReference(VideoQualityCaptureState())
        compose.setContent {
            if (visible.value) {
                DisposableEffect(analyzer) {
                    analyzer.setLookPreviewEnabled(true)
                    onDispose { analyzer.setLookPreviewEnabled(false) }
                }
                CameraPreview(
                    cameraLensFacing = CameraLensFacing.BACK,
                    cameraResolution = null,
                    frameAnalyzer = analyzer,
                    videoQualitySettings = settings.value,
                    onVideoQualityCaptureStateChanged = captureState::set,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        try {
            waitForCamera(analyzer, captureState)
            assertValidPreviews(analyzer.lookPreviews.value)
            val adjusted = VideoLookPreset.WARM.applyTo(settings.value)
            compose.runOnIdle { settings.value = adjusted }
            compose.waitUntil(15_000) { analyzer.lookPreviews.value?.settings == adjusted }
            assertValidPreviews(analyzer.lookPreviews.value)

            compose.runOnIdle { visible.value = false }
            compose.waitUntil(5_000) { analyzer.lookPreviews.value == null }
            captureState.set(VideoQualityCaptureState())
            compose.runOnIdle { visible.value = true }
            waitForCamera(analyzer, captureState)
            assertEquals(adjusted, analyzer.lookPreviews.value?.settings)
            val state = captureState.get()
            assertTrue(state.appliedExposureEV in -2f..2f)
            assertTrue(state.minExposureEV <= state.maxExposureEV)
        } finally {
            compose.runOnIdle { visible.value = false }
            analyzer.close()
            assertNull(analyzer.lookPreviews.value)
        }
    }

    @Test fun captureVideoAdjustmentSheetWithRealCameraPreviews() {
        grantCameraPermission()
        val analyzer = CameraFrameAnalyzer()
        val settings = mutableStateOf(VideoLookPreset.BRIGHT.applyTo(BroadcastVideoQualitySettings()))
        val captureState = mutableStateOf(VideoQualityCaptureState())
        compose.setContent {
            val previews by analyzer.lookPreviews.collectAsState()
            DisposableEffect(analyzer) {
                analyzer.setLookPreviewEnabled(true)
                onDispose { analyzer.setLookPreviewEnabled(false) }
            }
            MyApplicationTheme {
                Box(Modifier.fillMaxSize()) {
                    CameraPreview(
                        cameraLensFacing = CameraLensFacing.BACK,
                        cameraResolution = null,
                        frameAnalyzer = analyzer,
                        videoQualitySettings = settings.value,
                        onVideoQualityCaptureStateChanged = { captureState.value = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    BroadcastVideoControls(
                        settings = settings.value,
                        captureState = captureState.value,
                        previews = previews?.previews,
                        onSettingsChanged = { settings.value = it },
                        onDismiss = {},
                    )
                }
            }
        }
        try {
            compose.waitUntil(20_000) { analyzer.lookPreviews.value?.previews?.size == VideoLookPreset.entries.size }
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val orientation = instrumentation.targetContext.resources.configuration.orientation
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "video-adjustments-$orientation.png")
            output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            analyzer.close()
        }
    }

    private fun waitForCamera(analyzer: CameraFrameAnalyzer, state: AtomicReference<VideoQualityCaptureState>) {
        compose.waitUntil(20_000) {
            analyzer.lookPreviews.value?.previews?.size == VideoLookPreset.entries.size &&
                state.get().stabilizationStatus != VideoStabilizationStatus.PENDING
        }
    }

    private fun assertValidPreviews(value: VideoLookPreviews?) {
        assertNotNull(value)
        val previews = checkNotNull(value).previews
        assertEquals(VideoLookPreset.entries.toSet(), previews.keys)
        previews.values.forEach { bitmap ->
            assertTrue(bitmap.width > 0)
            assertTrue(bitmap.height > 0)
            assertTrue(maxOf(bitmap.width, bitmap.height) <= 480)
            assertTrue(!bitmap.isRecycled)
        }
    }

    private fun grantCameraPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )
    }
}
