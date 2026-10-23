package com.framework.innolive.feature.live

import com.framework.innolive.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MosaicConfigTest {
    @Test fun emptyConfigUsesServerDefaults() {
        assertEquals(24, MosaicConfig().effectiveBlurRadius)
        assertEquals(8, MosaicConfig().effectiveMaskMargin)
        // 본문이 세션 설정 전체이므로 기본값은 빈 본문으로 보냅니다.
        assertEquals("{}", MosaicConfig().toJson().toString())
    }

    @Test fun sliderValuesAreClampedAndPixelSizeIsKept() {
        val config = MosaicConfig(pixelSize = 4).adjusted(blurRadius = 99, maskMargin = -3)
        assertEquals(MosaicConfig(blurRadius = 64.0, pixelSize = 4, maskMargin = 0), config)
        val body = config.toJson()
        assertEquals(64.0, body.getDouble("blur_radius"), 0.0)
        assertEquals(4, body.getInt("pixel_size"))
        assertEquals(0, body.getInt("mask_margin"))
        // 0은 기본값과 다르므로 본문에 남깁니다.
        assertEquals(MosaicConfig(pixelSize = 4), config.adjusted(blurRadius = null, maskMargin = null))
    }

    @Test fun responseConfigIsParsedAndMissingFieldsStayDefault() {
        val response = JSONObject("""{"media":{"mosaic_config":{"blur_radius":40,"pixel_size":null,"mask_margin":16}}}""")
        assertEquals(MosaicConfig(blurRadius = 40.0, maskMargin = 16), parseMosaicConfig(response))
        assertEquals(MosaicConfig(), parseMosaicConfig(JSONObject("""{"media":{"mosaic_config":{}}}""")))
        assertNull(parseMosaicConfig(JSONObject("""{"media":{}}""")))
        assertNull(parseMosaicConfig(JSONObject("{}")))
    }

    @Test fun sessionSnapshotKeepsLastConfigWhenResponseOmitsIt() {
        val first = parseSessionSnapshot("""{"session_id":"s","media":{"mosaic_config":{"blur_radius":30}}}""", "s")
        assertEquals(30, first.mosaicConfig?.effectiveBlurRadius)
        val next = parseSessionSnapshot("""{"session_id":"s","broadcast_remaining_seconds":10}""", "s", first)
        assertEquals(first.mosaicConfig, next.mosaicConfig)
        assertNull(parseSessionSnapshot("""{"session_id":"s"}""", "s").mosaicConfig)
    }

    @Test fun noteFollowsIosOrder() {
        assertEquals(R.string.mosaic_prepare_required,
            mosaicNoteRes(hasSession = false, onDeviceProcessing = false, canChange = false, anonymization = AnonymizationState.ENABLED))
        assertEquals(R.string.mosaic_anonymization_off,
            mosaicNoteRes(hasSession = true, onDeviceProcessing = false, canChange = true, anonymization = AnonymizationState.DISABLED))
        assertNull(mosaicNoteRes(hasSession = true, onDeviceProcessing = false, canChange = true, anonymization = AnonymizationState.ENABLED))
    }

    @Test fun onDeviceNoteExplainsTheFloorEvenBeforePreparation() {
        // 방송 준비 전에는 세션이 없고 비식별화 상태도 아직 모릅니다. 꺼짐 안내를 띄우지 않습니다.
        assertEquals(R.string.mosaic_on_device_floor,
            mosaicNoteRes(hasSession = false, onDeviceProcessing = true, canChange = false, anonymization = AnonymizationState.UNKNOWN))
        assertEquals(R.string.mosaic_on_device_floor,
            mosaicNoteRes(hasSession = true, onDeviceProcessing = true, canChange = false, anonymization = AnonymizationState.ENABLED))
        assertEquals(R.string.mosaic_anonymization_off,
            mosaicNoteRes(hasSession = true, onDeviceProcessing = true, canChange = false, anonymization = AnonymizationState.DISABLED))
    }
}
