import Foundation
import os
@preconcurrency import LiveKitWebRTC

/// Android `uplinkBitratePolicyForAI`와 같은 해상도별 값이다.
struct UplinkBitratePolicy: Equatable {
    let startBps: Int
    let maxBps: Int

    /// libwebrtc는 앱이 아무것도 정하지 않으면 추정기를 300kbps에서 시작해 180x320부터 올라온다.
    /// 온디바이스 세션은 앱 직접 송출이 업로드를 쓰므로 서버 쪽 300kbps 제한을 그대로 둔다.
    static func forUplink(processingMode: AIProcessingMode, quality: CameraQualityPreset?) -> Self? {
        guard processingMode == .server, let quality else { return nil }
        return quality.height >= 1080
            ? UplinkBitratePolicy(startBps: 4_000_000, maxBps: 6_000_000)
            : UplinkBitratePolicy(startBps: 2_000_000, maxBps: 3_000_000)
    }
}

private let bitratePolicyLogger = Logger(subsystem: "com.framework.innolive", category: "uplink")

extension WebRTCVideoUplink {
    var uplinkBitratePolicy: UplinkBitratePolicy? {
        guard let credentials else { return nil }
        return UplinkBitratePolicy.forUplink(processingMode: credentials.processingMode, quality: activeVideoQuality)
    }

    /// offer 전에 적용해 첫 키프레임부터 세션 예산을 쓰고, 화질을 바꿀 때 추정기를 시작값으로 다시 맞춘다.
    func applyUplinkBitratePolicy() {
        guard let policy = uplinkBitratePolicy, let peerConnection, let videoSender else { return }
        let parameters = videoSender.parameters
        // 서버는 프레임을 벽시계 기준 30fps로 내보내 짧은 프레임 저하는 반복 프레임으로 끝난다.
        parameters.degradationPreference = NSNumber(value: LKRTCDegradationPreference.maintainResolution.rawValue)
        for encoding in parameters.encodings {
            encoding.maxBitrateBps = NSNumber(value: policy.maxBps)
        }
        videoSender.parameters = parameters
        let applied = peerConnection.setBweMinBitrateBps(
            nil,
            currentBitrateBps: NSNumber(value: policy.startBps),
            maxBitrateBps: NSNumber(value: policy.maxBps)
        )
        bitratePolicyLogger.notice("uplink_bitrate start_bps=\(policy.startBps) max_bps=\(policy.maxBps) applied=\(applied)")
    }
}
