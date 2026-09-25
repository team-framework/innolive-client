package com.framework.innolive.feature.live

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.framework.innolive.feature.live.privacy.PrivacyFrameMode
import com.framework.innolive.feature.live.privacy.PrivacyFrameProcessor
import com.framework.innolive.feature.live.privacy.PrivacyFrameRoute
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class CameraFrameAnalyzer(
    private val capturerObserver: CapturerObserver,
    private val applicationContext: Context? = null,
    initialOnDevice: Boolean = false,
    initialAnonymizationEnabled: Boolean = true,
    private val onProcessingFailure: () -> Unit = {},
    private val onProtectedFrameSent: () -> Unit = {},
) : ImageAnalysis.Analyzer {
    private val enabled = AtomicBoolean(false)
    private val protectedFrameReported = AtomicBoolean(false)
    private val processorLock = Any()
    private var localProcessor: PrivacyFrameProcessor? =
        if (initialOnDevice) PrivacyFrameProcessor(checkNotNull(applicationContext)) else null
    private val route = PrivacyFrameRoute(
        when {
            !initialOnDevice -> PrivacyFrameMode.SERVER
            initialAnonymizationEnabled -> PrivacyFrameMode.LOCAL_PROTECTED
            else -> PrivacyFrameMode.LOCAL_RAW
        },
    )

    fun setProcessingMode(onDevice: Boolean, anonymizationEnabled: Boolean) {
        synchronized(processorLock) {
            if (onDevice && localProcessor == null) {
                localProcessor = PrivacyFrameProcessor(checkNotNull(applicationContext))
            }
            route.change(when {
                !onDevice -> PrivacyFrameMode.SERVER
                anonymizationEnabled -> PrivacyFrameMode.LOCAL_PROTECTED
                else -> PrivacyFrameMode.LOCAL_RAW
            })
            protectedFrameReported.set(false)
        }
    }

    fun resetFaceExceptions() {
        synchronized(processorLock) {
            route.invalidate()
            localProcessor?.resetFaceExceptions()
        }
    }

    fun start() {
        if (enabled.compareAndSet(false, true)) {
            capturerObserver.onCapturerStarted(true)
        }
    }

    fun stop() {
        route.stop()
        if (enabled.compareAndSet(true, false)) {
            capturerObserver.onCapturerStopped()
        }
        synchronized(processorLock) {
            localProcessor?.close()
            localProcessor = null
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled.get()) return

            val source = JavaI420Buffer.allocate(image.width, image.height)
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

                val crop = image.cropRect
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
                try {
                    val ticket = route.ticket() ?: return
                    val outgoing = try {
                        if (ticket.mode == PrivacyFrameMode.LOCAL_PROTECTED) {
                            synchronized(processorLock) { checkNotNull(localProcessor).process(frame) }
                        } else {
                            frame
                        }
                    } catch (_: Exception) {
                        route.deliver(ticket, onProcessingFailure)
                        return
                    }
                    try {
                        route.deliver(ticket) {
                            capturerObserver.onFrameCaptured(outgoing)
                            if (ticket.mode == PrivacyFrameMode.LOCAL_PROTECTED &&
                                protectedFrameReported.compareAndSet(false, true)) onProtectedFrameSent()
                        }
                    } finally {
                        if (outgoing !== frame) outgoing.release()
                    }
                } finally {
                    frame.release()
                }
            } finally {
                source.release()
            }
        } finally {
            image.close()
        }
    }
}

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    target: ByteBuffer,
    targetStride: Int,
) {
    val source = plane.buffer.duplicate()
    val sourceStart = source.position()

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
