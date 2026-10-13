package com.framework.innolive.feature.live.direct.publish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * 일시 중지·백그라운드 동안 카메라 대신 보낼 대기 화면입니다. 크기마다 한 번 만들어 재사용하므로
 * 돌려준 버퍼는 호출자가 [VideoFrame.Buffer.retain]해 씁니다.
 */
internal fun interface SlateSource {
    fun buffer(width: Int, height: Int): VideoFrame.I420Buffer
}

/** 영상 트랙을 알리고 대기 화면을 읽지 못할 때 대신 보내는 검은 화면입니다. */
internal fun blackI420Buffer(width: Int, height: Int): VideoFrame.I420Buffer {
    val planes = I420Planes(width, height)
    // WebRTC 그리기 셰이더는 제한 범위(BT.601)라 Y=16이 검은색입니다.
    planes.y.fill(16)
    planes.u.fill(128.toByte())
    planes.v.fill(128.toByte())
    return planes.toBuffer()
}

/** I420 평면입니다. 홀수 크기는 색차 평면을 올림합니다. */
internal class I420Planes(val width: Int, val height: Int) {
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val y = ByteArray(width * height)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)

    fun toBuffer(): VideoFrame.I420Buffer = JavaI420Buffer.wrap(width, height,
        y.toDirect(), width, u.toDirect(), chromaWidth, v.toDirect(), chromaWidth, null)

    private fun ByteArray.toDirect(): ByteBuffer = ByteBuffer.allocateDirect(size).put(this).apply { flip() }
}

/** ARGB 픽셀을 BT.601 제한 범위 I420으로 바꿉니다. 색차는 2x2 평균입니다. */
internal fun argbToI420(pixels: IntArray, width: Int, height: Int): I420Planes {
    require(width > 0 && height > 0 && pixels.size >= width * height)
    val planes = I420Planes(width, height)
    for (row in 0 until height) {
        for (column in 0 until width) {
            val pixel = pixels[row * width + column]
            val r = pixel shr 16 and 0xff
            val g = pixel shr 8 and 0xff
            val b = pixel and 0xff
            planes.y[row * width + column] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
        }
    }
    for (row in 0 until planes.chromaHeight) {
        for (column in 0 until planes.chromaWidth) {
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (dy in 0..1) for (dx in 0..1) {
                val x = column * 2 + dx
                val y = row * 2 + dy
                if (x >= width || y >= height) continue
                val pixel = pixels[y * width + x]
                r += pixel shr 16 and 0xff
                g += pixel shr 8 and 0xff
                b += pixel and 0xff
                count++
            }
            r /= count
            g /= count
            b /= count
            val index = row * planes.chromaWidth + column
            planes.u[index] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            planes.v[index] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
        }
    }
    return planes
}

/** 원본 가운데를 출력 비율로 잘라 늘이지 않고 채우는 영역입니다. 서버 `cancellationSlateCropRect`와 같습니다. */
internal data class SlateCrop(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun slateCropRect(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): SlateCrop {
    require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
    val target = targetWidth.toLong() * sourceHeight
    val source = targetHeight.toLong() * sourceWidth
    return when {
        target > source -> {
            val cropHeight = (sourceWidth.toLong() * targetHeight / targetWidth).toInt()
            SlateCrop(0, (sourceHeight - cropHeight) / 2, sourceWidth, cropHeight)
        }
        target < source -> {
            val cropWidth = (sourceHeight.toLong() * targetWidth / targetHeight).toInt()
            SlateCrop((sourceWidth - cropWidth) / 2, 0, cropWidth, sourceHeight)
        }
        else -> SlateCrop(0, 0, sourceWidth, sourceHeight)
    }
}

/**
 * 서버 모드 일시 중지 화면과 같은 이미지입니다. asset은 innolive-server
 * `internal/media/assets/cancel-*.png`와 같은 파일이며 iOS도 같은 파일을 씁니다.
 * 방향에 맞는 이미지를 고르고, 읽지 못하면 검은 화면을 보냅니다.
 */
internal class AssetSlateSource(context: Context) : SlateSource {
    private val assets = context.applicationContext.assets
    private val lock = Any()
    private var cached: Pair<Long, VideoFrame.I420Buffer>? = null

    override fun buffer(width: Int, height: Int): VideoFrame.I420Buffer = synchronized(lock) {
        val key = width.toLong() shl 32 or height.toLong()
        cached?.takeIf { it.first == key }?.let { return it.second }
        cached?.second?.release()
        val created = runCatching { render(width, height) }.getOrElse { error ->
            Log.w(LOG_TAG, "slate_unavailable type=${error.javaClass.simpleName}")
            blackI420Buffer(width, height)
        }
        cached = key to created
        created
    }

    private fun render(width: Int, height: Int): VideoFrame.I420Buffer {
        val name = if (width >= height) LANDSCAPE_ASSET else PORTRAIT_ASSET
        val source = assets.open(name).use(BitmapFactory::decodeStream)
            ?: throw IllegalStateException("대기 화면을 읽지 못했습니다.")
        val crop = slateCropRect(source.width, source.height, width, height)
        val cropped = Bitmap.createBitmap(source, crop.x, crop.y, crop.width, crop.height)
        val scaled = cropped.scale(width, height)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        listOf(source, cropped, scaled).distinct().forEach(Bitmap::recycle)
        return argbToI420(pixels, width, height).toBuffer()
    }

    fun close() = synchronized(lock) {
        cached?.second?.release()
        cached = null
    }

    private companion object {
        const val LOG_TAG = "DirectPublish"
        const val LANDSCAPE_ASSET = "direct-publish-pause-landscape.png"
        const val PORTRAIT_ASSET = "direct-publish-pause-portrait.png"
    }
}
