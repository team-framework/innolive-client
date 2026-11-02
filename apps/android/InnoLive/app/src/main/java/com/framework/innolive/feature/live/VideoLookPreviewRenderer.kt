package com.framework.innolive.feature.live

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

data class VideoLookPreviews(
    val settings: BroadcastVideoQualitySettings,
    /** 현재 색 조정을 적용하기 전 카메라에서 샘플링한, 바로 세우고 좌우 반전하지 않은 이미지입니다. */
    val previews: Map<VideoLookPreset, Bitmap>,
)

internal class VideoLookPreviewRenderer : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "VideoLookPreviews").apply { isDaemon = true }
    }
    private val gate = VideoLookPreviewGate()
    private val mutablePreviews = MutableStateFlow<VideoLookPreviews?>(null)
    private data class CachedPresets(
        val images: Map<VideoLookPreset, Bitmap>,
        val confirmed: Set<VideoLookPreset>,
    )
    private val cachedPresets = AtomicReference<CachedPresets?>(null)
    val previews: StateFlow<VideoLookPreviews?> = mutablePreviews.asStateFlow()
    val isEnabled: Boolean get() = gate.isEnabled

    fun setEnabled(enabled: Boolean) {
        gate.setEnabled(enabled) { mutablePreviews.value = null }
    }

    fun invalidatePendingSample() = gate.reset {}

    fun resetSample() {
        gate.reset {
            cachedPresets.set(null)
            mutablePreviews.value = null
        }
    }

    /** 여기서는 카메라 소유 바이트만 복사합니다. 변환·크기 조정·렌더링은 캡처 밖에서 실행합니다. */
    fun offer(image: ImageProxy, settings: BroadcastVideoQualitySettings, appliedExposureEV: Float) {
        val generation = gate.tryStart(System.nanoTime()) ?: return
        val selected = VideoLookPreset.entries.firstOrNull { it.matches(settings) }
        val cached = cachedPresets.get()
        // 선택한 프리셋은 안정된 하드웨어 노출로 한 번 확정한 뒤 고정합니다.
        // 직접 조정하거나 시트를 다시 열어도 확정된 이미지는 다시 만들지 않습니다.
        if (cached != null && (selected == null || selected in cached.confirmed)) {
            gate.finish(generation) {
                if (mutablePreviews.value?.settings != settings || mutablePreviews.value?.previews !== cached.images) {
                    mutablePreviews.value = VideoLookPreviews(settings, cached.images)
                }
            }
            return
        }
        val source = try {
            PreviewYuvSnapshot.copy(image)
        } catch (_: Exception) {
            gate.finish(generation) {}
            return
        }
        try {
            executor.execute {
                try {
                    val images = cached?.images ?: render(source, settings, appliedExposureEV).previews
                    val confirmedImages = if (selected != null) {
                        // EV를 시뮬레이션하지 않습니다. 이 원본 카메라 프레임에 이미 선택한 노출이 적용돼 있습니다.
                        images + (selected to renderPixels(source, selected.applyTo(settings), appliedExposureEV, appliedExposureEV))
                    } else images
                    gate.finish(generation) {
                        cachedPresets.set(CachedPresets(confirmedImages, cached?.confirmed.orEmpty() + listOfNotNull(selected)))
                        mutablePreviews.value = VideoLookPreviews(settings, confirmedImages)
                    }
                } catch (_: Exception) {
                    // 썸네일 생성에 실패해도 원본 이미지로 대체하거나 영상 캡처에 영향을 주지 않습니다.
                    gate.finish(generation) {}
                }
            }
        } catch (_: RejectedExecutionException) {
            gate.finish(generation) {}
        }
    }

    override fun close() {
        gate.close {
            cachedPresets.set(null)
            mutablePreviews.value = null
        }
        executor.shutdown()
    }

    internal fun render(
        source: PreviewYuvSnapshot,
        settings: BroadcastVideoQualitySettings,
        appliedExposureEV: Float,
    ): VideoLookPreviews {
        return VideoLookPreviews(settings, VideoLookPreset.entries.associateWith { preset ->
            renderPixels(source, preset.applyTo(settings), preset.exposureEV, appliedExposureEV)
        })
    }

    private fun renderPixels(
        source: PreviewYuvSnapshot,
        settings: BroadcastVideoQualitySettings,
        targetExposureEV: Float,
        appliedExposureEV: Float,
    ): Bitmap {
        val (width, height) = videoLookPreviewDimensions(source.cropWidth, source.cropHeight)
        val rotated = source.rotation == 90 || source.rotation == 270
        val outputWidth = if (rotated) height else width
        val outputHeight = if (rotated) width else height
        val transform = VideoLookPreviewPixelTransform(
            settings.warmth, settings.saturation, targetExposureEV, appliedExposureEV,
        )
        val pixels = IntArray(width * height)
        repeat(height) { y ->
            val sourceY = source.cropTop + y * source.cropHeight / height
            repeat(width) { x ->
                val sourceX = source.cropLeft + x * source.cropWidth / width
                val luma = source.y[sourceY * source.width + sourceX].toInt() and 0xff
                val chromaIndex = (sourceY / 2) * source.chromaWidth + sourceX / 2
                val u = source.u[chromaIndex].toInt() and 0xff
                val v = source.v[chromaIndex].toInt() and 0xff
                val index = when (source.rotation) {
                    90 -> x * height + height - 1 - y
                    180 -> (height - 1 - y) * width + width - 1 - x
                    270 -> (width - 1 - x) * height + y
                    else -> y * width + x
                }
                pixels[index] = transform.pixel(luma, u, v)
            }
        }
        return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    }
}

/** 관리 메모리를 써서 WebRTC JNI 초기화 전인 연결 전 미리보기도 지원합니다. */
internal data class PreviewYuvSnapshot(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropWidth: Int = width,
    val cropHeight: Int = height,
    val rotation: Int = 0,
) {
    val chromaWidth get() = (width + 1) / 2

    companion object {
        fun copy(image: ImageProxy): PreviewYuvSnapshot {
            val crop = image.cropRect
            val chromaWidth = (image.width + 1) / 2
            val chromaHeight = (image.height + 1) / 2
            return PreviewYuvSnapshot(
                image.width, image.height,
                copyPlane(image.planes[0], image.width, image.height),
                copyPlane(image.planes[1], chromaWidth, chromaHeight),
                copyPlane(image.planes[2], chromaWidth, chromaHeight),
                crop.left, crop.top, crop.width(), crop.height(), image.imageInfo.rotationDegrees,
            )
        }

        private fun copyPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int): ByteArray {
            val buffer = plane.buffer.duplicate()
            val start = buffer.position()
            val output = ByteArray(width * height)
            repeat(height) { row ->
                val sourceRow = start + row * plane.rowStride
                if (plane.pixelStride == 1) {
                    buffer.position(sourceRow)
                    buffer.get(output, row * width, width)
                } else {
                    repeat(width) { column ->
                        output[row * width + column] = buffer.get(sourceRow + column * plane.pixelStride)
                    }
                }
            }
            return output
        }
    }
}
