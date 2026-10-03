import Foundation

struct YouTubeBroadcastStatePolicy {
    let stream: YouTubeStreamState?
    let isChangingStreamState: Bool
    var targets: [BroadcastTargetState]? = nil

    var visibleTargets: [BroadcastTargetState] {
        (targets ?? []).filter { $0.stream.broadcastPhaseValue != .idle }
    }

    private var targetPolicies: [Self]? {
        targets.map { $0.map { Self(stream: $0.stream, isChangingStreamState: isChangingStreamState) } }
    }

    var broadcastPhase: YouTubeBroadcastPhase {
        if let targetPolicies {
            if targetPolicies.contains(where: \.hasStartedBroadcast) { return .live }
            for phase: YouTubeBroadcastPhase in [.goingLive, .preparing, .prepared] {
                if targetPolicies.contains(where: { $0.broadcastPhase == phase && $0.streamStatus != .stopped }) {
                    return phase
                }
            }
            return targetPolicies.first(where: {
                if case .unknown = $0.broadcastPhase { return true }
                return false
            })?.broadcastPhase ?? .idle
        }
        return stream?.broadcastPhaseValue ?? .idle
    }

    var streamStatus: YouTubeStreamStatus? {
        if let targetPolicies {
            return targetPolicies.first(where: { $0.isBroadcastActive && !$0.isBroadcastPaused })?.streamStatus
                ?? targetPolicies.first(where: \.isBroadcastActive)?.streamStatus
                ?? targetPolicies.first?.streamStatus
        }
        return stream.map(\.statusValue)
    }

    var isBroadcastSettingsLocked: Bool {
        if let targetPolicies { return isChangingStreamState || targetPolicies.contains(where: \.isBroadcastSettingsLocked) }
        return isChangingStreamState
            || (streamStatus != .some(.stopped) && isActivePhase)
    }

    var isBroadcastActive: Bool {
        if let targetPolicies { return targetPolicies.contains(where: \.isBroadcastActive) }
        return isActivePhase && streamStatus != .some(.stopped)
    }

    var hasStartedBroadcast: Bool {
        if let targetPolicies { return targetPolicies.contains(where: \.hasStartedBroadcast) }
        return broadcastPhase == .live && streamStatus != .some(.stopped)
    }

    var isWaitingForBroadcastStart: Bool {
        switch broadcastPhase {
        case .preparing, .prepared, .goingLive:
            return true
        default:
            return false
        }
    }

    var isBroadcastPaused: Bool {
        if let targetPolicies {
            let active = targetPolicies.filter(\.hasStartedBroadcast)
            return !active.isEmpty && active.allSatisfy(\.isBroadcastPaused)
        }
        switch streamStatus {
        case .some(.paused), .some(.pausedReconfiguring), .some(.pausedReconnecting):
            return true
        default:
            return false
        }
    }

    var canPauseBroadcast: Bool {
        if let targetPolicies { return targetPolicies.contains(where: \.canPauseBroadcast) }
        guard broadcastPhase == .live else { return false }
        switch streamStatus {
        case .some(.streaming), .some(.reconfiguring):
            return true
        default:
            return false
        }
    }

    var canResumeBroadcast: Bool {
        if let targetPolicies { return targetPolicies.contains(where: \.canResumeBroadcast) }
        return broadcastPhase == .live && streamStatus == .some(.paused)
    }

    var canChangePauseState: Bool {
        canPauseBroadcast || canResumeBroadcast
    }

    var streamStatusText: String {
        switch broadcastPhase {
        case .preparing: return String(localized: "YouTube 방송 준비 중…")
        case .prepared: return String(localized: "YouTube 라이브 전환 대기 중…")
        case .goingLive: return String(localized: "YouTube 라이브 전환 중…")
        default: break
        }

        switch streamStatus {
        case .some(.streaming): return String(localized: "YouTube 송출 중")
        case .some(.reconnecting): return String(localized: "YouTube 재연결 중…")
        case .some(.paused): return pausedStatusText(prefix: String(localized: "YouTube 송출 일시 중지됨"))
        case .some(.pausedReconfiguring): return pausedStatusText(prefix: String(localized: "YouTube 일시 중지 준비 중…"))
        case .some(.pausedReconnecting): return pausedStatusText(prefix: String(localized: "YouTube 일시 중지 화면 재연결 중…"))
        case .some(.idle): return String(localized: "YouTube 준비 중…")
        case .some(.stopped): return String(localized: "YouTube 송출 중지됨")
        default: return String(localized: "YouTube 송출 대기")
        }
    }

    private var isActivePhase: Bool {
        switch broadcastPhase {
        case .preparing, .prepared, .goingLive, .live:
            return true
        default:
            return false
        }
    }

    private func pausedStatusText(prefix: String) -> String {
        guard let pausedAt = stream?.pausedAtDate else { return prefix }
        return "\(prefix) (\(pausedAt.formatted(date: .omitted, time: .shortened)))"
    }
}
