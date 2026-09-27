package com.framework.innolive.feature.live

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.privacy.PrivacyFrameMode
import com.framework.innolive.feature.live.privacy.PrivacyFrameProcessor
import com.framework.innolive.feature.live.privacy.PrivacyFrameRoute
import com.framework.innolive.feature.live.privacy.PrivacyNativePixels
import com.framework.innolive.feature.live.privacy.PrivacyFrameAnalysis
import com.framework.innolive.feature.live.privacy.PrivacyFrameTimings
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.EglBase
import com.framework.innolive.feature.live.privacy.PrivacyGpuBackpressureException
import com.framework.innolive.feature.live.privacy.PrivacyCameraInput
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraFrameAnalyzer(
    private val capturerObserver: CapturerObserver,
    private val applicationContext: Context? = null,
    initialOnDevice: Boolean = false,
    initialAnonymizationEnabled: Boolean = true,
    private val onProcessingFailure: () -> Unit = {},
    private val onProtectedFrameSent: () -> Unit = {},
    private val onCaptureFormat: (Int, Int) -> Unit = { _, _ -> },
    private val sharedEglContext: EglBase.Context? = null,
    private val directCameraInput: Boolean = true,
    private val directGpuInput: Boolean = true,
    private val nativePostprocessing: Boolean = true,
    private val batchTwoOptimizations: Boolean = true,
) : ImageAnalysis.Analyzer {
    private val enabled = AtomicBoolean(false)
    private val protectedFrameReported = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    @Volatile private var protectedPrepared = !batchTwoOptimizations
    private val preparationQueued=AtomicBoolean(false)
    private val preparationLatch=CountDownLatch(1)
    internal fun awaitProtectedPreparation(timeoutSeconds:Long=30):Boolean =
        preparationLatch.await(timeoutSeconds,TimeUnit.SECONDS) && protectedPrepared
    private val resetPending = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "privacy-uplink").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val received = AtomicLong()
    private val dropped = AtomicLong()
    private val delivered = AtomicLong()
    private var lastLogNs = System.nanoTime()
    private var lastCaptureFormat: Pair<Int, Int>? = null
    private var lastStagesLogNs = System.nanoTime()
    internal var onFrameDiagnostics: ((PrivacyCaptureDiagnostics) -> Unit)? = null
    private val processorLock = Any()
    private val cameraBuffers = PrivacyCameraBufferPool()
    @Volatile
    private var localProcessor: PrivacyFrameProcessor? =
        if (initialOnDevice && !batchTwoOptimizations) PrivacyFrameProcessor(checkNotNull(applicationContext), sharedEglContext,directGpuInput=directGpuInput,
            nativePostprocessing=nativePostprocessing,batchTwoOptimizations=false) else null
    private val route = PrivacyFrameRoute(
        when {
            !initialOnDevice -> PrivacyFrameMode.SERVER
            initialAnonymizationEnabled -> PrivacyFrameMode.LOCAL_PROTECTED
            else -> PrivacyFrameMode.LOCAL_RAW
        },
    )

    private fun processor():PrivacyFrameProcessor = localProcessor ?: PrivacyFrameProcessor(
        checkNotNull(applicationContext),sharedEglContext,directGpuInput=directGpuInput,
        nativePostprocessing=nativePostprocessing,batchTwoOptimizations=batchTwoOptimizations,
    ).also {localProcessor=it}

    private fun schedulePreparation() {
        if (!batchTwoOptimizations || protectedPrepared || stopped.get() ||
            !preparationQueued.compareAndSet(false,true)) return
        worker.execute {
            try {
                synchronized(processorLock) {processor().prepare()}
                if (!stopped.get()) protectedPrepared=true
            } catch(error:Exception) {
                Log.w("PrivacyPipeline","prepare_failed type=${error.javaClass.simpleName}")
                route.ticket()?.takeIf {it.mode==PrivacyFrameMode.LOCAL_PROTECTED}?.let {
                    route.deliver(it,onProcessingFailure)
                }
            } finally {
                preparationQueued.set(false)
                preparationLatch.countDown()
            }
        }
    }

    fun setProcessingMode(onDevice: Boolean, anonymizationEnabled: Boolean) {
        if (onDevice && localProcessor == null && !batchTwoOptimizations) {
            synchronized(processorLock) {if(localProcessor==null) processor()}
        }
        resetPending.set(true)
        route.change(when {
            !onDevice -> PrivacyFrameMode.SERVER
            anonymizationEnabled -> PrivacyFrameMode.LOCAL_PROTECTED
            else -> PrivacyFrameMode.LOCAL_RAW
        })
        protectedFrameReported.set(false)
        if (batchTwoOptimizations && !stopped.get()) {
            if (onDevice && anonymizationEnabled && enabled.get()) schedulePreparation()
            else worker.execute { synchronized(processorLock) {localProcessor?.deactivateFaces()} }
        }
    }

    fun resetFaceExceptions() {
        route.invalidate()
        resetPending.set(true)
    }

    fun start() {
        if (enabled.compareAndSet(false, true)) {
            capturerObserver.onCapturerStarted(true)
            if (route.ticket()?.mode==PrivacyFrameMode.LOCAL_PROTECTED) schedulePreparation()
        }
    }

    fun stop() {
        route.stop()
        if (!stopped.compareAndSet(false, true)) return
        if (enabled.compareAndSet(true, false)) {
            capturerObserver.onCapturerStopped()
        }
        worker.execute {
            synchronized(processorLock) {
                localProcessor?.close()
                localProcessor = null
                cameraBuffers.close()
            }
        }
        worker.shutdown()
    }

    override fun analyze(image: ImageProxy) {
        var ticket: PrivacyFrameRoute.Ticket? = null
        var reserved = false
        var transferredImage = false
        try {
            if (!enabled.get()) return
            val currentTicket = route.ticket() ?: return
            ticket = currentTicket
            val crop = image.cropRect
            val format = crop.width() to crop.height()
            if (lastCaptureFormat != format) {
                lastCaptureFormat = format
                onCaptureFormat(format.first, format.second)
            }
            if (currentTicket.mode == PrivacyFrameMode.LOCAL_PROTECTED) {
                received.incrementAndGet()
                if (!protectedPrepared) {dropped.incrementAndGet(); return}
                if (!processing.compareAndSet(false, true)) {
                    dropped.incrementAndGet()
                    return
                }
                reserved = true
                if(directCameraInput) {
                    val camera=PrivacyCameraInput(image)
                    worker.execute {processProtectedCamera(camera,currentTicket)}
                    transferredImage=true
                    reserved=false
                    return
                }
            }
            val copyStarted = System.nanoTime()
            val source = if (reserved) cameraBuffers.acquire(image.width, image.height)
                else JavaI420Buffer.allocate(image.width, image.height)
            try {
                copyPlane(image.planes[0], image.width, image.height, source.dataY, source.strideY)
                copyPlane(
                    image.planes[1],
                    (image.width + 1) / 2,
                    (image.height + 1) / 2,
                    source.dataU,
                    source.strideU,
                )
                copyPlane(
                    image.planes[2],
                    (image.width + 1) / 2,
                    (image.height + 1) / 2,
                    source.dataV,
                    source.strideV,
                )

                val output = source.cropAndScale(
                    crop.left,
                    crop.top,
                    crop.width(),
                    crop.height(),
                    crop.width(),
                    crop.height(),
                )
                val frame = VideoFrame(
                    output,
                    image.imageInfo.rotationDegrees,
                    image.imageInfo.timestamp,
                )
                if (reserved) {
                    val copyMs = (System.nanoTime() - copyStarted) / 1e6
                    try {
                        worker.execute { processProtected(frame, currentTicket, copyMs) }
                        reserved = false // The worker now owns the frame and the in-flight slot.
                    } catch (error: Exception) {
                        frame.release()
                        throw error
                    }
                } else {
                    try {
                        route.deliver(currentTicket) {
                            capturerObserver.onFrameCaptured(frame)
                        }
                    } finally {
                        frame.release()
                    }
                }
            } finally {
                source.release()
            }
        } catch (_: Exception) {
            ticket?.let { route.deliver(it, onProcessingFailure) }
        } finally {
            if (reserved) processing.set(false)
            if(!transferredImage) image.close()
        }
    }

    private fun processProtected(frame: VideoFrame, ticket: PrivacyFrameRoute.Ticket, cameraCopyMs: Double) {
        processProtected(ticket,cameraCopyMs,{it.process(frame)},{frame.release()})
    }

    private fun processProtectedCamera(camera:PrivacyCameraInput,ticket:PrivacyFrameRoute.Ticket) {
        processProtected(ticket,0.0,{it.process(camera)},{camera.close()})
    }

    private fun processProtected(ticket:PrivacyFrameRoute.Ticket,cameraCopyMs:Double,
        transform:(PrivacyFrameProcessor)->VideoFrame,releaseInput:()->Unit) {
        try {
            if(!route.deliver(ticket) {}) return
            val outgoing = synchronized(processorLock) {
                val processor = processor()
                if (resetPending.getAndSet(false)) processor.resetFaceExceptions()
                transform(processor)
            }
            try {
                val deliveryStarted = System.nanoTime()
                val wasDelivered = route.deliver(ticket) {
                    capturerObserver.onFrameCaptured(outgoing)
                    delivered.incrementAndGet()
                    if (protectedFrameReported.compareAndSet(false, true)) onProtectedFrameSent()
                }
                val completed = System.nanoTime()
                if (BuildConfig.DEBUG && wasDelivered) {
                    localProcessor?.let { processor ->
                        val timings = processor.lastTimings
                        val analysis = processor.lastAnalysis
                        if (timings != null && analysis != null) onFrameDiagnostics?.invoke(
                            PrivacyCaptureDiagnostics(cameraCopyMs, (completed - deliveryStarted) / 1e6,
                                timings, analysis, processor.lastCameraCopiedPlanes))
                    }
                }
                if (BuildConfig.DEBUG && completed - lastStagesLogNs >= 5_000_000_000L) {
                    lastStagesLogNs = completed
                    Log.i("PrivacyPipeline", "camera_copy_ms=$cameraCopyMs " +
                        "delivery_ms=${(completed - deliveryStarted) / 1e6}")
                }
            } finally { outgoing.release() }
        } catch (_: PrivacyGpuBackpressureException) {
            dropped.incrementAndGet()
        } catch (_: Exception) {
            route.deliver(ticket, onProcessingFailure)
        } finally {
            releaseInput()
            processing.set(false)
            logCounts()
        }
    }

    private fun logCounts() {
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        if (now - lastLogNs < 5_000_000_000L) return
        lastLogNs = now
        Log.i("PrivacyPipeline", "capture_received=${received.getAndSet(0)} " +
            "busy_dropped=${dropped.getAndSet(0)} delivered=${delivered.getAndSet(0)}")
    }
}

internal data class PrivacyCaptureDiagnostics(
    val cameraCopyMs: Double, val deliveryMs: Double,
    val timings: PrivacyFrameTimings, val analysis: PrivacyFrameAnalysis,
    val cameraCopiedPlanes:Int = 3,
)

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    target: ByteBuffer,
    targetStride: Int,
) {
    val source = plane.buffer.duplicate()
    val sourceStart = source.position()

    if (source.isDirect && target.isDirect) {
        PrivacyNativePixels.copyPlane(source.slice(), plane.rowStride, plane.pixelStride,
            width, height, target.duplicate().apply { clear() }, targetStride)
        target.position(0)
        return
    }

    repeat(height) { row ->
        val sourceRow = sourceStart + row * plane.rowStride
        val targetRow = row * targetStride
        if (plane.pixelStride == 1) {
            source.limit(source.capacity())
            source.position(sourceRow)
            source.limit(sourceRow + width)
            target.position(targetRow)
            target.put(source)
        } else {
            repeat(width) { column ->
                target.put(
                    targetRow + column,
                    source.get(sourceRow + column * plane.pixelStride),
                )
            }
        }
    }
    target.position(0)
}
