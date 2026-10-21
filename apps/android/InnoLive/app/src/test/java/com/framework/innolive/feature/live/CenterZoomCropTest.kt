package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.direct.publish.FitViewport
import com.framework.innolive.feature.live.direct.publish.fitViewport
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.VideoFrame

class CenterZoomCropTest {
    @Test fun broadcastSizesKeepExactRatioAndZoomAtLeastTenPercent() {
        assertEquals(CenterCrop(96, 54, 1728, 972), centerZoomCrop(1920, 1080, ON_DEVICE_EDGE_ZOOM))
        assertEquals(CenterCrop(64, 36, 1152, 648), centerZoomCrop(1280, 720, ON_DEVICE_EDGE_ZOOM))
        assertEquals(CenterCrop(36, 64, 648, 1152), centerZoomCrop(720, 1280, ON_DEVICE_EDGE_ZOOM))
    }

    @Test fun croppedFrameFillsBothEncoderSizesWithoutBars() {
        for ((width, height) in listOf(1920 to 1080, 1280 to 720, 1440 to 1080)) {
            val crop = centerZoomCrop(width, height, ON_DEVICE_EDGE_ZOOM)
            val targetHeight = 1280 * height / width
            assertEquals(FitViewport(0, 0, 1280, targetHeight), fitViewport(crop.width, crop.height, 1280, targetHeight))
            assertEquals(FitViewport(0, 0, 1920, 1920 * height / width),
                fitViewport(crop.width, crop.height, 1920, 1920 * height / width))
        }
    }

    @Test fun cropIsEvenCenteredAndInsideTheSource() {
        for ((width, height) in listOf(1920 to 1080, 1440 to 1080, 1920 to 1088, 1918 to 1078, 640 to 480, 1080 to 1080)) {
            val crop = centerZoomCrop(width, height, ON_DEVICE_EDGE_ZOOM)
            assertTrue(listOf(crop.x, crop.y, crop.width, crop.height).all { it % 2 == 0 })
            assertTrue(crop.x + crop.width <= width && crop.y + crop.height <= height)
            assertTrue(width.toDouble() / crop.width >= ON_DEVICE_EDGE_ZOOM)
            assertTrue(height.toDouble() / crop.height >= ON_DEVICE_EDGE_ZOOM)
            assertTrue(width.toDouble() / crop.width <= ON_DEVICE_EDGE_ZOOM * 1.02)
            assertTrue(kotlin.math.abs((width - crop.width) / 2 - crop.x) <= 1)
            assertTrue(kotlin.math.abs((height - crop.height) / 2 - crop.y) <= 1)
        }
    }

    @Test fun unitZoomKeepsTheWholeFrame() {
        assertEquals(CenterCrop(0, 0, 1920, 1080), centerZoomCrop(1920, 1080, 1f))
        val buffer = RecordingBuffer(1920, 1080)
        val frame = VideoFrame(buffer, 90, 42L)
        val output = frame.centerZoomed(1f)
        assertSame(frame, output)
        assertEquals(2, buffer.references)
        assertNull(buffer.lastCrop)
        output.release()
        frame.release()
        assertEquals(0, buffer.references)
    }

    @Test fun zoomedFrameCropsWithoutScalingAndKeepsMetadata() {
        val buffer = RecordingBuffer(1920, 1080)
        val frame = VideoFrame(buffer, 270, 123L)
        val output = frame.centerZoomed(ON_DEVICE_EDGE_ZOOM)
        assertEquals(listOf(96, 54, 1728, 972, 1728, 972), buffer.lastCrop)
        assertEquals(1728, output.buffer.width)
        assertEquals(972, output.buffer.height)
        assertEquals(270, output.rotation)
        assertEquals(123L, output.timestampNs)
        output.release()
        frame.release()
    }

    private class RecordingBuffer(private val width: Int, private val height: Int) : VideoFrame.Buffer {
        var references = 1
        var lastCrop: List<Int>? = null
        override fun getWidth() = width
        override fun getHeight() = height
        override fun retain() { references++ }
        override fun release() { references-- }
        override fun toI420(): VideoFrame.I420Buffer = error("Cropping must not convert")
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrame.Buffer {
            lastCrop = listOf(x, y, width, height, scaledWidth, scaledHeight)
            return RecordingBuffer(scaledWidth, scaledHeight)
        }
    }
}
