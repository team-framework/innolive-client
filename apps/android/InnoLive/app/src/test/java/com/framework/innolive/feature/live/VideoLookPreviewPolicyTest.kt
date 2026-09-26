package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test

class VideoLookPreviewPolicyTest {
    @Test fun exposureIsRelativeToWhatTheCameraActuallyApplied() {
        assertArrayEquals(IntArray(256) { it }, previewExposureLookup(0.8f, 0.8f))
        assertEquals(100, previewExposureLookup(1f, 0f)[50])
        assertEquals(50, previewExposureLookup(0f, 1f)[100])
        assertEquals(255, previewExposureLookup(2f, -2f)[100])
        assertEquals(100, previewExposureLookup(Float.NaN, 0f)[100])
    }

    @Test fun previewsRequireAnOpenPanelAndNeverQueueMoreThanOneJob() {
        val gate = VideoLookPreviewGate()
        assertNull(gate.tryStart(0))
        gate.setEnabled(true) {}
        val ticket = requireNotNull(gate.tryStart(0))
        assertNull(gate.tryStart(1_000_000_000L))
        gate.finish(ticket) {}
        assertNotNull(gate.tryStart(1_000_000_000L))
    }

    @Test fun previewAcceptanceIsCappedAtEightFramesPerSecond() {
        val gate = VideoLookPreviewGate()
        gate.setEnabled(true) {}
        val ticket = requireNotNull(gate.tryStart(0))
        gate.finish(ticket) {}
        assertNull(gate.tryStart(124_999_999L))
        assertNotNull(gate.tryStart(125_000_000L))
    }

    @Test fun closingAndReopeningThePanelRejectsTheOldPreview() {
        val gate = VideoLookPreviewGate()
        gate.setEnabled(true) {}
        val ticket = requireNotNull(gate.tryStart(0))
        var cleared = false
        gate.setEnabled(false) { cleared = true }
        gate.setEnabled(true) {}
        var published = false
        gate.finish(ticket) { published = true }
        assertTrue(cleared)
        assertFalse(published)
        assertNotNull(gate.tryStart(1))
    }

    @Test fun closedRendererCannotPublishOrRestart() {
        val gate = VideoLookPreviewGate()
        gate.setEnabled(true) {}
        val ticket = requireNotNull(gate.tryStart(0))
        gate.close {}
        var published = false
        gate.finish(ticket) { published = true }
        gate.setEnabled(true) {}
        assertFalse(published)
        assertFalse(gate.isEnabled)
        assertNull(gate.tryStart(1_000_000_000L))
    }

    @Test fun thumbnailsPreserveAspectRatioWithoutUpscalingAndStayBelow960Pixels() {
        assertEquals(480 to 270, videoLookPreviewDimensions(1920, 1080))
        assertEquals(270 to 480, videoLookPreviewDimensions(1080, 1920))
        assertEquals(3 to 5, videoLookPreviewDimensions(3, 5))
        assertEquals(480 to 1, videoLookPreviewDimensions(8000, 1))
        assertThrows(IllegalArgumentException::class.java) { videoLookPreviewDimensions(0, 100) }
    }
}
