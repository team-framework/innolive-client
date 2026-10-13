package com.framework.innolive.feature.live.direct.publish

/**
 * 카메라 프레임 시각을 마이크와 같은 단조 시계(`System.nanoTime`)로 옮깁니다.
 *
 * CameraX 타임스탬프는 기기에 따라 부팅 후 경과 시간(`elapsedRealtimeNanos`, 절전 포함) 또는
 * 단조 시계 기준입니다. 도착 시점의 두 시계와 비교해 프레임 시각이 방금 전(0~[MAX_FRAME_AGE_NS])인
 * 쪽을 기준으로 봅니다. 둘 다 아니면 도착 시각으로 대신해 음성과 크게 어긋나지 않게 합니다.
 */
internal fun cameraTimestampToMonotonicNs(frameTimestampNs: Long, nowMonotonicNs: Long, nowRealtimeNs: Long): Long {
    val monotonicAge = nowMonotonicNs - frameTimestampNs
    if (monotonicAge in 0..MAX_FRAME_AGE_NS) return frameTimestampNs
    val realtimeAge = nowRealtimeNs - frameTimestampNs
    if (realtimeAge in 0..MAX_FRAME_AGE_NS) return nowMonotonicNs - realtimeAge
    return nowMonotonicNs
}

private const val MAX_FRAME_AGE_NS = 1_000_000_000L
