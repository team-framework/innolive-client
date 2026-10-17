import Combine
import Foundation

/// Client presentation state. Server broadcast phases remain unchanged.
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
    var duration: TimeInterval { max(0, endedAt.timeIntervalSince(startedAt)) }
}

/// Owns presentation transitions; callers own timers, API requests and media writing.
/// A generation must accompany delayed work so cancellation cannot start an old session.
@MainActor
final class BroadcastLifecycle: ObservableObject {
    @Published private(set) var phase: BroadcastLifecyclePhase = .idle
    @Published private(set) var recording: BroadcastRecordingState = .idle
    @Published private(set) var result: BroadcastLifecycleResult?
    @Published private(set) var errorMessage: String?
    private(set) var generation: UInt = 0
    private(set) var startedAt: Date?
    private var activeTargets: Set<String> = []

    var isPresentingStart: Bool {
        if case .countdown = phase { return true }
        return phase == .starting
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

    /// Called after the final countdown interval, immediately before the go-live request.
    /// Arm the writer here; a total start failure must discard its provisional output.
    @discardableResult
    func beginStarting(generation: UInt, at date: Date = Date()) -> Bool {
        guard isCurrent(generation), phase == .countdown(1) else { return false }
        phase = .starting
        startedAt = date
        recording = .armed
        return true
    }

    func startFailed(generation: UInt, message: String) {
        guard isCurrent(generation), phase == .starting, activeTargets.isEmpty else { return }
        // Invalidate callbacks from a provisional writer which the caller discards.
        self.generation &+= 1
        startedAt = nil
        recording = .idle
        errorMessage = message
        phase = .prepared
    }

    /// Include live, paused and reconnecting live targets. Prepared targets are excluded.
    /// Invoke only after applying an authoritative response, never on a failed status fetch.
    func reconcileTargets(_ targets: Set<String>, generation: UInt, at date: Date = Date()) {
        guard isCurrent(generation) else { return }
        guard phase == .starting || phase == .live || phase == .ending else { return }
        activeTargets = targets
        if !targets.isEmpty {
            if phase != .ending { phase = .live }
        } else if phase != .starting, let startedAt {
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

    /// Complete a stop operation with its latest confirmed remaining targets.
    /// Partial failures return to live; an empty confirmed set ends the recording.
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

    /// The writer calls this only after closing and verifying the playable file.
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
        errorMessage = nil
        startedAt = nil
        activeTargets = []
    }
}
