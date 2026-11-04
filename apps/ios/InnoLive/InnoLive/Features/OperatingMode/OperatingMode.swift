import Foundation

nonisolated enum LocalReason: Equatable, Sendable {
    case noPath
    case serverUnreachable
    case userChoice

    var broadcastUnavailableReason: String {
        switch self {
        case .noPath: String(localized: "인터넷에 연결되지 않아 송출할 수 없어요.")
        case .serverUnreachable: String(localized: "서버에 연결할 수 없어 송출할 수 없어요.")
        case .userChoice: String(localized: "항상 로컬 모드에서는 송출할 수 없어요.")
        }
    }
}

nonisolated enum OperatingMode: Equatable, Sendable {
    case server
    case suspect(since: ContinuousClock.Instant, failures: Int)
    case local(LocalReason)

    var isLocal: Bool {
        if case .local = self { return true }
        return false
    }
}

nonisolated enum OperatingEvent: Equatable, Sendable {
    case path(satisfied: Bool)
    case probe(succeeded: Bool)
    case refreshUnavailable
    case capture(active: Bool)
    case alwaysLocal(Bool)
    case switchToServer
    case tick
}

/// "서버를 쓸 수 있는가"에 대한 단 하나의 판단이다. 진행 중인 로컬 캡처는 스스로 서버로 돌아가지 않는다.
nonisolated struct OperatingState: Equatable, Sendable {
    static let suspectWindow: Duration = .seconds(2)
    static let recoveryWindow: Duration = .seconds(30)
    static let recoverySuccesses = 3

    private struct Recovery: Equatable, Sendable {
        let firstSuccess: ContinuousClock.Instant
        var successes: Int
    }

    private(set) var mode: OperatingMode = .server
    private(set) var pathSatisfied = true
    private(set) var alwaysLocal = false
    private(set) var captureActive = false
    /// 로컬 모드에서 서버 확인이 충분히 오래 계속 성공했다. "서버 연결됨" 칩으로 보여 준다.
    private(set) var serverAvailable = false
    private var recovery: Recovery?

    init(alwaysLocal: Bool = false) {
        if alwaysLocal { apply(.alwaysLocal(true), at: .now) }
    }

    /// "항상 로컬"에서는 서버를 확인하지 않으므로 사용량 업로드는 네트워크 경로만 기다린다.
    var canUploadUsage: Bool { pathSatisfied && (alwaysLocal || mode == .server || serverAvailable) }

    /// 캡처 중이 아닐 때 서버 확인이 한 번 이상 성공하면 사용자가 서버로 다시 전환할 수 있다.
    var canSwitchToServer: Bool {
        guard case .local(let reason) = mode, reason != .userChoice, !captureActive else { return false }
        return recovery != nil
    }

    mutating func apply(_ event: OperatingEvent, at now: ContinuousClock.Instant) {
        switch event {
        case .alwaysLocal(let enabled):
            alwaysLocal = enabled
            resetRecovery()
            mode = enabled ? .local(.userChoice) : (pathSatisfied ? .server : .suspect(since: now, failures: 0))
            return
        case .path(let satisfied):
            pathSatisfied = satisfied
            if !satisfied { resetRecovery() }
        case .probe(let succeeded):
            if succeeded, mode.isLocal {
                var next = recovery ?? Recovery(firstSuccess: now, successes: 0)
                next.successes += 1
                recovery = next
                serverAvailable = next.successes >= Self.recoverySuccesses
                    && now - next.firstSuccess >= Self.recoveryWindow
            } else if !succeeded {
                resetRecovery()
            }
        case .capture(let active):
            captureActive = active
        case .refreshUnavailable, .switchToServer, .tick:
            break
        }
        guard !alwaysLocal else { return }

        switch mode {
        case .server:
            switch event {
            case .path(satisfied: false), .refreshUnavailable:
                mode = .suspect(since: now, failures: 0)
            case .probe(succeeded: false):
                mode = .suspect(since: now, failures: 1)
            default:
                break
            }
        case .suspect(let since, let failures):
            switch event {
            case .probe(succeeded: true):
                mode = .server
            case .probe(succeeded: false):
                mode = failures + 1 >= 2 ? .local(.serverUnreachable) : .suspect(since: since, failures: failures + 1)
            default:
                break
            }
        case .local:
            if event == .switchToServer, canSwitchToServer {
                mode = .server
                resetRecovery()
            } else if serverAvailable, !captureActive {
                // 다음 세션은 다시 서버를 기본으로 쓴다. 진행 중인 촬영은 로컬을 유지한다.
                mode = .server
                resetRecovery()
            }
        }
        if case .suspect(let since, _) = mode, !pathSatisfied, now - since >= Self.suspectWindow {
            mode = .local(.noPath)
        }
    }

    private mutating func resetRecovery() {
        recovery = nil
        serverAvailable = false
    }
}
