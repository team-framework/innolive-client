package com.framework.innolive.feature.face

import android.Manifest
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraPreview
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production CameraPreview and FaceCaptureCamera, with real CameraX frames. */
@RunWith(AndroidJUnit4::class)
class FaceCameraHandoffTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun frontHomeToRegistrationAndBackRepeatedlyReceivesFrames() = verifyHandoff(CameraLensFacing.FRONT)
    @Test fun backHomeToRegistrationAndBackRepeatedlyReceivesFrames() = verifyHandoff(CameraLensFacing.BACK)
    @Test fun registrationSurvivesLensSwitchRecompositionAndBackgroundReturn() = verifyHandoff(
        CameraLensFacing.FRONT, exerciseLifecycle = true,
    )

    private fun verifyHandoff(initialFacing: CameraLensFacing, exerciseLifecycle: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.CAMERA,
        )
        val provider = ProcessCameraProvider.getInstance(instrumentation.targetContext).get(10, TimeUnit.SECONDS)
        // Isolation only; production disposal must release its own binding before every transition.
        composeRule.runOnUiThread { provider.unbindAll() }
        val registering = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val facing = mutableStateOf(initialFacing)
        val active = mutableStateOf(true)
        val frames = AtomicInteger()
        val errors = AtomicInteger()
        composeRule.setContent {
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val observer = LifecycleEventObserver { _, _ ->
                    active.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }
            if (visible.value) {
                if (registering.value) {
                    FaceCaptureCamera(
                        cameraLensFacing = facing.value,
                        enabled = active.value,
                        onFrame = { frames.incrementAndGet(); it.recycle() },
                        onSourceTooSmall = { errors.incrementAndGet() },
                        onCameraError = { errors.incrementAndGet() },
                    )
                } else {
                    CameraPreview(cameraLensFacing = facing.value, cameraResolution = null)
                }
            }
        }
        try {
            repeat(3) { round ->
                waitForHome(provider, facing.value)
                composeRule.runOnIdle { registering.value = true }
                observeFrames(frames, errors, "${facing.value} round=$round")
                if (exerciseLifecycle && round == 0) {
                    composeRule.runOnIdle { facing.value = CameraLensFacing.BACK }
                    observeFrames(frames, errors, "lens-switch BACK")
                    // Actual Activity pause/stop destroys the capture effect, then resume recreates it.
                    composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    observeFrames(frames, errors, "background-return BACK")
                }
                composeRule.runOnIdle { registering.value = false }
            }
            waitForHome(provider, facing.value)
            assertEquals("Camera initialization or conversion failed", 0, errors.get())
        } finally {
            composeRule.runOnIdle { visible.value = false }
            composeRule.runOnUiThread { provider.unbindAll() }
        }
    }

    private fun waitForHome(provider: ProcessCameraProvider, facing: CameraLensFacing) {
        val selector = if (facing == CameraLensFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
        composeRule.waitUntil(10_000) {
            var opened = false
            composeRule.runOnUiThread {
                opened = provider.getCameraInfo(selector).cameraState.value?.type == CameraState.Type.OPEN
            }
            opened
        }
    }

    private fun observeFrames(frames: AtomicInteger, errors: AtomicInteger, label: String) {
        val first = frames.get()
        composeRule.waitUntil(10_000) { frames.get() > first || errors.get() > 0 }
        assertEquals("$label: capture initialization failed", 0, errors.get())
        val start = android.os.SystemClock.elapsedRealtime()
        val startFrames = frames.get()
        // Count delivered registration Bitmaps over a wall-clock window, not Compose text or mocks.
        composeRule.waitUntil(5_000) { android.os.SystemClock.elapsedRealtime() - start >= 3_000 }
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        val received = frames.get() - startFrames
        Log.i("FaceCameraHandoffTest", "$label observation_ms=$elapsed frames=$received errors=${errors.get()}")
        assertTrue("$label: frames stopped during $elapsed ms (received=$received)", received >= 3)
        assertEquals("$label: camera error during observation", 0, errors.get())
    }
}
