import Foundation

enum CameraQualityPreset: String, CaseIterable, Identifiable {
    case fullHD30 = "1080p - 30fps"
    case fullHD24 = "1080p - 24fps"
    case hd30 = "720p - 30fps"
    case hd24 = "720p - 24fps"

    static let defaultValue = CameraQualityPreset.fullHD30
    /// 해상도는 방송 설정의 송출 화질, 프레임 레이트는 카메라 및 오디오 설정이 고른다. 저장값은 하나로 둔다.
    static let storageKey = "selectedResolution"
    static let frameRates = [30, 24]

    var id: String { rawValue }

    init(resolution: VideoUplinkResolution, framesPerSecond: Int) {
        switch resolution {
        case .fhd: self = framesPerSecond == 24 ? .fullHD24 : .fullHD30
        case .hd720: self = framesPerSecond == 24 ? .hd24 : .hd30
        }
    }

    var resolution: VideoUplinkResolution { height == 1080 ? .fhd : .hd720 }

    func with(resolution: VideoUplinkResolution) -> Self {
        Self(resolution: resolution, framesPerSecond: framesPerSecond)
    }

    func with(framesPerSecond: Int) -> Self {
        Self(resolution: resolution, framesPerSecond: framesPerSecond)
    }

    var width: Int32 {
        switch self {
        case .fullHD30, .fullHD24: return 1920
        case .hd30, .hd24: return 1280
        }
    }

    var height: Int32 {
        switch self {
        case .fullHD30, .fullHD24: return 1080
        case .hd30, .hd24: return 720
        }
    }

    var framesPerSecond: Int {
        switch self {
        case .fullHD30, .hd30: return 30
        case .fullHD24, .hd24: return 24
        }
    }
}

enum WebRTCVideoUplinkState: Equatable {
    case idle
    case preparing
    case connecting
    case connected
    case failed
}

/// 서버 `/webrtc/config`의 `recovery` 정책이다. 영상 재연결을 몇 번, 얼마 동안 시도할지 정한다.
/// 서버는 이 시간 동안 세션을 복구 대기로 둔다. 서버 교체 배포 중에도 같은 세션으로 다시 붙는다.
struct WebRTCRecoveryPolicy: Equatable, Decodable {
    let windowMilliseconds: Int
    let debounceMilliseconds: Int
    let maximumAttempts: Int

    /// 서버가 정책을 주지 않을 때의 값이다. 서버 기본값(50초, 2초, 10회)과 같다.
    static let `default` = WebRTCRecoveryPolicy(windowMilliseconds: 50_000, debounceMilliseconds: 2_000, maximumAttempts: 10)

    init(windowMilliseconds: Int, debounceMilliseconds: Int, maximumAttempts: Int) {
        self.windowMilliseconds = windowMilliseconds
        self.debounceMilliseconds = debounceMilliseconds
        self.maximumAttempts = maximumAttempts
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let fallback = Self.default
        let window = try container.decodeIfPresent(Int.self, forKey: .windowMilliseconds) ?? fallback.windowMilliseconds
        let debounce = try container.decodeIfPresent(Int.self, forKey: .debounceMilliseconds) ?? fallback.debounceMilliseconds
        let attempts = try container.decodeIfPresent(Int.self, forKey: .maximumAttempts) ?? fallback.maximumAttempts
        windowMilliseconds = window > 0 ? window : fallback.windowMilliseconds
        debounceMilliseconds = debounce >= 0 ? debounce : fallback.debounceMilliseconds
        maximumAttempts = attempts > 0 ? attempts : fallback.maximumAttempts
    }

    var window: Duration { .milliseconds(windowMilliseconds) }

    /// 실패한 시도 다음 시도까지 기다리는 시간이다. 너무 짧으면 같은 실패를 되풀이하므로 1초를 하한으로 둔다.
    var retryDelay: Duration { .milliseconds(max(debounceMilliseconds, 1_000)) }

    private enum CodingKeys: String, CodingKey {
        case windowMilliseconds = "window_ms"
        case debounceMilliseconds = "debounce_ms"
        case maximumAttempts = "max_attempts"
    }
}

/// `/webrtc/config` 응답이다.
struct WebRTCConfiguration: Equatable {
    let iceServers: [WebRTCIceServer]
    let recovery: WebRTCRecoveryPolicy
}

struct VideoRecoveryStatus: Equatable {
    let attempt: Int
    let maximumAttempts: Int

    var title: String {
        String(localized: "복구 시도 중")
    }

    var detail: String {
        String(
            format: String(localized: "네트워크 연결이 끊겼습니다. %d/%d번째 복구를 시도하고 있습니다."),
            attempt,
            maximumAttempts
        )
    }

    var buttonTitle: String {
        String(
            format: String(localized: "복구 시도 중 (%d/%d)"),
            attempt,
            maximumAttempts
        )
    }
}

enum WebRTCVideoUplinkError: LocalizedError {
    case cancelled
    case unauthorized
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .cancelled: return String(localized: "영상 연결이 취소되었습니다.")
        case .unauthorized: return String(localized: "영상 연결 인증이 만료되었습니다.")
        case let .failed(message): return message
        }
    }
}

struct WebRTCSessionCredentials {

    let sessionID: String
    let ownerToken: String
    var processingMode: AIProcessingMode = .server
    var localAnonymizationEnabled = true
    /// 앱 직접 송출 세션은 서버가 음성을 쓰지 않는다. 마이크는 앱 송출용 녹음이 쓰도록 WebRTC에 넘기지 않는다.
    var sendsMicrophoneToServer = true
    var isDirectPublish = false
    var broadcastResolution = VideoUplinkResolution.hd720
}

struct WebRTCIceServer: Codable, Equatable {
    let urls: [String]
    let username: String?
    let credential: String?
}
