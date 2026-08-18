import Foundation

enum CameraQualityPreset: String, CaseIterable, Identifiable {
    case fullHD30 = "1080p - 30fps"
    case fullHD24 = "1080p - 24fps"
    case hd30 = "720p - 30fps"
    case hd24 = "720p - 24fps"

    static let defaultValue = CameraQualityPreset.fullHD30

    var id: String { rawValue }

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

enum WebRTCVideoUplinkError: LocalizedError {
    case cancelled
    case unauthorized
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .cancelled: return "영상 연결이 취소되었습니다."
        case .unauthorized: return "영상 연결 인증이 만료되었습니다."
        case let .failed(message): return message
        }
    }
}

struct WebRTCSessionCredentials {
    let sessionID: String
    let ownerToken: String
}

struct WebRTCIceServer: Codable, Equatable {
    let urls: [String]
    let username: String?
    let credential: String?
}
