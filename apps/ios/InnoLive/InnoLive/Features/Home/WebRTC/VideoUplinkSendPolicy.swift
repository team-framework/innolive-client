import Foundation
@preconcurrency import LiveKitWebRTC

/// 서버가 확인한 세션 송출 해상도. 서버는 이 해상도로 업링크를 재인코딩한다.
nonisolated enum VideoUplinkResolution: CaseIterable, Hashable, Sendable {
    case hd720
    case fhd

    /// 알 수 없는 값은 nil이다. 값이 없으면 서버 기본값 720p로 본다(contracts/api/ai-processing-v1.md).
    init?(serverValue: String?) {
        switch serverValue {
        case "720p": self = .hd720
        case "fhd": self = .fhd
        default: return nil
        }
    }

    /// 세션 생성·방송 방식 변경 요청의 `broadcast_resolution` 값이다.
    var serverValue: String {
        switch self {
        case .hd720: return "720p"
        case .fhd: return "fhd"
        }
    }

    var title: String {
        switch self {
        case .hd720: return "720p"
        case .fhd: return "FHD"
        }
    }
}

/// 서버 WebRTC 영상의 용도. 앱 직접 송출 세션은 세션 유지와 입력 확인에만 쓴다.
nonisolated enum VideoUplinkSessionMode: Equatable, Sendable {
    case serverAI
    case onDeviceServerEgress
    case onDeviceDirectPublish
}

/// 서버가 재인코딩하는 영상 업링크의 송신 정책(client #557, #580). 시작·최대·fps·해상도 유지는 Android와 같다.
/// 최소는 의도적으로 다르다. Android는 client #627부터 FHD 1Mbps·720p 0.5Mbps로 낮췄고 iOS는 3Mbps·1.5Mbps를 유지한다.
///
/// 앱이 아무것도 설정하지 않으면 libwebrtc는 약 300kbps에서 램프업하고 1080p를 2.5Mbps로 묶으며,
/// 대역폭이 흔들리면 해상도부터 내린다. 서버는 세션 해상도로 디코더를 고정하므로 낮은 입력이 그대로
/// 확대돼 반환 영상과 방송 출력에 남는다.
nonisolated struct VideoUplinkSendPolicy: Equatable, Sendable {
    /// 대역폭 추정기 시작값. 연결을 시작할 때만 준다.
    let startBitrateBps: Int
    let minBitrateBps: Int
    let maxBitrateBps: Int
    let maxFramerate: Int
    /// 서버 송출은 wall-clock CFR이라 프레임이 잠깐 줄면 직전 프레임을 반복할 뿐이지만,
    /// 해상도가 바뀌면 인코더를 다시 만든다.
    let degradationPreference: LKRTCDegradationPreference

    static let directPublishMaxBitrateBps = 300_000
    static let directPublishMaxFramerate = 15

    static func forSession(_ mode: VideoUplinkSessionMode, resolution: VideoUplinkResolution) -> VideoUplinkSendPolicy? {
        // 직접 송출 중에는 업로드가 두 배가 되지 않게 서버 영상을 낮추므로 라이브러리 기본값을 둔다(#481).
        guard mode != .onDeviceDirectPublish else { return nil }
        // 온디바이스 + 서버 송출은 Android에 대응 경로가 없다. 서버가 같은 방식으로 재인코딩하므로 서버 AI 값을 쓴다.
        switch resolution {
        case .hd720:
            return VideoUplinkSendPolicy(startBitrateBps: 2_000_000, minBitrateBps: 1_500_000,
                                         maxBitrateBps: 3_000_000, maxFramerate: 30,
                                         degradationPreference: .maintainResolution)
        case .fhd:
            return VideoUplinkSendPolicy(startBitrateBps: 4_000_000, minBitrateBps: 3_000_000,
                                         maxBitrateBps: 6_000_000, maxFramerate: 30,
                                         degradationPreference: .maintainResolution)
        }
    }

    /// 영상 sender 파라미터를 한곳에서 정한다. 앱 직접 송출 제한이 켜져 있으면 정책보다 제한이 우선한다.
    static func configure(_ parameters: LKRTCRtpParameters, policy: VideoUplinkSendPolicy?, directPublishLimited: Bool) {
        let applied = directPublishLimited ? nil : policy
        parameters.degradationPreference = applied.map { NSNumber(value: $0.degradationPreference.rawValue) }
        for encoding in parameters.encodings {
            // 추정기 상한만 올리면 인코더는 라이브러리 기본 상한에 머문다(docs/android-on-device-ai.md).
            encoding.minBitrateBps = applied.map { NSNumber(value: $0.minBitrateBps) }
            encoding.maxBitrateBps = directPublishLimited
                ? NSNumber(value: directPublishMaxBitrateBps)
                : applied.map { NSNumber(value: $0.maxBitrateBps) }
            encoding.maxFramerate = directPublishLimited
                ? NSNumber(value: directPublishMaxFramerate)
                : applied.map { NSNumber(value: $0.maxFramerate) }
            encoding.scaleResolutionDownBy = applied.map { _ in NSNumber(value: 1.0) }
        }
    }
}

extension WebRTCSessionCredentials {
    var uplinkSessionMode: VideoUplinkSessionMode {
        if isDirectPublish { return .onDeviceDirectPublish }
        return processingMode == .onDevice ? .onDeviceServerEgress : .serverAI
    }
}
