package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/** Real models and production frame conversion; synthetic pixels never grant an identity exception. */
@RunWith(AndroidJUnit4::class)
class PrivacyPerformanceDeviceTest {
    @Test fun measureConcurrentFaceGpuContention() {
        requirePhysicalGpu()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val concurrent = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val ready = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val faceTask = executor.submit {
            val faceImage = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(102, 128, 153))
            }
            try {
                PrivacyFaceModel(context).use { face ->
                    assertTrue("This comparison requires face GPU acceleration", face.usesGpu)
                    ready.countDown()
                    while (!stopped.get()) {
                        if (concurrent.get()) {
                            val started = System.nanoTime()
                            face.predict(faceImage, floatArrayOf(.34f, .46f, .66f, .46f,
                                .50f, .64f, .37f, .82f, .63f, .82f))
                            Thread.sleep(maxOf(1L, 250L - (System.nanoTime() - started) / 1_000_000L))
                        } else Thread.sleep(10)
                    }
                }
            } finally { ready.countDown(); faceImage.recycle() }
        }
        val image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 120, 160))
        }
        try {
            assertTrue(ready.await(30, java.util.concurrent.TimeUnit.SECONDS))
            if (faceTask.isDone) faceTask.get() // Propagate preparation errors.
            PrivacyOnnxModel(context).use { detector ->
                detector.process(image).recycle()
                assertTrue("This comparison requires detector GPU acceleration", detector.usesGpu)
                for ((phase, enabled) in listOf(false, true, false).withIndex()) {
                    concurrent.set(enabled)
                    Thread.sleep(300)
                    val samples = ArrayList<Double>()
                    val until = System.nanoTime() + 2_000_000_000L
                    while (System.nanoTime() < until) {
                        detector.process(image).recycle()
                        samples += checkNotNull(detector.lastTimings).inferenceMs
                        Thread.sleep(10)
                    }
                    samples.sort()
                    Log.i(TAG, "gpu_contention phase=$phase face=$enabled samples=${samples.size} " +
                        "detector_median_ms=${samples[samples.size / 2]} " +
                        "detector_p95_ms=${samples[(samples.size * .95).toInt().coerceAtMost(samples.lastIndex)]}")
                }
            }
        } finally {
            stopped.set(true)
            image.recycle()
            try { faceTask.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
        }
    }

    @Test fun measureRetainedCpuSessionAfterGpuSelection() {
        requirePhysicalGpu()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 120, 160))
        }
        try {
            for (allowSpinning in listOf(true, false, false, true)) {
                val model = PrivacyOnnxModel(context, sessionOptions = {
                    ai.onnxruntime.OrtSession.SessionOptions().apply {
                        if (!allowSpinning) {
                            addConfigEntry("session.intra_op.allow_spinning", "0")
                            addConfigEntry("session.inter_op.allow_spinning", "0")
                        }
                    }
                })
                try {
                    model.process(image).recycle()
                    assertTrue("This comparison requires detector GPU acceleration", model.usesGpu)
                    // Separate the CPU validation warmup from steady GPU operation.
                    Thread.sleep(1000)
                    val cpuStarted = Process.getElapsedCpuTime()
                    var inferenceMs = 0.0
                    repeat(12) {
                        model.process(image).recycle()
                        inferenceMs += checkNotNull(model.lastTimings).inferenceMs
                        Thread.sleep(20)
                    }
                    val inferenceCpuMs = Process.getElapsedCpuTime() - cpuStarted
                    val idleStarted = Process.getElapsedCpuTime()
                    Thread.sleep(2000)
                    Log.i(TAG, "retained_cpu spinning=$allowSpinning inference_ms=${inferenceMs / 12} " +
                        "process_cpu_ms=$inferenceCpuMs idle_cpu_ms=${Process.getElapsedCpuTime() - idleStarted}")
                } finally { model.close() }
            }
        } finally { image.recycle() }
    }

    @Test fun measureProcessingStagesAndRecognizer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions())
        PrivacyFrameProcessor(context).use { processor ->
            for ((width, height) in listOf(640 to 360, 1280 to 720, 1920 to 1080)) {
                val buffer = JavaI420Buffer.allocate(width, height)
                for (y in 0 until height) for (x in 0 until width) {
                    buffer.dataY.put(y * buffer.strideY + x, (32 + x % 180).toByte())
                }
                repeat(buffer.dataU.capacity()) { buffer.dataU.put(it, 128.toByte()) }
                repeat(buffer.dataV.capacity()) { buffer.dataV.put(it, 128.toByte()) }
                val frame = VideoFrame(buffer, 90, 123L)
                try {
                    repeat(4) { sample ->
                        val output = processor.process(frame)
                        try { assertEquals(width, output.buffer.width); assertEquals(height, output.buffer.height) }
                        finally { output.release() }
                        Log.i(TAG, "frame=${width}x$height sample=$sample ${processor.lastTimings}")
                    }
                } finally { frame.release() }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(IntArray(width * height) { if (it % width < width / 2) Color.BLACK else Color.WHITE },
                        0, width, 0, 0, width, height)
                }
                try { PrivacyBitmapBlur().use { blur ->
                    repeat(4) { sample ->
                        val started = System.nanoTime()
                        val output = PrivacyMaskRenderer.render(bitmap, ByteArray(160 * 160) { -1 },
                            PrivacySegmentation.Letterbox(width, height), blur::apply)
                        output.recycle()
                        Log.i(TAG, "protected_render=${width}x$height sample=$sample ms=${(System.nanoTime() - started) / 1e6}")
                    }
                    repeat(4) { sample ->
                        val started = System.nanoTime()
                        val output = blur.apply(bitmap)
                        output.recycle()
                        Log.i(TAG, "blur_only=${width}x$height sample=$sample ms=${(System.nanoTime() - started) / 1e6}")
                    }
                    repeat(4) { sample ->
                        val started = System.nanoTime()
                        val output = PrivacyMaskRenderer.render(bitmap, ByteArray(160 * 160) { -1 },
                            PrivacySegmentation.Letterbox(width, height), PrivacyMaskRenderer::pixelatedBlur)
                        output.recycle()
                        Log.i(TAG, "protected_render_cpu=${width}x$height sample=$sample ms=${(System.nanoTime() - started) / 1e6}")
                    }
                } } finally { bitmap.recycle() }
            }
        }
        val image = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(102, 128, 153))
        }
        try {
            PrivacyFaceModel(context).use { model ->
                repeat(4) { sample ->
                    val started = System.nanoTime()
                    val embedding = model.predict(image, floatArrayOf(.34f, .46f, .66f, .46f,
                        .50f, .64f, .37f, .82f, .63f, .82f))
                    assertEquals(512, embedding.size)
                    Log.i(TAG, "recognizer sample=$sample ms=${(System.nanoTime() - started) / 1e6}")
                }
            }
        } finally { image.recycle() }
    }

    private fun requirePhysicalGpu() {
        assumeFalse(android.os.Build.MODEL.startsWith("sdk_") ||
            android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"))
    }

    companion object { private const val TAG = "PrivacyPerformance" }
}
