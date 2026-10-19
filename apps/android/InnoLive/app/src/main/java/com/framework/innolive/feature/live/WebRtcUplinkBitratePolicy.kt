package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.webrtc.RtpParameters

/** Bandwidth-estimator seed and cap for the server-AI uplink, in bits per second. */
internal data class UplinkBitratePolicy(val startBps: Int, val maxBps: Int)

/**
 * libwebrtc caps a 1080p stream at 2500kbps and seeds the estimator at 300kbps when the app
 * sets nothing. The server re-encodes FHD at 5000k, so the default cap keeps the H.264 QP high
 * and the quality scaler oscillates between 720p and 1080p, recreating MediaCodec each time.
 * The on-device path keeps the library defaults because it may use the software VP8 encoder.
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
 * The server egress runs at a constant 30fps from wall-clock timestamps, so a short frame-rate
 * dip only repeats a frame, while a resolution switch costs an encoder restart of about 300ms.
 */
internal fun uplinkDegradationPreferenceForAI(onDevice: Boolean): RtpParameters.DegradationPreference? =
    if (onDevice) null else RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION

/**
 * Layers the server-AI degradation and encoding cap over [applyVideoSendPolicy] before the offer.
 * Returns false when the session has nothing to add, so the caller can skip setParameters.
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
    // setBitrate only bounds the estimator; the encoder keeps libwebrtc's 2500kbps
    // 1080p default until the encoding's own maximum is raised.
    policy?.let { budget -> parameters.encodings.forEach { it.maxBitrateBps = budget.maxBps } }
    return true
}
