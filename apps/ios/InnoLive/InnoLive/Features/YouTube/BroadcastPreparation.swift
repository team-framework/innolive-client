import Foundation

enum BroadcastPreparationPhase: Equatable {
    case creatingSession
    case connectingServer
    case confirmingVideo
    case savingSettings
    case preparingStream
    case cancelling

    var title: String {
        switch self {
        case .creatingSession: return String(localized: "세션을 만드는 중")
        case .connectingServer: return String(localized: "서버에 연결하는 중")
        case .confirmingVideo: return String(localized: "서버 영상을 확인하는 중")
        case .savingSettings: return String(localized: "방송 설정을 저장하는 중")
        case .preparingStream: return String(localized: "송출을 준비하는 중")
        case .cancelling: return String(localized: "준비를 취소하는 중")
        }
    }

    var detail: String {
        switch self {
        case .creatingSession: return String(localized: "방송 세션을 만들고 있습니다.")
        case .connectingServer: return String(localized: "카메라와 마이크를 서버에 연결하고 있습니다.")
        case .confirmingVideo: return String(localized: "서버가 영상 입력을 받을 때까지 기다리고 있습니다.")
        case .savingSettings: return String(localized: "선택한 플랫폼의 방송 설정을 저장하고 있습니다.")
        case .preparingStream: return String(localized: "플랫폼 송출을 준비하고 있습니다.")
        case .cancelling: return String(localized: "준비된 연결과 세션을 정리하고 있습니다.")
        }
    }

    var failureMessage: String {
        switch self {
        case .creatingSession: return String(localized: "세션을 만들지 못했습니다.")
        case .connectingServer: return String(localized: "서버에 연결하지 못했습니다.")
        case .confirmingVideo: return String(localized: "서버 영상 입력을 확인하지 못했습니다.")
        case .savingSettings: return String(localized: "방송 설정을 저장하지 못했습니다.")
        case .preparingStream: return String(localized: "송출을 준비하지 못했습니다.")
        case .cancelling: return String(localized: "준비 취소를 마치지 못했습니다.")
        }
    }
}

struct BroadcastPreparationStatus: Equatable {
    var phase: BroadcastPreparationPhase
    var failedPhase: BroadcastPreparationPhase?
    var message: String?

    var isFailed: Bool { failedPhase != nil }
    var isRunning: Bool { failedPhase == nil && phase != .cancelling }
}

struct BroadcastPreparationPermissions: Equatable {
    var hasMediaTransmissionConsent: Bool
    var cameraAuthorized: Bool
    var microphoneAuthorized: Bool
}
