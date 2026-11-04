import Combine
import Foundation

/// 확정된 방송의 수명을 로컬 writer 하나에 묶는다. 녹화 오류가 나도 라이브 영상은 멈추지 않는다.
@MainActor
final class BroadcastRecordingCoordinator: ObservableObject {
    nonisolated deinit {}
    @Published private(set) var notice: String?
    let store: RecordingSessionStore
    private let lifecycle: BroadcastLifecycle
    private let attachSink: (LocalBroadcastRecorder?) -> Void
    private struct Active {
        let sessionID: CaptureSessionID
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

    /// 카운트다운이 끝난 뒤 첫 go-live 요청 바로 직전에만 호출한다.
    /// 나중에 추가한 플랫폼은 진행 중인 recorder를 함께 쓴다.
    func arm(sessionID: CaptureSessionID, source: RecordingSource = .raw, mosaic: OnDeviceMosaicSettings? = nil) {
        guard active == nil, lifecycle.phase == .starting else { return }
        notice = nil
        retryGeneration = nil
        let generation = lifecycle.generation
        do {
            let recorder = try store.begin(sessionID: sessionID.recordingKey, source: source, mosaic: mosaic) { [weak self] message in
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
            // 시도한 바인딩을 남겨 두어 방송 도중 두 번째 플랫폼이 다른 파일을 시작하지 못하게 한다.
            active = Active(sessionID: sessionID, generation: generation, recorder: nil)
            reportFailure(error, generation: generation)
        }
    }

    /// 서버의 확정 응답을 반영하고 일시적인 모드 전환·재연결 상태를 걸러 낸 뒤에 호출한다.
    func reconcile(targets: Set<String>, sessionID: CaptureSessionID) {
        guard var binding = active, binding.sessionID == sessionID else { return }
        if !targets.isEmpty {
            binding.confirmedLive = true
            active = binding
        }
        guard binding.recorder != nil else {
            if targets.isEmpty && binding.confirmedLive { active = nil }
            return
        }
        do { try store.reconcileTargets(targets, sessionID: sessionID.recordingKey) }
        catch { reportFailure(error, generation: binding.generation); return }
        // 시작 중에 대상이 비어 있다고 해서 방송이 끝났다고 볼 수는 없다.
        if targets.isEmpty && binding.confirmedLive { finalize(binding, forceLocalEnd: false) }
    }

    func abortFailedStart(sessionID: CaptureSessionID) {
        guard let binding = active, binding.sessionID == sessionID, !binding.confirmedLive else { return }
        attachSink(nil)
        active = nil
        guard binding.recorder != nil else { return }
        do {
            guard let task = try store.beginAbortFailedStart(sessionID: sessionID.recordingKey) else { return }
            track {
                do { try await task.value }
                catch { self.reportFailure(error, generation: binding.generation) }
            }
        } catch { reportFailure(error, generation: binding.generation) }
    }

    /// 로컬 캡처를 멈춘 뒤에 호출하거나, reset에서 미디어 바인딩을 교체하기 전에 동기로 호출한다.
    /// 원격 종료 확인이 실패해도 로컬에서 멈춘 파일은 저장할 수 있다.
    func endLocally() {
        guard let binding = active else { return }
        guard binding.confirmedLive else {
            abortFailedStart(sessionID: binding.sessionID)
            return
        }
        finalize(binding, forceLocalEnd: true)
    }

    /// 화면 표시는 바로 초기화한다. 이미 확정된 파일은 그 뒤에도 라이브러리에 저장을 마칠 수 있다.
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
        let observation = observeFinalizationFailure(generation: generation)
        defer { observation.cancel() }
        do {
            let saved = try await store.retryFinalization()
            // 시간이 정해진 백그라운드 lease는 검증된 커밋이 끝나기 전에 만료될 수 있다.
            lifecycle.recordingFinalizing(generation: generation)
            lifecycle.recordingSaved(store.fileURL(for: saved), generation: generation)
            if lifecycle.isCurrent(generation) { notice = nil }
        } catch { reportFailure(error, generation: generation) }
    }

    /// 테스트와 종료 진단에서 UI 수명을 바꾸지 않고 남은 작업을 기다릴 수 있다.
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
        let observation = observeFinalizationFailure(generation: binding.generation)
        do {
            guard let task = try store.beginFinalization(sessionID: binding.sessionID.recordingKey,
                                                        forceLocalEnd: forceLocalEnd) else {
                observation.cancel()
                return
            }
            track {
                defer { observation.cancel() }
                do {
                    let saved = try await task.value
                    // store는 저장 완료를 알리기 전에 실제 음성·영상 샘플을 디코딩해 확인했다.
                    self.lifecycle.recordingFinalizing(generation: binding.generation)
                    self.lifecycle.recordingSaved(self.store.fileURL(for: saved), generation: binding.generation)
                    if self.lifecycle.isCurrent(binding.generation) { self.notice = nil }
                } catch {
                    if self.lifecycle.isCurrent(binding.generation) { self.retryGeneration = binding.generation }
                    self.reportFailure(error, generation: binding.generation)
                }
            }
        } catch {
            observation.cancel()
            reportFailure(error, generation: binding.generation)
        }
    }

    /// store 상태는 MainActor에서 동기로 발행된다. 이 작업 이후의 새 값만 관찰해서
    /// 앞서 실패한 세션의 상태가 새 마무리 저장에 섞이지 않게 한다.
    private func observeFinalizationFailure(generation: UInt) -> AnyCancellable {
        store.$state.dropFirst().sink { [weak self] state in
            guard let self, self.lifecycle.isCurrent(generation),
                  case .failed(let message, _) = state else { return }
            self.notice = message
            self.lifecycle.recordingFailed(message, generation: generation)
        }
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
