package com.framework.innolive.feature.live

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class VideoLookPreviewRendererTest {
    @Test fun rawSourceProducesAllLooksWithoutChangingTheCapturedBytes() {
        val source = source()
        val originalY = source.y.copyOf()
        val originalU = source.u.copyOf()
        val originalV = source.v.copyOf()
        VideoLookPreviewRenderer().use { renderer ->
            val settings = BroadcastVideoQualitySettings(warmth = -1f, saturation = 0f)
            val previews = renderer.render(source, settings, 0f)
            val neutralCurrent = renderer.render(source, BroadcastVideoQualitySettings(), 0f)
            assertEquals(VideoLookPreset.entries.toSet(), previews.previews.keys)
            for (look in VideoLookPreset.entries) {
                val preview = previews.previews.getValue(look)
                assertEquals(5, preview.width)
                assertEquals(3, preview.height)
                assertTrue(preview.sameAs(neutralCurrent.previews.getValue(look)))
            }
            assertFalse(previews.previews.getValue(VideoLookPreset.VIVID).sameAs(previews.previews.getValue(VideoLookPreset.WARM)))
        }
        assertArrayEquals(originalY, source.y)
        assertArrayEquals(originalU, source.u)
        assertArrayEquals(originalV, source.v)
    }

    @Test fun cropAndClockwiseRotationAreAppliedToEveryLook() {
        VideoLookPreviewRenderer().use { renderer ->
            val cropped = source().copy(cropLeft = 1, cropTop = 1, cropWidth = 3, cropHeight = 2)
            val original = renderer.render(cropped, BroadcastVideoQualitySettings(), 0f)
            val rotated = renderer.render(cropped.copy(rotation = 90), BroadcastVideoQualitySettings(), 0f)
            for (look in VideoLookPreset.entries) {
                val before = original.previews.getValue(look)
                val after = rotated.previews.getValue(look)
                assertEquals(2, after.width)
                assertEquals(3, after.height)
                repeat(2) { y -> repeat(3) { x ->
                    assertEquals(before.getPixel(x, y), after.getPixel(1 - y, x))
                } }
            }
        }
    }

    @Test fun brightnessUsesActualExposureAndLongEdgeIsBounded() {
        VideoLookPreviewRenderer().use { renderer ->
            val source = PreviewYuvSnapshot(
                width = 1280, height = 720,
                y = ByteArray(1280 * 720) { 70 },
                u = ByteArray(640 * 360) { 128.toByte() },
                v = ByteArray(640 * 360) { 128.toByte() },
            )
            val settings = BroadcastVideoQualitySettings(exposureEV = 0.8f)
            val current = renderer.render(source, settings, 0.8f).previews.getValue(VideoLookPreset.BRIGHT)
            val unapplied = renderer.render(source, settings, 0f).previews.getValue(VideoLookPreset.BRIGHT)
            assertEquals(480, current.width)
            assertEquals(270, current.height)
            assertTrue(Color.red(unapplied.getPixel(0, 0)) > Color.red(current.getPixel(0, 0)))
        }
    }

    private fun source() = PreviewYuvSnapshot(
        width = 5, height = 3,
        y = ByteArray(15) { (50 + it * 5).toByte() },
        u = ByteArray(6) { 120.toByte() },
        v = ByteArray(6) { 140.toByte() },
    )
}
