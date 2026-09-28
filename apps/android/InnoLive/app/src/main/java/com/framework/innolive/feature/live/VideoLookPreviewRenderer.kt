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
    /** Upright, unmirrored images sampled from the camera before the active color adjustment. */
    val previews: Map<VideoLookPreset, Bitmap>,
)

internal class VideoLookPreviewRenderer : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "VideoLookPreviews").apply { isDaemon = true }
    }
    private val gate = VideoLookPreviewGate()
    private val mutablePreviews = MutableStateFlow<VideoLookPreviews?>(null)
    private val cachedPresetImages = AtomicReference<Map<VideoLookPreset, Bitmap>?>(null)
    private val latestSettings = AtomicReference(BroadcastVideoQualitySettings())
    val previews: StateFlow<VideoLookPreviews?> = mutablePreviews.asStateFlow()
    val isEnabled: Boolean get() = gate.isEnabled

    fun setEnabled(enabled: Boolean) {
        gate.setEnabled(enabled) { mutablePreviews.value = null }
    }

    fun resetSample() {
        gate.reset {
            cachedPresetImages.set(null)
            mutablePreviews.value = null
        }
    }

    /** Only copy camera-owned bytes here; conversion, resizing and rendering run off capture. */
    fun offer(image: ImageProxy, settings: BroadcastVideoQualitySettings, appliedExposureEV: Float) {
        latestSettings.set(settings)
        val generation = gate.tryStart(System.nanoTime()) ?: return
        val selectedPreset = VideoLookPreset.entries.firstOrNull { it.matches(settings) }
        val cached = cachedPresetImages.get()
        // Preserve the other cards across manual adjustments and sheet reopenings.
        // Only the selected look can use the camera's actual hardware exposure.
        if (cached != null && selectedPreset == null) {
            gate.finish(generation) {
                if (mutablePreviews.value?.settings != settings || mutablePreviews.value?.previews !== cached) {
                    mutablePreviews.value = VideoLookPreviews(settings, cached)
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
                    if (cached == null) {
                        val rendered = render(source, settings, appliedExposureEV)
                        gate.finish(generation) {
                            cachedPresetImages.set(rendered.previews)
                            mutablePreviews.value = rendered.copy(settings = latestSettings.get())
                        }
                    } else {
                        val preset = checkNotNull(selectedPreset)
                        val actual = renderLook(source, preset, 0f, 0f)
                        gate.finish(generation) {
                            val currentSettings = latestSettings.get()
                            if (currentSettings == settings) {
                                val updated = cached + (preset to actual)
                                cachedPresetImages.set(updated)
                                mutablePreviews.value = VideoLookPreviews(settings, updated)
                            } else {
                                mutablePreviews.value = VideoLookPreviews(currentSettings, cached)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // A thumbnail failure never substitutes a raw image or affects video capture.
                    gate.finish(generation) {}
                }
            }
        } catch (_: RejectedExecutionException) {
            gate.finish(generation) {}
        }
    }

    override fun close() {
        gate.close {
            cachedPresetImages.set(null)
            mutablePreviews.value = null
        }
        executor.shutdown()
    }

    internal fun render(
        source: PreviewYuvSnapshot,
        settings: BroadcastVideoQualitySettings,
        appliedExposureEV: Float,
    ): VideoLookPreviews = VideoLookPreviews(
        settings,
        VideoLookPreset.entries.associateWith { renderLook(source, it, it.exposureEV, appliedExposureEV) },
    )

    private fun renderLook(
        source: PreviewYuvSnapshot,
        preset: VideoLookPreset,
        targetExposureEV: Float,
        appliedExposureEV: Float,
    ): Bitmap {
        val (width, height) = videoLookPreviewDimensions(source.cropWidth, source.cropHeight)
        val rotated = source.rotation == 90 || source.rotation == 270
        val outputWidth = if (rotated) height else width
        val outputHeight = if (rotated) width else height
        val transform = VideoColorTransform(preset.warmth, preset.saturation)
        val exposure = previewExposureLookup(targetExposureEV, appliedExposureEV)
        val pixels = IntArray(width * height)
        repeat(height) { y ->
            val sourceY = source.cropTop + y * source.cropHeight / height
            repeat(width) { x ->
                val sourceX = source.cropLeft + x * source.cropWidth / width
                val luma = (source.y[sourceY * source.width + sourceX].toInt() and 0xff) - 16
                val chromaIndex = (sourceY / 2) * source.chromaWidth + sourceX / 2
                val u = transform.u[source.u[chromaIndex].toInt() and 0xff] - 128
                val v = transform.v[source.v[chromaIndex].toInt() and 0xff] - 128
                val r = exposure[((298 * luma + 409 * v + 128) shr 8).coerceIn(0, 255)]
                val g = exposure[((298 * luma - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)]
                val b = exposure[((298 * luma + 516 * u + 128) shr 8).coerceIn(0, 255)]
                val index = when (source.rotation) {
                    90 -> x * height + height - 1 - y
                    180 -> (height - 1 - y) * width + width - 1 - x
                    270 -> (width - 1 - x) * height + y
                    else -> y * width + x
                }
                pixels[index] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    }
}

/** Managed memory also supports the pre-connection preview, before WebRTC JNI is initialized. */
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
