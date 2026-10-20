import Foundation

nonisolated enum AIProcessingMode: String, CaseIterable, Codable, Sendable, Identifiable {
    case server
    case onDevice = "on_device"

    static let storageKey = "com.framework.innolive.ai-processing-mode"
    static var selected: AIProcessingMode {
        AIProcessingMode(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .server
    }
    var id: String { rawValue }
    var title: String {
        switch self {
        case .server: String(localized: "서버 AI 모델 사용")
        case .onDevice: String(localized: "온디바이스 AI 모델 사용")
        }
    }
    static var localModelsAvailable: Bool {
        ["PrivacyDetector", "PrivacyFaceRecognizer", "PrivacyYuNet"].allSatisfy {
            Bundle.main.url(forResource: $0, withExtension: "mlmodelc") != nil
        }
    }
}

/// 온디바이스 AI 영상의 송출 경로다. 서버 AI는 항상 서버가 송출한다.
/// 서버 송출은 `server` 세션에서 서버 AI만 끄므로 서버 시간에서 차감된다.
nonisolated enum OnDeviceTransport: String, CaseIterable, Codable, Sendable, Identifiable {
    case direct
    case server

    static let storageKey = "com.framework.innolive.on-device-transport"
    static var selected: OnDeviceTransport {
        OnDeviceTransport(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .direct
    }
    var id: String { rawValue }
    var title: String {
        switch self {
        case .direct: String(localized: "앱 직접 송출")
        case .server: String(localized: "서버 송출")
        }
    }
    var detail: String {
        switch self {
        case .direct: String(localized: "이 기기에서 YouTube·치지직으로 바로 보냅니다. 온디바이스 시간에서 차감됩니다.")
        case .server: String(localized: "서버를 거쳐 보냅니다. 방송 중 일시 중지와 방송 방식 변경을 쓸 수 있고 서버 시간에서 차감됩니다.")
        }
    }
}

/// 서버 AI 세션의 얼굴 비식별화 방법이다. 합성은 관리자 전용 실험 기능이라 실험실에서만 고른다.
/// 세션 생성 때 고정되므로 다음 방송 준비부터 적용한다.
nonisolated enum FaceAnonymizationMode: String, CaseIterable, Codable, Sendable, Identifiable {
    case blur
    case faceSwap = "face_swap"

    static let storageKey = "com.framework.innolive.face-anonymization-mode"
    static var selected: FaceAnonymizationMode {
        FaceAnonymizationMode(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .blur
    }
    var id: String { rawValue }
    var title: String {
        switch self {
        case .blur: String(localized: "블러")
        case .faceSwap: String(localized: "합성")
        }
    }
}
