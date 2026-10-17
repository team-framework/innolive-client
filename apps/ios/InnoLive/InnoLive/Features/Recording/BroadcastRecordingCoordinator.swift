import Combine
import Foundation

/// Couples confirmed broadcast lifetime to one local writer, without making recording errors stop live video.
@MainActor
final class BroadcastRecordingCoordinator: ObservableObject {
    @Published private(set) var notice: String?
    let store: RecordingSessionStore
    private let lifecycle: BroadcastLifecycle
    private let attachSink: (LocalBroadcastRecorder?) -> Void
    private struct Active {
        let sessionID: String
        let generation: UInt
        let recorder: LocalBroadcastRecorder?
        var confirmedLive = false
    }
    private var active: Active?
    private var pending: [UUID: Task<Void, Never>] = [:]
    private var retryGeneration: UInt?

    init(store: RecordingSessionStore, lifecycle: BroadcastLifecycle,
         attachSink: @escaping (LocalBroadcastRecorder?) -> Void) {
        self.store = store
        self.lifecycle = lifecycle
        self.attachSink = attachSink
    }

    /// Only call immediately before the initial go-live request, after the countdown has completed.
    /// Additional platforms share the active recorder.
    func arm(sessionID: String) {
        guard active == nil, lifecycle.phase == .starting else { return }
        notice = nil
        retryGeneration = nil
        let generation = lifecycle.generation
        do {
            let recorder = try store.begin(sessionID: sessionID) { [weak self] message in
                Task { @MainActor [weak self] in
                    guard let self, self.lifecycle.isCurrent(generation),
                          self.active?.generation == generation else { return }
                    self.notice = message
                    self.lifecycle.recordingFailed(message, generation: generation)
                }
            }
            active = Active(sessionID: sessionID, generation: generation, recorder: recorder)
            attachSink(recorder)
            lifecycle.recordingStarted(generation: generation)
        } catch {
            // Keep an attempted binding so a second platform cannot start a different file mid-broadcast.
            active = Active(sessionID: sessionID, generation: generation, recorder: nil)
            reportFailure(error, generation: generation)
        }
    }

    /// Call after authoritative response application and after filtering transient mode/reconnect states.
    func reconcile(targets: Set<String>, sessionID: String) {
        guard var binding = active, binding.sessionID == sessionID else { return }
        if !targets.isEmpty {
            binding.confirmedLive = true
            active = binding
        }
        guard binding.recorder != nil else {
            if targets.isEmpty && binding.confirmedLive { active = nil }
            return
        }
        do { try store.reconcileTargets(targets, sessionID: sessionID) }
        catch { reportFailure(error, generation: binding.generation); return }
        // Empty while starting is not evidence of a finished broadcast.
        if targets.isEmpty && binding.confirmedLive { finalize(binding, forceLocalEnd: false) }
    }

    func abortFailedStart(sessionID: String) {
        guard let binding = active, binding.sessionID == sessionID, !binding.confirmedLive else { return }
        attachSink(nil)
        active = nil
        guard binding.recorder != nil else { return }
        do {
            guard let task = try store.beginAbortFailedStart(sessionID: sessionID) else { return }
            track {
                do { try await task.value }
                catch { self.reportFailure(error, generation: binding.generation) }
            }
        } catch { reportFailure(error, generation: binding.generation) }
    }

    /// Call after local capture is stopped, or synchronously at reset before replacing its media bindings.
    /// A locally stopped file can be saved even when the remote stop acknowledgement failed.
    func endLocally() {
        guard let binding = active else { return }
        guard binding.confirmedLive else {
            abortFailedStart(sessionID: binding.sessionID)
            return
        }
        finalize(binding, forceLocalEnd: true)
    }

    /// Reset presentation immediately; already-confirmed files may still finish in the library.
    func reset() {
        endLocally()
        notice = nil
        retryGeneration = nil
    }

    var canRetry: Bool {
        guard let retryGeneration, lifecycle.isCurrent(retryGeneration),
              case .failed(_, let canRetry) = store.state else { return false }
        return canRetry
    }

    func retrySaving() async {
        guard canRetry, let generation = retryGeneration else { return }
        lifecycle.recordingFinalizing(generation: generation)
        do {
            let saved = try await store.retryFinalization()
            lifecycle.recordingSaved(store.fileURL(for: saved), generation: generation)
            if lifecycle.isCurrent(generation) { notice = nil }
        } catch { reportFailure(error, generation: generation) }
    }

    /// Test and shutdown diagnostics can wait for work without changing the UI lifetime.
    func waitForPendingWork() async {
        while !pending.isEmpty {
            let tasks = Array(pending.values)
            for task in tasks { await task.value }
        }
    }

    private func finalize(_ binding: Active, forceLocalEnd: Bool) {
        attachSink(nil)
        active = nil
        guard binding.recorder != nil else { return }
        lifecycle.recordingFinalizing(generation: binding.generation)
        do {
            guard let task = try store.beginFinalization(sessionID: binding.sessionID,
                                                        forceLocalEnd: forceLocalEnd) else { return }
            track {
                do {
                    let saved = try await task.value
                    // The store has decoded actual audio and video samples before reporting saved.
                    self.lifecycle.recordingSaved(self.store.fileURL(for: saved), generation: binding.generation)
                    if self.lifecycle.isCurrent(binding.generation) { self.notice = nil }
                } catch {
                    if self.lifecycle.isCurrent(binding.generation) { self.retryGeneration = binding.generation }
                    self.reportFailure(error, generation: binding.generation)
                }
            }
        } catch { reportFailure(error, generation: binding.generation) }
    }

    private func reportFailure(_ error: Error, generation: UInt) {
        guard lifecycle.isCurrent(generation) else { return }
        notice = error.localizedDescription
        lifecycle.recordingFailed(error.localizedDescription, generation: generation)
    }

    private func track(_ work: @escaping @MainActor () async -> Void) {
        let id = UUID()
        pending[id] = Task { @MainActor in
            await work()
            self.pending[id] = nil
        }
    }
}
