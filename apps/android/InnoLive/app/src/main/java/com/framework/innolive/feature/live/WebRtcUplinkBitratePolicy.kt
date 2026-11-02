package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.webrtc.RtpParameters

/** 서버 AI 업링크의 대역폭 추정 시작값과 상한이며, 단위는 초당 비트입니다. */
internal data class UplinkBitratePolicy(val startBps: Int, val maxBps: Int)

/**
 * 앱이 아무것도 설정하지 않으면 libwebrtc는 1080p 스트림 상한을 2500kbps로, 추정 시작값을
 * 300kbps로 둡니다. 서버는 FHD를 5000k로 재인코딩하므로 기본 상한에서는 H.264 QP가 높게 유지되고,
 * 품질 스케일러가 720p와 1080p 사이를 오가며 매번 MediaCodec을 다시 만듭니다.
 * 온디바이스 경로는 소프트웨어 VP8 인코더를 쓸 수 있으므로 라이브러리 기본값을 유지합니다.
 */
internal fun uplinkBitratePolicyForAI(
    onDevice: Boolean,
    resolution: DirectPublishResolution,
): UplinkBitratePolicy? {
    if (onDevice) return null
    return when (resolution) {
        DirectPublishResolution.FHD -> UplinkBitratePolicy(startBps = 4_000_000, maxBps = 6_000_000)
        DirectPublishResolution.HD_720 -> UplinkBitratePolicy(startBps = 2_000_000, maxBps = 3_000_000)
    }
}

/**
 * 서버 송출은 벽시계 타임스탬프 기준으로 30fps를 일정하게 유지하므로, 짧은 프레임 레이트 저하는
 * 프레임 하나를 반복할 뿐이지만 해상도 전환은 약 300ms의 인코더 재시작 비용이 듭니다.
 */
internal fun uplinkDegradationPreferenceForAI(onDevice: Boolean): RtpParameters.DegradationPreference? =
    if (onDevice) null else RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION

/**
 * offer 전에 [applyVideoSendPolicy] 위에 서버 AI 저하 설정과 인코딩 상한을 덧붙입니다.
 * 세션에 추가할 내용이 없으면 false를 반환해 호출자가 setParameters를 건너뛸 수 있게 합니다.
 */
internal fun applyUplinkSenderPolicy(
    parameters: RtpParameters,
    onDevice: Boolean,
    resolution: DirectPublishResolution,
): Boolean {
    val preference = uplinkDegradationPreferenceForAI(onDevice)
    val policy = uplinkBitratePolicyForAI(onDevice, resolution)
    if (preference == null && policy == null) return false
    preference?.let { parameters.degradationPreference = it }
    // setBitrate는 추정기 범위만 제한합니다. 인코딩 자체의 최댓값을 올리기 전까지 인코더는
    // libwebrtc의 1080p 기본값인 2500kbps를 유지합니다.
    policy?.let { budget -> parameters.encodings.forEach { it.maxBitrateBps = budget.maxBps } }
    return true
}
