package com.framework.innolive.feature.live

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class CameraFrameAnalyzer(
    private val capturerObserver: CapturerObserver,
) : ImageAnalysis.Analyzer {
    private val enabled = AtomicBoolean(false)

    fun start() {
        if (enabled.compareAndSet(false, true)) {
            capturerObserver.onCapturerStarted(true)
        }
    }

    fun stop() {
        if (enabled.compareAndSet(true, false)) {
            capturerObserver.onCapturerStopped()
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
                    capturerObserver.onFrameCaptured(frame)
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
