import Combine
import Foundation

/// 클라이언트 화면 표시용 상태다. 서버 방송 단계는 바꾸지 않는다.
enum BroadcastLifecyclePhase: Equatable {
    case idle
    case preparing
    case prepared
    case countdown(Int)
    case starting
    case live
    case ending
    case result
}

enum BroadcastRecordingState: Equatable {
    case idle
    case armed
    case recording
    case finalizing
    case saved(URL)
    case failed(String)
}

struct BroadcastLifecycleResult: Equatable {
    let startedAt: Date
    let endedAt: Date
    var terminationWarning: String? = nil
    var duration: TimeInterval { max(0, endedAt.timeIntervalSince(startedAt)) }
}

/// 화면 표시 전환만 맡는다. 타이머, API 요청, 미디어 쓰기는 호출자가 맡는다.
/// 지연 작업에는 generation을 함께 넘겨, 취소한 뒤에 이전 세션이 시작되지 않게 한다.
@MainActor
final class BroadcastLifecycle: ObservableObject {
    // iOS 18 객체 해제 호환성: docs/ios-version-support.md 참고.
    nonisolated deinit {}
    @Published private(set) var phase: BroadcastLifecyclePhase = .idle
    @Published private(set) var recording: BroadcastRecordingState = .idle
    @Published private(set) var result: BroadcastLifecycleResult?
    @Published private(set) var errorMessage: String?
    @Published private(set) var resultDismissed = false
    private(set) var generation: UInt = 0
    private(set) var startedAt: Date?
    private var activeTargets: Set<String> = []

    var isPresentingStart: Bool {
        if case .countdown = phase { return true }
        return phase == .starting
    }

    var isPresentingEnd: Bool { phase == .ending || (phase == .result && !resultDismissed) }

    func dismissResult() {
        guard phase == .result else { return }
        resultDismissed = true
    }

    /// 원격 종료 확인을 받을 수 없어도 로컬 정리로 캡처를 끝낼 수 있다.
    /// generation을 유지해 이 화면이 닫힌 뒤에도 대기 중인 writer가 마무리할 수 있게 한다.
    func finishLocalSession(warning: String?, at date: Date = Date()) {
        if phase == .result {
            if let warning { result?.terminationWarning = warning }
            return
        }
        guard let startedAt, phase == .live || phase == .ending || phase == .starting else { return }
        result = BroadcastLifecycleResult(startedAt: startedAt, endedAt: date, terminationWarning: warning)
        resultDismissed = false
        activeTargets = []
        phase = .result
        if recording == .armed || recording == .recording { recording = .finalizing }
    }

    func isCurrent(_ generation: UInt) -> Bool { self.generation == generation }

    @discardableResult
    func beginPreparation() -> UInt {
        reset()
        phase = .preparing
        return generation
    }

    func finishPreparation(generation: UInt, error: String? = nil) {
        guard isCurrent(generation), phase == .preparing else { return }
        errorMessage = error
        phase = error == nil ? .prepared : .idle
    }

    @discardableResult
    func beginCountdown() -> UInt? {
        guard phase == .prepared else { return nil }
        generation &+= 1
        errorMessage = nil
        phase = .countdown(3)
        return generation
    }

    func advanceCountdown(generation: UInt) {
        guard isCurrent(generation), case .countdown(let value) = phase, value > 1 else { return }
        phase = .countdown(value - 1)
    }

    func cancelCountdown() {
        guard case .countdown = phase else { return }
        generation &+= 1
        phase = .prepared
    }

    /// 마지막 카운트다운 간격이 지난 뒤, 라이브 전환 요청 바로 전에 호출한다.
    /// 여기서 writer를 준비 상태로 둔다. 시작이 모두 실패하면 writer의 임시 출력을 버려야 한다.
    @discardableResult
    func beginStarting(generation: UInt, at date: Date = Date()) -> Bool {
        guard isCurrent(generation), phase == .countdown(1) else { return false }
        phase = .starting
        startedAt = date
        recording = .armed
        return true
    }

    @discardableResult
    func beginLocalTake(at date: Date = Date()) -> UInt? {
        guard phase == .idle || phase == .result else { return nil }
        reset()
        phase = .starting
        startedAt = date
        recording = .armed
        return generation
    }

    func startFailed(generation: UInt, message: String) {
        guard isCurrent(generation), phase == .starting, activeTargets.isEmpty else { return }
        // 호출자가 버리는 임시 writer의 콜백을 무효화한다.
        self.generation &+= 1
        startedAt = nil
        recording = .idle
        errorMessage = message
        phase = .prepared
    }

    /// 라이브, 일시 정지, 재연결 중인 라이브 대상을 넣는다. 준비만 된 대상은 뺀다.
    /// 확정 응답을 반영한 뒤에만 호출한다. 상태 조회가 실패했을 때는 호출하지 않는다.
    func reconcileTargets(_ targets: Set<String>, generation: UInt, at date: Date = Date()) {
        guard isCurrent(generation) else { return }
        guard phase == .starting || phase == .live || phase == .ending else { return }
        activeTargets = targets
        if !targets.isEmpty {
            if phase != .ending { phase = .live }
        } else if phase != .starting, let startedAt {
            resultDismissed = false
            result = BroadcastLifecycleResult(startedAt: startedAt, endedAt: date)
            phase = .result
            if recording == .armed || recording == .recording { recording = .finalizing }
        }
    }

    @discardableResult
    func beginEnding() -> Bool {
        guard phase == .live else { return false }
        errorMessage = nil
        phase = .ending
        return true
    }

    /// 종료 작업을 마지막으로 확인된 남은 대상으로 마무리한다.
    /// 일부만 실패하면 라이브로 돌아가고, 확인된 대상이 없으면 녹화를 끝낸다.
    func finishEnding(remainingTargets: Set<String>, generation: UInt, at date: Date = Date(), error: String? = nil) {
        guard isCurrent(generation), phase == .ending else { return }
        errorMessage = error
        reconcileTargets(remainingTargets, generation: generation, at: date)
        if phase == .ending { phase = .live }
    }

    func recordingStarted(generation: UInt) {
        guard isCurrent(generation), recording == .armed else { return }
        recording = .recording
    }

    func recordingFinalizing(generation: UInt) {
        guard isCurrent(generation), recording != .idle else { return }
        if case .saved = recording { return }
        recording = .finalizing
    }

    /// writer는 재생 가능한 파일을 닫고 검증한 뒤에만 이 함수를 호출한다.
    func recordingSaved(_ url: URL, generation: UInt) {
        guard isCurrent(generation), recording == .finalizing else { return }
        recording = .saved(url)
    }

    func recordingFailed(_ message: String, generation: UInt) {
        guard isCurrent(generation), recording != .idle else { return }
        recording = .failed(message)
    }

    func reset() {
        generation &+= 1
        phase = .idle
        recording = .idle
        result = nil
        resultDismissed = false
        errorMessage = nil
        startedAt = nil
        activeTargets = []
    }
}
