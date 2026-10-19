package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.PrivacyCameraBufferPool
import com.framework.innolive.feature.live.privacy.PrivacyCameraPlane
import com.framework.innolive.feature.live.privacy.PrivacyNativePixels
import org.webrtc.VideoFrame
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 윤곽선이 얼굴을 따라가도록 짧게 잡되, 송출 분석 스레드의 부담은 제한합니다. */
private const val FRAME_INTERVAL_NANOS = 150_000_000L

/** 감지 속도를 위해 긴 변을 이 크기로 줄여 ML Kit에 넘깁니다. 좌표는 정규화하므로 원본에 그대로 씁니다. */
private const val DETECTION_MAX_SIDE = 640

/** 얼굴 선택 프레임을 보내는 쪽입니다. */
internal interface FaceSelectionSource {
    fun start()

    fun stop()
}

/**
 * 프레임 간격, 한 번에 하나만 처리하는 규칙, 해제 여부를 함께 지킵니다. 분석 스레드가 [tryBegin]으로 프레임을
 * 맡고 처리 스레드가 [end]로 돌려주며, 메인 스레드의 [dispose] 뒤에는 새 프레임을 맡지 않습니다.
 */
internal class FaceSelectionFrameGate(private val intervalNanos: Long) {
    private val busy = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    @Volatile private var lastFrameAtNanos: Long? = null

    val isDisposed: Boolean get() = disposed.get()

    /** 해제 전이고 앞 프레임 처리가 끝났으며 간격이 지났으면 프레임 하나를 맡습니다. */
    fun tryBegin(nowNanos: Long): Boolean {
        if (disposed.get()) return false
        val last = lastFrameAtNanos
        if (last != null && nowNanos - last < intervalNanos) return false
        if (!busy.compareAndSet(false, true)) return false
        lastFrameAtNanos = nowNanos
        return true
    }

    fun end() {
        busy.set(false)
    }

    /** 처음 해제할 때만 true입니다. */
    fun dispose(): Boolean = disposed.compareAndSet(false, true)
}

/**
 * 방송 카메라를 끄지 않고 가림 처리 전 원본 프레임에서 얼굴을 찾아 [onFrame]으로 넘깁니다. 분석 스레드에서는
 * 미리보기와 같은 영역의 YUV 평면만 복사하고, 감지용 축소·RGB 변환·회전은 별도 스레드가 맡습니다. 전체 해상도
 * RGB 비트맵은 탭한 프레임을 등록할 때만 만듭니다. 프레임은 받은 쪽이 해제하며 송출 출력은 바뀌지 않습니다.
 * [mirrored]면 iOS처럼 프레임을 미리보기와 같이 좌우 반전해 감지·윤곽·등록 이미지가 같은 좌표를 씁니다.
 */
internal class FaceSelectionFrameSource(
    private val analyzer: CameraFrameAnalyzer,
    private val mirrored: Boolean,
    private val detector: FaceSelectionDetector,
    private val onFrame: (FaceSelectionFrame) -> Unit,
    private val onError: () -> Unit,
) : FaceSelectionSource {
    private val conversionExecutor = Executors.newSingleThreadExecutor()
    private val gate = FaceSelectionFrameGate(FRAME_INTERVAL_NANOS)
    private val buffers = PrivacyCameraBufferPool()

    private val tap: (ImageProxy) -> Unit = tap@{ image ->
        if (!gate.tryBegin(System.nanoTime())) return@tap
        val copy = try {
            image.copyCroppedI420(buffers)
        } catch (error: Exception) {
            gate.end()
            fail(error)
            return@tap
        }
        val frameImage = I420FaceSelectionImage(copy, image.imageInfo.rotationDegrees, mirrored)
        try {
            conversionExecutor.execute { detect(frameImage) }
        } catch (_: RejectedExecutionException) {
            frameImage.release()
            gate.end()
        }
    }

    override fun start() {
        if (!gate.isDisposed) analyzer.setFaceFrameTap(tap)
    }

    override fun stop() {
        if (!gate.dispose()) return
        analyzer.clearFaceFrameTap(tap)
        conversionExecutor.shutdown()
        buffers.close()
    }

    private fun detect(image: I420FaceSelectionImage) {
        var handedOff = false
        try {
            if (gate.isDisposed) return
            val bitmap = image.detectionBitmap(DETECTION_MAX_SIDE)
            handedOff = detector.detect(bitmap, image.width, image.height) { faces ->
                try {
                    bitmap.recycle()
                    if (faces == null || gate.isDisposed) {
                        image.release()
                    } else {
                        onFrame(FaceSelectionFrame(image, image.width, image.height, faces))
                    }
                } finally {
                    gate.end()
                }
            }
            if (!handedOff) bitmap.recycle()
        } catch (error: Exception) {
            fail(error)
        } finally {
            if (!handedOff) {
                image.release()
                gate.end()
            }
        }
    }

    private fun fail(error: Exception) {
        Log.e(TAG, "Face selection frame conversion failed", error)
        if (!gate.isDisposed) onError()
    }

    private companion object {
        const val TAG = "FaceSelectionFrames"
    }
}

/** 카메라 방향 그대로의 I420 복사본입니다. 바로 세우기와 좌우 반전은 비트맵으로 바꿀 때 적용합니다. */
private class I420FaceSelectionImage(
    private val buffer: VideoFrame.I420Buffer,
    private val rotationDegrees: Int,
    private val mirrored: Boolean,
) : FaceSelectionImage {
    private val released = AtomicBoolean(false)
    private val uprightSize = FaceSelectionGeometry.uprightSize(buffer.width, buffer.height, rotationDegrees)
    val width: Int get() = uprightSize.first
    val height: Int get() = uprightSize.second

    override fun toUprightBitmap(): Bitmap {
        check(!released.get()) { "Face selection frame was released" }
        return buffer.toBitmap().toUpright(rotationDegrees, mirrored)
    }

    /** 긴 변을 [maxSide] 이하로 줄여 바로 세운 감지용 비트맵입니다. 축소는 I420에서 하므로 RGB 변환량이 작습니다. */
    fun detectionBitmap(maxSide: Int): Bitmap {
        check(!released.get()) { "Face selection frame was released" }
        val scale = min(1f, maxSide.toFloat() / max(buffer.width, buffer.height))
        val scaled = buffer.cropAndScale(
            0,
            0,
            buffer.width,
            buffer.height,
            evenSize(buffer.width * scale),
            evenSize(buffer.height * scale),
        )
        val i420 = try {
            scaled.toI420() ?: error("Face selection frame scaling failed")
        } finally {
            scaled.release()
        }
        val bitmap = try {
            i420.toBitmap()
        } finally {
            i420.release()
        }
        return bitmap.toUpright(rotationDegrees, mirrored)
    }

    override fun release() {
        if (released.compareAndSet(false, true)) buffer.release()
    }

    private fun evenSize(value: Float): Int = (value.roundToInt() / 2 * 2).coerceAtLeast(2)
}

private fun VideoFrame.I420Buffer.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        PrivacyNativePixels.i420ToBitmap(dataY.slice(), strideY, dataU.slice(), strideU, dataV.slice(), strideV, bitmap)
        return bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
}

/**
 * 분석 스레드가 빌려 준 [ImageProxy]에서 미리보기와 같은 자르기 영역의 평면을 I420 버퍼로 복사합니다. 이미지는
 * 닫지 않습니다. 자르기 규칙은 `PrivacyCameraInput.planes`와 같습니다.
 */
private fun ImageProxy.copyCroppedI420(pool: PrivacyCameraBufferPool): VideoFrame.I420Buffer {
    val crop = Rect(cropRect)
    require(
        crop.width() > 0 && crop.height() > 0 && crop.left >= 0 && crop.top >= 0 &&
            crop.right <= width && crop.bottom <= height && planes.size == 3,
    )
    val output = pool.acquire(crop.width(), crop.height())
    try {
        val targets = listOf(
            output.dataY to output.strideY,
            output.dataU to output.strideU,
            output.dataV to output.strideV,
        )
        planes.forEachIndexed { index, plane ->
            val chroma = index > 0
            val left = if (chroma) crop.left / 2 else crop.left
            val top = if (chroma) crop.top / 2 else crop.top
            val planeWidth = if (chroma) (crop.width() + 1) / 2 else crop.width()
            val planeHeight = if (chroma) (crop.height() + 1) / 2 else crop.height()
            val buffer = plane.buffer.duplicate()
            val offset = top.toLong() * plane.rowStride + left.toLong() * plane.pixelStride
            require(offset <= buffer.remaining())
            buffer.position(buffer.position() + offset.toInt())
            val source = buffer.slice()
            val needed = (planeHeight - 1L) * plane.rowStride + (planeWidth - 1L) * plane.pixelStride + 1
            require(plane.pixelStride > 0 && plane.rowStride > 0 && needed <= source.remaining())
            val (target, stride) = targets[index]
            PrivacyCameraPlane(source, plane.rowStride, plane.pixelStride, planeWidth, planeHeight)
                .copyTo(target, stride)
        }
        return output
    } catch (error: Throwable) {
        output.release()
        throw error
    }
}
