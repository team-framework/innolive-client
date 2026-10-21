package com.framework.innolive.feature.live

import org.webrtc.VideoFrame
import kotlin.math.floor

/**
 * 온디바이스 송출 배율입니다. 가장자리에 걸쳐 얼굴 검출이 놓치기 쉬운 영역을 잘라 내고 가운데만 송출합니다.
 * 검출은 자르기 전 전체 프레임에서 하므로, 송출 화면의 테두리 얼굴도 검출기에는 프레임 안쪽에 있습니다.
 */
internal const val ON_DEVICE_EDGE_ZOOM = 1.1f

/** 비율을 맞추느라 [ON_DEVICE_EDGE_ZOOM]보다 더 확대되는 정도의 상한입니다. 넘으면 짝수 반올림만 합니다. */
private const val MAX_EXTRA_ZOOM = 1.02

internal data class CenterCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 최소 [zoom]배 확대한 가운데 영역입니다. 인코더가 비율 차이로 여백을 넣지 않도록 원본 비율을 정확히 지키고,
 * I420 색 평면을 복사 없이 자를 수 있게 위치와 크기를 짝수로 맞춥니다.
 */
internal fun centerZoomCrop(width: Int, height: Int, zoom: Float): CenterCrop {
    require(width > 0 && height > 0 && zoom.isFinite() && zoom >= 1f)
    if (zoom == 1f) return CenterCrop(0, 0, width, height)
    val divisor = gcd(width, height)
    val unitWidth = width / divisor
    val unitHeight = height / divisor
    var units = floor(divisor / zoom.toDouble()).toInt()
    if ((unitWidth % 2 != 0 || unitHeight % 2 != 0) && units % 2 != 0) units--
    val exact = units > 0 && width.toDouble() / (unitWidth * units) <= zoom * MAX_EXTRA_ZOOM
    val cropWidth = if (exact) unitWidth * units else floorEven(width / zoom.toDouble())
    val cropHeight = if (exact) unitHeight * units else floorEven(height / zoom.toDouble())
    require(cropWidth > 0 && cropHeight > 0) { "확대 후 영역이 비었습니다." }
    return CenterCrop(floorEven((width - cropWidth) / 2.0), floorEven((height - cropHeight) / 2.0), cropWidth, cropHeight)
}

/** 가운데를 [zoom]배 확대한 프레임입니다. 돌려받은 참조는 호출한 쪽이 해제합니다. */
internal fun VideoFrame.centerZoomed(zoom: Float): VideoFrame {
    if (zoom == 1f) {
        retain()
        return this
    }
    val crop = centerZoomCrop(buffer.width, buffer.height, zoom)
    // 크기를 바꾸지 않는 자르기는 I420은 평면 위치만, 텍스처는 좌표 변환만 바꿔 복사가 없습니다.
    val cropped = buffer.cropAndScale(crop.x, crop.y, crop.width, crop.height, crop.width, crop.height)
    return VideoFrame(cropped, rotation, timestampNs)
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private fun floorEven(value: Double): Int = floor(value).toInt() and 1.inv()
