package com.framework.innolive.feature.live.privacy

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class PrivacyGpuFramePipelineDeviceTest {
    private fun source(width: Int = 96, height: Int = 64): VideoFrame.I420Buffer {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        return JavaI420Buffer.allocate(width, height).apply {
            for (y in 0 until height) for (x in 0 until width) dataY.put(y * strideY + x,
                (if (x < width / 2) if (y < height / 2) 40 else 100 else if (y < height / 2) 160 else 210).toByte())
            repeat(dataU.capacity()) { dataU.put(it, 128.toByte()) }
            repeat(dataV.capacity()) { dataV.put(it, 128.toByte()) }
        }
    }

    @Test fun textureReadbackDoesNotWaitForNextInferenceQueue() {
        val source = source(320, 240)
        val graph = PrivacyGpuFramePipeline(null, useGles3 = true)
        val layout = graph.prepare(source, 0)
        val texture = graph.finish(ByteArray(160 * 160), layout, 320, 240)
        val busy = java.util.concurrent.CountDownLatch(1)
        val releaseInference = java.util.concurrent.CountDownLatch(1)
        val converted = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val inference = Thread {
            graph.runFrame {
                busy.countDown()
                releaseInference.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val encoder = Thread {
            try {
                val i420 = checkNotNull(texture.toI420())
                try {
                    assertEquals(320, i420.width)
                    assertEquals(240, i420.height)
                    assertEquals(40.0, (i420.dataY.get(20 * i420.strideY + 20).toInt() and 255).toDouble(), 2.0)
                } finally { i420.release() }
            } catch (error: Throwable) { failure.set(error) }
            finally { converted.countDown() }
        }
        try {
            inference.start()
            assertTrue(busy.await(5, java.util.concurrent.TimeUnit.SECONDS))
            encoder.start()
            assertTrue("CPU encoding waited for unrelated AI work",
                converted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            releaseInference.countDown()
            inference.join(5000)
            if (encoder.isAlive) encoder.join(5000)
            texture.release()
            source.release()
            graph.close()
        }
    }

    @Test fun emptyMaskPreservesSensorQuadrantsThroughEveryRotation() {
        val source = source()
        try {
            PrivacyGpuFramePipeline(null).use { graph ->
                for (rotation in listOf(0, 90, 180, 270)) {
                    val layout = graph.prepare(source, rotation)
                    val texture = graph.finish(ByteArray(160 * 160), layout, source.width, source.height)
                    assertEquals(VideoFrame.TextureBuffer.Type.RGB, texture.type)
                    val output = texture.toI420()!!
                    try {
                        for ((x, y) in listOf(20 to 15, 75 to 15, 20 to 50, 75 to 50)) {
                            assertEquals("rotation=$rotation x=$x y=$y",
                                (source.dataY.get(y * source.strideY + x).toInt() and 255).toDouble(),
                                (output.dataY.get(y * output.strideY + x).toInt() and 255).toDouble(), 2.0)
                        }
                    } finally { output.release(); texture.release() }
                }
            }
        } finally { source.release() }
    }

    @Test fun uprightOutputAppliesCameraRotationOnGpuWithoutChangingQuadrants() {
        val source = source()
        try {
            PrivacyGpuFramePipeline(null).use { graph ->
                for (rotation in listOf(0, 90, 180, 270)) {
                    val layout = graph.prepare(source, rotation)
                    val texture = graph.finish(ByteArray(160 * 160), layout,
                        source.width, source.height, outputUpright = true)
                    val expectedWidth = if (rotation % 180 == 0) source.width else source.height
                    val expectedHeight = if (rotation % 180 == 0) source.height else source.width
                    assertEquals(expectedWidth, texture.width)
                    assertEquals(expectedHeight, texture.height)
                    val output = checkNotNull(texture.toI420())
                    try {
                        for (y in listOf(expectedHeight / 4, expectedHeight * 3 / 4)) {
                            for (x in listOf(expectedWidth / 4, expectedWidth * 3 / 4)) {
                                val (sensorX, sensorY) = when (rotation) {
                                    90 -> y to (source.height - 1 - x)
                                    180 -> (source.width - 1 - x) to (source.height - 1 - y)
                                    270 -> (source.width - 1 - y) to x
                                    else -> x to y
                                }
                                val expected = source.dataY.get(sensorY * source.strideY + sensorX).toInt() and 255
                                val actual = output.dataY.get(y * output.strideY + x).toInt() and 255
                                assertEquals("rotation=$rotation x=$x y=$y", expected.toDouble(), actual.toDouble(), 3.0)
                            }
                        }
                    } finally { output.release(); texture.release() }
                }
            }
        } finally { source.release() }
    }

    @Test fun gpuCropUsesUprightCoordinatesAndOwnsItsPixels() {
        val source = source()
        try {
            PrivacyGpuFramePipeline(null).use { graph ->
                graph.prepare(source, 90)
                val crop = graph.crop(Rect(4, 4, 16, 16))
                val pixel = crop.getPixel(5, 5)
                assertTrue((pixel and 255) in 95..101)
                graph.prepare(source, 180)
                assertEquals(pixel, crop.getPixel(5, 5))
                crop.recycle()
                assertFalse(graph.modelBitmap.isRecycled)
            }
        } finally { source.release() }
    }

    @Test fun paddedPlanesAndOddDimensionsPreserveVisiblePixels() {
        source().release() // Initialize WebRTC before wrapping independently padded planes.
        val width = 97; val height = 65
        val yStride = 112; val chromaStride = 64
        val y = ByteBuffer.allocateDirect(yStride * height)
        val u = ByteBuffer.allocateDirect(chromaStride * 33)
        val v = ByteBuffer.allocateDirect(chromaStride * 33)
        repeat(y.capacity()) { y.put(it, 235.toByte()) }
        for (row in 0 until height) for (column in 0 until width) y.put(row*yStride+column, 80.toByte())
        repeat(u.capacity()) { u.put(it, 128.toByte()); v.put(it, 128.toByte()) }
        val padded = JavaI420Buffer.wrap(width,height,y,yStride,u,chromaStride,v,chromaStride) {}
        try {
            PrivacyGpuFramePipeline(null).use { graph ->
                for (rotation in listOf(0,90)) {
                    val layout = graph.prepare(padded,rotation)
                    val texture = graph.finish(ByteArray(160*160),layout,width,height)
                    try {
                        val output = texture.toI420()!!
                        try {
                            for ((x,row) in listOf(2 to 2, 94 to 62, 48 to 32)) {
                                assertEquals(80.0,(output.dataY.get(row*output.strideY+x).toInt() and 255).toDouble(),2.0)
                            }
                        } finally { output.release() }
                    } finally { texture.release() }
                }
            }
        } finally { padded.release() }
    }

    @Test fun gaussianSpreadsEdgeInsideProtectedAreaWithoutBlockGrid() {
        val source = source(320,320)
        try {
            for (y in 0 until 320) for (x in 0 until 320)
                source.dataY.put(y*source.strideY+x,(if(x<160)16 else 235).toByte())
            PrivacyGpuFramePipeline(null).use { graph ->
                val layout = graph.prepare(source,0)
                val texture = graph.finish(ByteArray(160*160) { -1 },layout,320,320)
                try {
                    val pixels = texture.toI420()!!
                    try {
                        val left = pixels.dataY.get(160*pixels.strideY+145).toInt() and 255
                        val right = pixels.dataY.get(160*pixels.strideY+175).toInt() and 255
                        // Sigma 48 at full resolution must turn the hard edge into a smooth gradient.
                        assertTrue("Gaussian missing: left=$left right=$right",left in 25..190 && right in 50..225 && left<right)
                    } finally { pixels.release() }
                } finally { texture.release() }
            }
        } finally { source.release() }
    }

    @Test fun protectedCoreIsBlurredAndUnprotectedCornerIsPreserved() {
        val source = source(320, 320)
        try {
            for (y in 0 until 320) for (x in 0 until 320)
                source.dataY.put(y * source.strideY + x, (if (x % 2 == 0) 16 else 235).toByte())
            PrivacyGpuFramePipeline(null).use { graph ->
                val layout = graph.prepare(source, 0)
                val mask = ByteArray(160 * 160)
                for (y in 60..100) for (x in 60..100) mask[y * 160 + x] = -1
                val baseline = graph.finish(ByteArray(160 * 160), layout, 320, 320)
                val reference = baseline.toI420()!!
                val unprotectedCorner = reference.dataY.get(20 * reference.strideY + 20).toInt() and 255
                reference.release(); baseline.release()
                val texture = graph.finish(mask, layout, 320, 320)
                val pixels = texture.toI420()!!
                try {
                    val center = pixels.dataY.get(160 * pixels.strideY + 160).toInt() and 255
                    assertTrue("Core was exposed: Y=$center", center in 40..220)
                    assertEquals(unprotectedCorner, pixels.dataY.get(20 * pixels.strideY + 20).toInt() and 255)
                    val a = pixels.dataY.get(160 * pixels.strideY + 149).toInt() and 255
                    val b = pixels.dataY.get(160 * pixels.strideY + 150).toInt() and 255
                    assertTrue(kotlin.math.abs(a - b) < 15)
                } finally { pixels.release(); texture.release() }
            }
        } finally { source.release() }
    }

    @Test fun strongerOnDeviceStrengthSpreadsTheEdgeFurther() {
        val source = source(320,320)
        try {
            for (y in 0 until 320) for (x in 0 until 320)
                source.dataY.put(y*source.strideY+x,(if(x<160)16 else 235).toByte())
            PrivacyGpuFramePipeline(null).use { graph ->
                val layout = graph.prepare(source,0)
                fun leftOfEdge(strength: Int): Int {
                    val texture = graph.finish(ByteArray(160*160) { -1 },layout,320,320,
                        blurSigma=PrivacyBroadcastBlur.sigma(320,320,strength))
                    try {
                        val pixels = texture.toI420()!!
                        try { return pixels.dataY.get(160*pixels.strideY+100).toInt() and 255 }
                        finally { pixels.release() }
                    } finally { texture.release() }
                }
                val default = leftOfEdge(PrivacyBroadcastBlur.DEFAULT_STRENGTH)
                val strongest = leftOfEdge(PrivacyBroadcastBlur.MAX_STRENGTH)
                // 60px 떨어진 곳까지 밝은 쪽이 번지는 정도는 sigma가 두 배일 때 더 커야 합니다.
                assertTrue("Stronger blur did not spread: default=$default strongest=$strongest", strongest > default + 5)
            }
        } finally { source.release() }
    }

    @Test fun widerMarginProtectsPixelsOutsideTheDefaultEdge() {
        val source = source(320, 320)
        try {
            for (y in 0 until 320) for (x in 0 until 320)
                source.dataY.put(y * source.strideY + x, (if (x % 2 == 0) 16 else 235).toByte())
            PrivacyGpuFramePipeline(null).use { graph ->
                val layout = graph.prepare(source, 0)
                val mask = ByteArray(160 * 160)
                for (y in 60..100) for (x in 60..100) mask[y * 160 + x] = -1
                fun pixel(mask: ByteArray, edgeCells: Int): Int {
                    val texture = graph.finish(mask, layout, 320, 320, edgeCells = edgeCells)
                    try {
                        val pixels = texture.toI420()!!
                        try { return pixels.dataY.get(160 * pixels.strideY + 220).toInt() and 255 }
                        finally { pixels.release() }
                    } finally { texture.release() }
                }
                val unprotected = pixel(ByteArray(160 * 160), PrivacyMaskRenderer.PROTECTED_EDGE_CELLS)
                // 1칸은 2px입니다. 기본 경계의 흐린 테두리는 109칸(218px) 안에서 끝나 220px은 그대로이고,
                // 8칸이면 108칸까지 불투명하게 가려 220px에도 블러가 섞입니다.
                assertEquals(unprotected.toDouble(), pixel(mask, PrivacyMaskRenderer.PROTECTED_EDGE_CELLS).toDouble(), 3.0)
                val widened = pixel(mask, 8)
                assertTrue("Wider margin left the pixel exposed: before=$unprotected after=$widened",
                    kotlin.math.abs(widened - unprotected) > 20 && widened in 40..220)
            }
        } finally { source.release() }
    }

    @Test fun edgeFadesTheBlurBeyondTheOpaqueCore() {
        val source = source(320, 320)
        try {
            for (y in 0 until 320) for (x in 0 until 320)
                source.dataY.put(y * source.strideY + x, (if (x % 2 == 0) 16 else 235).toByte())
            PrivacyGpuFramePipeline(null).use { graph ->
                val layout = graph.prepare(source, 0)
                val mask = ByteArray(160 * 160)
                for (y in 60..100) for (x in 60..100) mask[y * 160 + x] = -1
                fun pixel(mask: ByteArray, x: Int): Int {
                    val texture = graph.finish(mask, layout, 320, 320)
                    try {
                        val pixels = texture.toI420()!!
                        try { return pixels.dataY.get(160 * pixels.strideY + x).toInt() and 255 }
                        finally { pixels.release() }
                    } finally { texture.release() }
                }
                // 1칸은 2px입니다. iOS처럼 2칸 core(102칸, 204px)는 불투명하게 가리고, 104칸까지 넓힌 마스크를
                // 흐려 105칸(210px)에도 블러가 일부 섞이며, 흐린 테두리 밖(224px)은 원본 그대로입니다.
                val core = pixel(mask, 204)
                assertTrue("Core was not blurred: $core", core in 90..160)
                val unprotected = pixel(ByteArray(160 * 160), 210)
                val faded = pixel(mask, 210)
                assertTrue("Edge did not fade: before=$unprotected after=$faded", faded - unprotected > 15)
                assertEquals(pixel(ByteArray(160 * 160), 224).toDouble(), pixel(mask, 224).toDouble(), 3.0)
            }
        } finally { source.release() }
    }

    @Test fun heldTextureSurvivesNewFramesGeometryChangesAndProcessorClose() {
        val source = source()
        val different = source(48, 32)
        val graph = PrivacyGpuFramePipeline(null)
        var held: VideoFrame.TextureBuffer? = null
        try {
            val layout = graph.prepare(source, 0)
            held = graph.finish(ByteArray(160 * 160), layout, source.width, source.height)
            val next = graph.prepare(different, 90)
            graph.finish(ByteArray(160 * 160), next, different.width, different.height).release()
            graph.close()
            val pixels = held.toI420()!!
            try { assertEquals(40.0, (pixels.dataY.get(15 * pixels.strideY + 20).toInt() and 255).toDouble(), 2.0) }
            finally { pixels.release() }
        } finally { held?.release(); source.release(); different.release() }
    }

    @Test fun slowConsumersDropAtBoundedCapacityWithoutOverwritingHeldOutput() {
        val source = source()
        try {
            PrivacyGpuFramePipeline(null).use { graph ->
                val layout = graph.prepare(source, 0)
                val held = mutableListOf<VideoFrame.TextureBuffer>()
                try {
                    repeat(6) { held += graph.finish(ByteArray(160 * 160), layout, source.width, source.height) }
                    try { graph.finish(ByteArray(160 * 160), layout, source.width, source.height); fail("Unbounded texture allocation") }
                    catch (_: PrivacyGpuBackpressureException) { }
                    try { graph.prepare(source, 0); fail("Inference input prepared with all outputs retained") }
                    catch (_: PrivacyGpuBackpressureException) { }
                    held.removeAt(0).release()
                    graph.prepare(source, 0)
                    graph.finish(ByteArray(160 * 160), layout, source.width, source.height).release()
                } finally { held.forEach { it.release() } }
            }
        } finally { source.release() }
    }
}
