package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySegmentationTest {
    @Test fun letterboxKeepsSensorAspectRatio() {
        val landscape = PrivacySegmentation.Letterbox(1280, 720)
        assertEquals(640, landscape.resizedWidth)
        assertEquals(360, landscape.resizedHeight)
        assertEquals(140, landscape.top)
        val portrait = PrivacySegmentation.Letterbox(720, 1280)
        assertEquals(360, portrait.resizedWidth)
        assertEquals(140, portrait.left)
    }

    @Test fun classAwareSuppressionRetainsFaceAndPlateAtSameLocation() {
        val count = 3
        val output = FloatArray(38 * count)
        repeat(count) { index ->
            output[index] = 320f
            output[count + index] = 320f
            output[2 * count + index] = 80f
            output[3 * count + index] = 80f
        }
        output[4 * count] = 0.9f
        output[4 * count + 1] = 0.8f
        output[5 * count + 2] = 0.7f
        val detections = PrivacySegmentation.detections(output, count)
        assertEquals(2, detections.size)
        assertEquals(listOf(0, 1), detections.map { it.classId })
    }

    @Test fun emptyInstanceMaskProtectsItsBoundingBox() {
        val detection = PrivacySegmentation.Detection(
            PrivacySegmentation.Box(100f, 100f, 140f, 140f),
            0.9f,
            0,
            FloatArray(32),
        )
        val mask = PrivacySegmentation.unionMask(listOf(detection), FloatArray(32 * 160 * 160))
        assertEquals(-1, mask[30 * 160 + 30].toInt())
        assertEquals(0, mask[10 * 160 + 10].toInt())
    }

    @Test fun invalidOutputsAreRejectedBeforeAFrameCanBeSent() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivacySegmentation.detections(FloatArray(37), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivacySegmentation.unionMask(emptyList(), FloatArray(4))
        }
        val output = FloatArray(38)
        output[4] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            PrivacySegmentation.detections(output, 1)
        }
        assertTrue(PrivacySegmentation.detections(FloatArray(38), 1).isEmpty())
    }

    @Test fun maskExpansionNeverExposesAProtectedPixel() {
        val mask = ByteArray(160 * 160)
        mask[80 * 160 + 80] = -1
        val expanded = PrivacyMaskRenderer.expandedMask(mask)
        assertEquals(-1, expanded[80 * 160 + 80].toInt())
        assertEquals(-1, expanded[82 * 160 + 80].toInt())
        assertEquals(0, expanded[90 * 160 + 90].toInt())
    }
}
