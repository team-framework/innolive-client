package com.framework.innolive.feature.live

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

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
        val generation = gate.tryStart(System.nanoTime()) ?: return
        // Keep the same camera sample across sheet reopenings. Camera exposure changes
        // the source pixels and clipped highlights cannot be recovered by inverse EV.
        cachedPresetImages.get()?.let { images ->
            gate.finish(generation) {
                if (mutablePreviews.value?.settings != settings || mutablePreviews.value?.previews !== images) {
                    mutablePreviews.value = VideoLookPreviews(settings, images)
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
                    val rendered = render(source, settings, appliedExposureEV)
                    gate.finish(generation) {
                        cachedPresetImages.set(rendered.previews)
                        mutablePreviews.value = rendered
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
        val transform = VideoColorTransform(settings.warmth, settings.saturation)
        val exposure = previewExposureLookup(targetExposureEV, appliedExposureEV)
        val pixels = IntArray(width * height)
        repeat(height) { y ->
            val sourceY = source.cropTop + y * source.cropHeight / height
            repeat(width) { x ->
                val sourceX = source.cropLeft + x * source.cropWidth / width
                val luma = (source.y[sourceY * source.width + sourceX].toInt() and 0xff) * 1.16438f
                val chromaIndex = (sourceY / 2) * source.chromaWidth + sourceX / 2
                val u = transform.u[source.u[chromaIndex].toInt() and 0xff]
                val v = transform.v[source.v[chromaIndex].toInt() and 0xff]
                // Match the shipped WebRTC GlGenericDrawer's YUV shader, including offsets.
                val r = exposure[(luma + 1.59603f * v - 0.874202f * 255f).roundToInt().coerceIn(0, 255)]
                val g = exposure[(luma - 0.391762f * u - 0.812968f * v + 0.531668f * 255f).roundToInt().coerceIn(0, 255)]
                val b = exposure[(luma + 2.01723f * u - 1.08563f * 255f).roundToInt().coerceIn(0, 255)]
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
