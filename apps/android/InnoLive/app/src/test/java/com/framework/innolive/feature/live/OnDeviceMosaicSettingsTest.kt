package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.privacy.PrivacyBroadcastBlur
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceMosaicSettingsTest {
    @Test fun defaultsMatchTheServerScaleAndTheCurrentOnDeviceStrength() {
        val settings = OnDeviceMosaicSettings()
        assertEquals(MosaicConfig.DEFAULT_BLUR_RADIUS, settings.blurStrength)
        assertEquals(PrivacyBroadcastBlur.DEFAULT_STRENGTH, settings.blurStrength)
        assertEquals(MosaicConfig.DEFAULT_MASK_MARGIN, settings.maskMargin)
        assertEquals(MosaicConfig(blurRadius = 24.0, maskMargin = 8), settings.toMosaicConfig())
    }

    @Test fun neverGoesWeakerOrNarrowerThanTheDefault() {
        assertEquals(OnDeviceMosaicSettings(24, 8), OnDeviceMosaicSettings(1, 0).normalized())
        assertEquals(OnDeviceMosaicSettings(48, 64), OnDeviceMosaicSettings(99, 99).normalized())
        assertEquals(OnDeviceMosaicSettings(36, 20), OnDeviceMosaicSettings(36, 20).normalized())
    }

    @Test fun sheetChangesAndResetMapBackToTheOnDeviceRange() {
        assertEquals(OnDeviceMosaicSettings(40, 32),
            OnDeviceMosaicSettings.from(MosaicConfig().adjusted(blurRadius = 40, maskMargin = 32)))
        // 모자이크 기본값 버튼은 null 필드로 오며, 지금 강도와 1칸으로 돌아갑니다.
        assertEquals(OnDeviceMosaicSettings(),
            OnDeviceMosaicSettings.from(MosaicConfig(blurRadius = 40.0, maskMargin = 32).adjusted(null, null)))
        assertEquals(OnDeviceMosaicSettings(48, 8),
            OnDeviceMosaicSettings.from(MosaicConfig().adjusted(blurRadius = 64, maskMargin = 0)))
    }
}
