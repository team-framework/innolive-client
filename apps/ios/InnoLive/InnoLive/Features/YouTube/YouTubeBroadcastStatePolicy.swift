import Foundation

struct YouTubeBroadcastStatePolicy {
    let stream: YouTubeStreamState?
    let isChangingStreamState: Bool

    var broadcastPhase: YouTubeBroadcastPhase {
        stream?.broadcastPhaseValue ?? .idle
    }

    var streamStatus: YouTubeStreamStatus? {
        stream.map(\.statusValue)
    }

    var isBroadcastSettingsLocked: Bool {
        isChangingStreamState
            || (streamStatus != .some(.stopped) && isActivePhase)
    }

    var isBroadcastActive: Bool {
        isActivePhase && streamStatus != .some(.stopped)
    }

    var hasStartedBroadcast: Bool {
        broadcastPhase == .live && streamStatus != .some(.stopped)
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
        switch streamStatus {
        case .some(.paused), .some(.pausedReconfiguring), .some(.pausedReconnecting):
            return true
        default:
            return false
        }
    }

    var canPauseBroadcast: Bool {
        guard broadcastPhase == .live else { return false }
        switch streamStatus {
        case .some(.streaming), .some(.reconfiguring):
            return true
        default:
            return false
        }
    }

    var canResumeBroadcast: Bool {
        broadcastPhase == .live && streamStatus == .some(.paused)
    }

    var canChangePauseState: Bool {
        canPauseBroadcast || canResumeBroadcast
    }

    var streamStatusText: String {
        switch broadcastPhase {
        case .preparing: return "YouTube 방송 준비 중…"
        case .prepared: return "YouTube 라이브 전환 대기 중…"
        case .goingLive: return "YouTube 라이브 전환 중…"
        default: break
        }

        switch streamStatus {
        case .some(.streaming): return "YouTube 송출 중"
        case .some(.reconnecting): return "YouTube 재연결 중…"
        case .some(.paused): return pausedStatusText(prefix: "YouTube 송출 일시 중지됨")
        case .some(.pausedReconfiguring): return pausedStatusText(prefix: "YouTube 일시 중지 준비 중…")
        case .some(.pausedReconnecting): return pausedStatusText(prefix: "YouTube 일시 중지 화면 재연결 중…")
        case .some(.idle): return "YouTube 준비 중…"
        case .some(.stopped): return "YouTube 송출 중지됨"
        default: return "YouTube 송출 대기"
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
