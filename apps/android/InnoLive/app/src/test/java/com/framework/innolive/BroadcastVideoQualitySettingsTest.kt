package com.framework.innolive

import com.framework.innolive.feature.live.BroadcastVideoQualitySettings
import com.framework.innolive.feature.live.VideoLookPreset
import org.junit.Assert.*
import org.junit.Test

class BroadcastVideoQualitySettingsTest {
    @Test fun defaultsKeepOriginalColorAndEnableStabilization() {
        val settings = BroadcastVideoQualitySettings()
        assertTrue(settings.stabilizationEnabled)
        assertEquals(0f, settings.exposureEV)
        assertEquals(0f, settings.warmth)
        assertEquals(1f, settings.saturation)
    }

    @Test fun corruptAndOutOfRangeValuesAreNormalized() {
        assertEquals(BroadcastVideoQualitySettings(), BroadcastVideoQualitySettings(
            exposureEV = Float.NaN, warmth = Float.POSITIVE_INFINITY, saturation = Float.NEGATIVE_INFINITY,
        ).normalized())
        assertEquals(BroadcastVideoQualitySettings(exposureEV = -2f, warmth = 1f, saturation = 2f),
            BroadcastVideoQualitySettings(exposureEV = -8f, warmth = 3f, saturation = 9f).normalized())
    }

    @Test fun resetPreservesStabilizationChoice() {
        assertEquals(BroadcastVideoQualitySettings(stabilizationEnabled = false),
            VideoLookPreset.WARM.applyTo(BroadcastVideoQualitySettings(stabilizationEnabled = false)).resetAdjustments())
    }

    @Test fun presetsMatchIosValuesAndPreserveStabilization() {
        val baseline = BroadcastVideoQualitySettings(stabilizationEnabled = false)
        assertEquals(baseline.copy(exposureEV = 0f, warmth = -0.2f, saturation = 1.4f), VideoLookPreset.VIVID.applyTo(baseline))
        assertEquals(baseline.copy(exposureEV = 0.8f, warmth = 0.2f, saturation = 1.2f), VideoLookPreset.BRIGHT.applyTo(baseline))
        assertEquals(baseline.copy(exposureEV = 0.2f, warmth = 0.6f, saturation = 1.1f), VideoLookPreset.WARM.applyTo(baseline))
        VideoLookPreset.entries.forEach { preset ->
            assertTrue(preset.matches(preset.applyTo(baseline)))
            assertFalse(preset.matches(baseline))
            assertFalse(preset.matches(preset.applyTo(baseline).copy(saturation = 0f)))
        }
    }
}
