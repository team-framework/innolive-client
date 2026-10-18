package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** 윤곽선이 얼굴을 따라가도록 짧게 잡되, 송출 분석 스레드의 변환 부담은 제한합니다. */
private const val FRAME_INTERVAL_NANOS = 150_000_000L

/**
 * 방송 카메라를 끄지 않고 가림 처리 전 원본 프레임을 얼굴 선택용으로 받습니다. 프레임은 미리보기와 같은
 * 영역으로 자르고 바로 세운 비트맵이며 [onFrame]을 받은 쪽이 재활용합니다. 송출 출력은 바뀌지 않습니다.
 */
internal class FaceSelectionFrameSource(
    private val analyzer: CameraFrameAnalyzer,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: () -> Unit,
) {
    // 자르기·회전은 송출 분석 스레드를 붙잡지 않도록 별도 스레드에서 합니다.
    private val conversionExecutor = Executors.newSingleThreadExecutor()
    private val disposed = AtomicBoolean(false)
    private val converting = AtomicBoolean(false)
    @Volatile private var nextFrameAtNanos = 0L

    private val tap: (ImageProxy) -> Unit = tap@{ image ->
        if (disposed.get()) return@tap
        val now = System.nanoTime()
        if (now < nextFrameAtNanos || !converting.compareAndSet(false, true)) return@tap
        nextFrameAtNanos = now + FRAME_INTERVAL_NANOS
        val rotation = image.imageInfo.rotationDegrees
        val cropRect = Rect(image.cropRect)
        val source = try {
            image.toBitmap()
        } catch (error: Exception) {
            converting.set(false)
            Log.e(TAG, "Face selection frame conversion failed", error)
            if (!disposed.get()) onError()
            return@tap
        }
        try {
            conversionExecutor.execute {
                try {
                    val upright = source.toUprightSelectionFrame(cropRect, rotation)
                    if (disposed.get()) upright.recycle() else onFrame(upright)
                } catch (error: Exception) {
                    Log.e(TAG, "Face selection frame conversion failed", error)
                    if (!disposed.get()) onError()
                } finally {
                    converting.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            source.recycle()
            converting.set(false)
        }
    }

    fun start() {
        if (disposed.get()) return
        analyzer.setFaceFrameTap(tap)
    }

    fun stop() {
        if (!disposed.compareAndSet(false, true)) return
        analyzer.clearFaceFrameTap(tap)
        conversionExecutor.shutdown()
    }

    private companion object {
        const val TAG = "FaceSelectionFrames"
    }
}
