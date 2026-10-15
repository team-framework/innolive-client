import Foundation

enum BroadcastTutorialStep: Equatable {
    case openPreparation
    case connectAccount
    case startPreparation
    case waitForPreparation
    case retryPreparation
    case goLive
}

/// 안내를 그릴 화면. 방송 설정 시트는 홈과 다른 화면 계층이라 각자 오버레이를 둔다.
enum BroadcastTutorialHost: Equatable {
    case home
    case settingsSheet
}

struct BroadcastTutorialStage: Equatable {
    let step: BroadcastTutorialStep
    let host: BroadcastTutorialHost
}

struct BroadcastTutorialProgress: Equatable {
    let index: Int
    let total: Int
}

struct BroadcastTutorialSnapshot: Equatable {
    var isSettingsSheetPresented = false
    var selectedAccountsConnected = false
    var preparation: BroadcastPreparationStatus?
    var phase: YouTubeBroadcastPhase = .idle
    var hasStartedBroadcast = false
}

/// 안내 단계는 사용자가 누른 기록이 아니라 지금 앱 상태로 정한다.
/// 시트를 닫거나 준비를 취소해도 맞는 단계로 자연스럽게 돌아간다.
enum BroadcastTutorialPolicy {
    static func stage(for snapshot: BroadcastTutorialSnapshot) -> BroadcastTutorialStage? {
        if snapshot.hasStartedBroadcast || snapshot.phase == .goingLive || snapshot.phase == .live {
            return nil
        }
        if snapshot.isSettingsSheetPresented {
            if let preparation = snapshot.preparation {
                return .init(step: preparation.isFailed ? .retryPreparation : .waitForPreparation, host: .settingsSheet)
            }
            if snapshot.phase == .preparing || snapshot.phase == .prepared {
                return .init(step: .waitForPreparation, host: .settingsSheet)
            }
            return .init(step: snapshot.selectedAccountsConnected ? .startPreparation : .connectAccount, host: .settingsSheet)
        }
        if let preparation = snapshot.preparation {
            return .init(step: preparation.isFailed ? .retryPreparation : .waitForPreparation, host: .home)
        }
        switch snapshot.phase {
        case .prepared: return .init(step: .goLive, host: .home)
        case .preparing: return .init(step: .waitForPreparation, host: .home)
        default: return .init(step: .openPreparation, host: .home)
        }
    }

    static func progress(for step: BroadcastTutorialStep, includesAccountStep: Bool) -> BroadcastTutorialProgress {
        let accountOffset = includesAccountStep ? 1 : 0
        let index: Int
        switch step {
        case .openPreparation: index = 1
        case .connectAccount: index = 2
        case .startPreparation: index = 2 + accountOffset
        case .waitForPreparation, .retryPreparation: index = 3 + accountOffset
        case .goLive: index = 4 + accountOffset
        }
        return .init(index: index, total: 4 + accountOffset)
    }
}
