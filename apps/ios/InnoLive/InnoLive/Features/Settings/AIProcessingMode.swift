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
