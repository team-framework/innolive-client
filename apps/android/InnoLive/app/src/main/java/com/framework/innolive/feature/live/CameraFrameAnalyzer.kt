package com.framework.innolive.feature.live

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class CameraFrameAnalyzer(
    private val capturerObserver: CapturerObserver? = null,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val captureLock = Any()
    private var enabled = false
    private var closed = false
    private var reportedColorFailure = false
    private val settings = AtomicReference(BroadcastVideoQualitySettings())
    private val appliedExposureEV = AtomicReference(0f)
    private val colorProcessor = VideoColorFrameProcessor()
    private val previewRenderer = VideoLookPreviewRenderer()
    val lookPreviews: StateFlow<VideoLookPreviews?> = previewRenderer.previews

    fun setVideoQualitySettings(value: BroadcastVideoQualitySettings) {
        settings.set(value.normalized())
    }

    fun setAppliedExposureEV(value: Float) {
        appliedExposureEV.set(value.takeIf(Float::isFinite) ?: 0f)
    }

    fun setLookPreviewEnabled(value: Boolean) {
        previewRenderer.setEnabled(value)
    }

    fun start() = synchronized(captureLock) {
        if (!enabled && !closed && capturerObserver != null) {
            enabled = true
            capturerObserver.onCapturerStarted(true)
        }
    }

    fun stop() = synchronized(captureLock) {
        if (enabled) {
            enabled = false
            capturerObserver?.onCapturerStopped()
        }
    }

    override fun close() = synchronized(captureLock) {
        closed = true
        stop()
        previewRenderer.close()
    }

    override fun analyze(image: ImageProxy) {
        try {
            synchronized(captureLock) {
                if (closed || (!enabled && !previewRenderer.isEnabled)) return
                val currentSettings = settings.get()
                previewRenderer.offer(image, currentSettings, appliedExposureEV.get())
                val observer = capturerObserver
                if (!enabled || observer == null) return

                capture(image, currentSettings, observer)
                reportedColorFailure = false
            }
        } catch (exception: Exception) {
            // No raw-frame fallback: an adjustment failure drops this frame before the sender.
            if (!reportedColorFailure) {
                reportedColorFailure = true
                Log.w("CameraFrameAnalyzer", "video_adjustment_frame_dropped type=${exception.javaClass.simpleName}")
            }
        } finally {
            image.close()
        }
    }

    private fun capture(
        image: ImageProxy,
        currentSettings: BroadcastVideoQualitySettings,
        observer: CapturerObserver,
    ) {
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
                relayColorFrame(frame, currentSettings, colorProcessor::process, observer::onFrameCaptured)
            } finally {
                frame.release()
            }
        } finally {
            source.release()
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
    val sourceLimit = source.limit()

    repeat(height) { row ->
        val sourceRow = sourceStart + row * plane.rowStride
        val targetRow = row * targetStride
        if (plane.pixelStride == 1) {
            source.limit(sourceLimit)
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
