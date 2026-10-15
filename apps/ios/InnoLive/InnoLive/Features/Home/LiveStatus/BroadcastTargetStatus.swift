import Foundation

enum BroadcastTargetTone: Equatable {
    case live
    case ready
    case progress
    case attention
    case ended
}

extension YouTubeBroadcastStatePolicy {
    /// 상태 패널은 플랫폼 이름을 따로 보여 주므로 이름 없는 짧은 상태만 쓴다.
    var targetLabel: String {
        if streamStatus == .some(.stopped) {
            return String(localized: "송출 종료", table: "BroadcastGuide")
        }
        switch broadcastPhase {
        case .preparing: return String(localized: "준비 중", table: "BroadcastGuide")
        case .prepared: return String(localized: "시작 대기", table: "BroadcastGuide")
        case .goingLive: return String(localized: "시작하는 중", table: "BroadcastGuide")
        default: break
        }
        switch streamStatus {
        case .some(.streaming): return String(localized: "송출 중", table: "BroadcastGuide")
        case .some(.reconfiguring): return String(localized: "화질 바꾸는 중", table: "BroadcastGuide")
        case .some(.reconnecting): return String(localized: "다시 연결하는 중", table: "BroadcastGuide")
        case .some(.paused), .some(.pausedReconfiguring), .some(.pausedReconnecting):
            return String(localized: "일시 중지", table: "BroadcastGuide")
        case .some(.idle): return String(localized: "준비 중", table: "BroadcastGuide")
        default: return String(localized: "확인 중", table: "BroadcastGuide")
        }
    }

    var targetTone: BroadcastTargetTone {
        if streamStatus == .some(.stopped) { return .ended }
        switch broadcastPhase {
        case .prepared: return .ready
        case .preparing, .goingLive: return .progress
        default: break
        }
        switch streamStatus {
        case .some(.streaming):
            return .live
        case .some(.reconnecting), .some(.paused), .some(.pausedReconfiguring), .some(.pausedReconnecting):
            return .attention
        default:
            return .progress
        }
    }
}
