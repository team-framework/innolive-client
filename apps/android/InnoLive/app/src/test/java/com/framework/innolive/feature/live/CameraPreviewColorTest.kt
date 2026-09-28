package com.framework.innolive.feature.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewColorTest {
    @Test fun neutralPreviewDoesNotChangeCameraColors() {
        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            cameraPreviewColorMatrix(BroadcastVideoQualitySettings()),
            0.0001f,
        )
    }

    @Test fun warmPresetMovesPreviewRedAndBlueInTheSameDirectionAsSenderChroma() {
        val preset = VideoLookPreset.WARM
        val matrix = cameraPreviewColorMatrix(preset.applyTo(BroadcastVideoQualitySettings()))
        val transform = VideoColorTransform(preset.warmth, preset.saturation)
        assertTrue(matrix[4] > 0f && matrix[14] < 0f)
        assertTrue(transform.v[128] > 128 && transform.u[128] < 128)
    }
}
