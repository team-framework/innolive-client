import Foundation
import UIKit

extension YouTubeIntegration {
    func endBroadcast(accessToken: String?) async {
        guard !isEndingSession else { return }
        cancelBroadcastCountdown()
        countdownTask?.cancel()
        countdownTask = nil
        let wasAwaitingStart = lifecycle.phase == .starting
        isEndingSession = true
        defer { isEndingSession = false }
        sessionOperationGeneration &+= 1
        invalidateBroadcastOperation()
        if let task = sessionPreparationTask { _ = await task.value }
        clearBackgroundYouTubePauseState()
        if isYouTubeBroadcastActive {
            await stopYouTubeStream(accessToken: accessToken)
        }
        let terminationWarning = currentLifecycleTargets.isEmpty && !wasAwaitingStart ? nil
            : String(localized: "기기 송출은 중단했지만 플랫폼 종료를 확인하지 못했습니다.")
        await stopAllDirectPublish()
        await videoUplink.stopAndWait()
        lifecycle.finishLocalSession(warning: terminationWarning)
        recordingCoordinator.endLocally()
        reconnectTask?.cancel()
        reconnectTask = nil
        videoConnectionConfiguration = nil
        stopPolling()
        await deleteCurrentSession(accessToken: accessToken)
        session = nil
        sessionScope = nil
        clearSessionResponseState()
        liveStartedAt = nil
        videoTrack = nil
        isAnonymizationEnabled = false
        isAIProcessingUnconfirmed = false
        forceReleaseBroadcastOrientationLock()
    }

    /// 첫 시작은 카운트다운을 거친다. 준비된 동시 송출 대상을 다시 시도할 때는 라이브 화면을 유지한다.
    func startBroadcastCountdown(accessToken: String?) async {
        if hasStartedYouTubeBroadcast {
            await goLiveYouTubeStream(accessToken: accessToken)
            return
        }
        guard countdownTask == nil, !isChangingStreamState, !isEndingSession,
              !isChangingBroadcastMode, !isHandlingUpgradeOffer,
              preparationStatus?.isFailed != true, statePolicy.broadcastPhase == .prepared,
              let sessionID = session?.sessionID, let accessToken, !accessToken.isEmpty else { return }
        if lifecycle.phase != .prepared {
            let preparation = lifecycle.beginPreparation()
            lifecycle.finishPreparation(generation: preparation)
        }
        guard let generation = lifecycle.beginCountdown() else { return }
        let sessionGeneration = sessionOperationGeneration
        let lock = lockBroadcastOrientationIfNeeded()
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                // 카운트다운 중에 폴링이나 상황 변화로 준비된 방송이 무효가 될 수 있다.
                if self.lifecycle.isCurrent(generation), case .countdown = self.lifecycle.phase {
                    self.lifecycle.cancelCountdown()
                    if let lock { self.releaseBroadcastOrientationLock(generation: lock) }
                }
            }
            do {
                for step in 0..<3 {
                    try await self.countdownSleep()
                    try Task.checkCancellation()
                    guard self.lifecycle.isCurrent(generation),
                          self.session?.sessionID == sessionID,
                          self.sessionOperationGeneration == sessionGeneration else { return }
                    if step < 2 { self.lifecycle.advanceCountdown(generation: generation) }
                }
                guard self.statePolicy.broadcastPhase == .prepared, !self.isEndingSession,
                      self.lifecycle.beginStarting(generation: generation) else { return }
                await self.goLiveYouTubeStream(accessToken: accessToken)
                guard self.lifecycle.isCurrent(generation) else { return }
                self.synchronizeBroadcastLifecycle()
                if !self.hasStartedYouTubeBroadcast && self.statePolicy.broadcastPhase != .goingLive {
                    self.recordingCoordinator.abortFailedStart(sessionID: .server(sessionID))
                    self.lifecycle.startFailed(generation: generation,
                        message: self.errorMessage ?? String(localized: "방송을 시작하지 못했습니다."))
                    if let lock { self.releaseBroadcastOrientationLock(generation: lock) }
                }
            } catch {
                if self.lifecycle.isCurrent(generation) {
                    self.lifecycle.cancelCountdown()
                    if let lock { self.releaseBroadcastOrientationLock(generation: lock) }
                }
            }
        }
        countdownTask = task
        await task.value
        if countdownTask == task { countdownTask = nil }
    }

    func cancelBroadcastCountdown() {
        guard case .countdown = lifecycle.phase else { return }
        countdownTask?.cancel()
        countdownTask = nil
        lifecycle.cancelCountdown()
        forceReleaseBroadcastOrientationLock()
    }

    func invalidateBroadcastLifecycle() {
        // 확정된 녹화는 보관함에 저장을 마치고, 확정되지 않은 시작은 임시 파일을 버린다.
        recordingCoordinator.reset()
        countdownTask?.cancel()
        countdownTask = nil
        lifecycle.reset()
    }

    private var currentLifecycleTargets: Set<String> {
        if let snapshot = responseState.details.targets {
            return Set(snapshot.filter {
                YouTubeBroadcastStatePolicy(stream: $0.stream, isChangingStreamState: false).hasStartedBroadcast
            }.map(\.provider))
        }
        return hasStartedYouTubeBroadcast ? [responseState.details.resolvedProvider] : []
    }

    func synchronizeBroadcastLifecycle() {
        let targets = currentLifecycleTargets
        if targets.isEmpty && (isChangingBroadcastMode || responseState.details.resolutionSwitch?.status == "switching") { return }
        if case .countdown = lifecycle.phase, statePolicy.broadcastPhase != .prepared {
            cancelBroadcastCountdown()
            return
        }
        // 요청이 끝난 뒤 종료를 분명히 보여 주는 근거가 있을 때만 대기 중인 시작을 실패로 처리한다.
        // 오래된 준비 스냅숏, 비어 있는 부분 응답, 실패한 조회는 종료가 아니다.
        if lifecycle.phase == .starting, targets.isEmpty, !isChangingStreamState {
            let snapshot = responseState.details.targets
            let pending = snapshot?.contains {
                $0.stream.statusValue != .stopped &&
                    ($0.stream.broadcastPhaseValue == .goingLive || $0.stream.broadcastPhaseValue == .preparing)
            } ?? (statePolicy.broadcastPhase == .goingLive || statePolicy.broadcastPhase == .preparing)
            let failedProviders = Set(responseState.details.failedTargets.map(\.provider))
            let explicitlyEnded: Bool
            if let snapshot, !snapshot.isEmpty {
                explicitlyEnded = snapshot.allSatisfy { target in
                    target.stream.statusValue == .stopped || failedProviders.contains(target.provider)
                }
            } else if snapshot == nil {
                explicitlyEnded = stream?.statusValue == .stopped ||
                    failedProviders.contains(responseState.details.resolvedProvider)
            } else {
                explicitlyEnded = !failedProviders.isEmpty
            }
            if !pending && explicitlyEnded {
                let message = errorMessage ?? String(localized: "방송을 시작하지 못했습니다.")
                errorMessage = message
                if let sessionID = session?.sessionID { recordingCoordinator.abortFailedStart(sessionID: .server(sessionID)) }
                lifecycle.startFailed(generation: lifecycle.generation, message: message)
                forceReleaseBroadcastOrientationLock()
                return
            }
        }
        // 대기 중인 플랫폼 때문에 아직 시작 중인 작업이 종료된 것처럼 보이면 안 된다.
        if targets.isEmpty && statePolicy.broadcastPhase == .goingLive { return }
        lifecycle.reconcileTargets(targets, generation: lifecycle.generation)
        if let sessionID = session?.sessionID { recordingCoordinator.reconcile(targets: targets, sessionID: .server(sessionID)) }
    }

    func goLiveYouTubeStream(accessToken: String?) async {
        guard !isChangingBroadcastMode, !isHandlingUpgradeOffer else { return }
        clearError()
        guard !isChangingStreamState else { return }
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }
        guard let session else {
            showError(.sessionRequired)
            return
        }
        guard preparationStatus?.isFailed != true, !targetProviders(for: .goLive).isEmpty,
              statePolicy.broadcastPhase == .prepared || hasStartedYouTubeBroadcast else {
            showError(.api(
                code: "broadcast_not_prepared",
                fallback: String(localized: "방송을 먼저 준비해 주세요."),
                helpURL: nil
            ))
            return
        }

        let attemptedProviders = Set(targetProviders(for: .goLive).map(providerKey))
        let operationGeneration = beginBroadcastOperation()
        let sessionGeneration = sessionOperationGeneration
        let lockGeneration = lockBroadcastOrientationIfNeeded()
        isChangingStreamState = true
        defer {
            if isCurrentBroadcastOperation(operationGeneration) {
                isChangingStreamState = false
                synchronizeBroadcastLifecycle()
            }
        }
        recordingCoordinator.arm(sessionID: .server(session.sessionID),
                                 source: RecordingSource(processingMode: session.processingMode),
                                 mosaic: videoUplink.onDeviceMosaic)
        do {
            let liveResponse = try await goLiveWithRetry(
                session: session,
                accessToken: accessToken,
                operationGeneration: operationGeneration
            )
            guard isCurrentBroadcastOperation(operationGeneration) else { return }
            guard applySessionResponse(liveResponse, sessionID: session.sessionID, generation: sessionGeneration) else { return }
            liveStartFailures.removeAll { attemptedProviders.contains($0.provider) }
            liveStartFailures.append(contentsOf: liveResponse.details.failedTargets)
            // 치지직은 연결하는 순간 공개 방송이 되므로 라이브 전환 응답의 주소로 이때 연결한다.
            var directFailures: [String] = []
            if isDirectPublishSession {
                let failedProviders = Set(liveResponse.details.failedTargets.map(\.provider))
                var endpoints: [String: DirectIngestEndpoint?] = [:]
                for target in liveResponse.directIngest where endpoints[target.provider] == nil {
                    endpoints[target.provider] = target.endpoint
                }
                // 라이브로 전환됐는데 주소가 없거나 쓸 수 없는 대상도 실패로 알린다. 이미 송출 중인 대상과
                // 라이브 전환에 실패한 대상(failed_targets)은 연결하지 않는다.
                let expected = attemptedProviders.filter { Self.receivesDirectIngestAtGoLive($0) }
                let providers = Set(endpoints.keys).union(expected)
                    .filter { directPublisher.states[$0] == nil && !failedProviders.contains($0) }
                    .sorted()
                for provider in providers {
                    let started = await startDirectPublish(provider: provider, endpoint: endpoints[provider] ?? nil, session: session,
                                                           accessToken: accessToken, generation: sessionGeneration)
                    guard isCurrentBroadcastOperation(operationGeneration) else { return }
                    if !started { directFailures.append(errorMessage ?? DirectPublishError.connectionFailed(provider: provider).localizedDescription) }
                }
            }
            if !liveResponse.details.failedTargets.isEmpty {
                errorMessage = liveResponse.details.failedTargets.map(Self.liveStartFailureMessage).joined(separator: "\n")
            }
            if !directFailures.isEmpty {
                errorMessage = ([errorMessage].compactMap { $0 } + directFailures).joined(separator: "\n")
            }
            if hasStartedYouTubeBroadcast { liveStartedAt = liveStartedAt ?? Date() }
            // 서버의 입력 없음 시계는 라이브 전환 시점부터 돈다. 전환 직후 첫 보고를 보낸다.
            if isDirectPublishSession, hasStartedYouTubeBroadcast, !directPublisher.states.isEmpty {
                beginDirectHeartbeat(accessToken: accessToken)
            }
            beginPolling(accessToken: accessToken)
        } catch {
            guard isCurrentBroadcastOperation(operationGeneration) else { return }
            handle(error)
            if !hasStartedYouTubeBroadcast, let lockGeneration {
                releaseBroadcastOrientationLock(generation: lockGeneration)
            }
        }
    }

    /// 종료 전용 요청 조건과 맞추되, 이미 받아들여진 서버 모드 전환은 막지 않는다.
    var canStopBroadcastTargets: Bool {
        !isChangingStreamState && !broadcastModeRequestInFlight && backgroundPauseTask == nil
    }

    func stopYouTubeStream(accessToken: String?, provider: BroadcastSettingsProvider? = nil) async {
        await changeTargets(.stop, accessToken: accessToken, provider: provider)
    }

    func pauseYouTubeStream(accessToken: String?, provider: BroadcastSettingsProvider? = nil) async {
        await changeTargets(.pause, accessToken: accessToken, provider: provider)
    }

    func resumeYouTubeStream(accessToken: String?, provider: BroadcastSettingsProvider? = nil) async {
        await changeTargets(.resume, accessToken: accessToken, provider: provider)
    }

    func targetPolicy(_ provider: BroadcastSettingsProvider) -> YouTubeBroadcastStatePolicy {
        let target = responseState.details.targets?.first { $0.provider == provider.rawValue }?.stream
        let legacy = responseState.details.targets == nil && responseState.details.resolvedProvider == provider.rawValue
        return YouTubeBroadcastStatePolicy(stream: target ?? (legacy ? stream : nil), isChangingStreamState: isChangingStreamState)
    }

    func handleAppMovedToBackground(accessToken: String?) async {
        stopLocalTake()
        cancelBroadcastCountdown()
        shouldResumeAfterBackgroundPause = false
        let shouldPauseForBackground = hasObservedActiveScene && canPauseYouTubeBroadcast
        hasObservedActiveScene = false
        if let backgroundPauseTask {
            await backgroundPauseTask.value
            self.backgroundPauseTask = nil
        }
        guard shouldPauseForBackground else { return }

        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.pauseYouTubeForBackground(accessToken: accessToken)
        }
        backgroundPauseTask = task
        await task.value
        if backgroundPauseTask == task {
            backgroundPauseTask = nil
        }
    }

    func handleAppBecameActive(accessToken: String?) async {
        localTakeUploader.drain()
        hasObservedActiveScene = true
        shouldResumeAfterBackgroundPause = true
        if let backgroundPauseTask {
            await backgroundPauseTask.value
            if self.backgroundPauseTask == backgroundPauseTask { self.backgroundPauseTask = nil }
        }
        await resumeBackgroundTargetsIfNeeded(accessToken: accessToken)
    }

    func resumeBackgroundTargetsIfNeeded(accessToken: String?) async {
        guard hasObservedActiveScene, shouldResumeAfterBackgroundPause, !backgroundPausedProviders.isEmpty,
              !isChangingStreamState, backgroundPauseTask == nil else { return }
        let providers = targetProviders(for: .resume).filter { backgroundPausedProviders.contains(providerKey($0)) }
        guard !providers.isEmpty else { return }
        await changeTargets(.resume, accessToken: accessToken, providers: providers)
        if hasObservedActiveScene { shouldResumeAfterBackgroundPause = !backgroundPausedProviders.isEmpty }
    }

    private func goLiveWithRetry(
        session: YouTubeBroadcastSession,
        accessToken: String,
        operationGeneration: UInt
    ) async throws -> YouTubeSessionResponse {
        let sessionID = session.sessionID
        for attempt in 1...maximumGoLiveAttempts {
            try ensureCurrentGoLiveAttempt(
                operationGeneration: operationGeneration,
                sessionID: sessionID
            )
            do {
                let liveStream = try await api.streamAction(.goLive, session: session, accessToken: accessToken)
                try ensureCurrentGoLiveAttempt(
                    operationGeneration: operationGeneration,
                    sessionID: sessionID
                )
                return liveStream
            } catch let error as YouTubeAPIError {
                guard case let .api(code, _, _) = error,
                      code == "broadcast_not_ready",
                      attempt < maximumGoLiveAttempts else {
                    throw error
                }
                try ensureCurrentGoLiveAttempt(
                    operationGeneration: operationGeneration,
                    sessionID: sessionID
                )
                try await Task.sleep(for: .seconds(1))
                try ensureCurrentGoLiveAttempt(
                    operationGeneration: operationGeneration,
                    sessionID: sessionID
                )
            }
        }
        throw YouTubeAPIError.response
    }

    private func ensureCurrentGoLiveAttempt(
        operationGeneration: UInt,
        sessionID: String
    ) throws {
        guard isCurrentBroadcastOperation(operationGeneration),
              session?.sessionID == sessionID else {
            throw CancellationError()
        }
    }

    private func pauseYouTubeForBackground(accessToken: String?) async {
        let pauseGeneration = backgroundPauseGeneration
        let sessionGeneration = sessionOperationGeneration
        guard canPauseYouTubeBroadcast,
              let accessToken, !accessToken.isEmpty,
              let session else { return }

        await withBackgroundTaskNamed("youtube-background-pause") {
            for provider in targetProviders(for: .pause) {
                guard pauseGeneration == backgroundPauseGeneration,
                      sessionGeneration == sessionOperationGeneration else { return }
                if directPublisher.states[providerKey(provider)] != nil {
                    directPublisher.setPaused(true, provider: providerKey(provider))
                    backgroundPausedProviders.insert(providerKey(provider))
                    continue
                }
                do {
                    let paused = try await api.streamAction(.pause, session: session, accessToken: accessToken, provider: provider)
                    guard pauseGeneration == backgroundPauseGeneration,
                          sessionGeneration == sessionOperationGeneration,
                          applySessionResponse(paused, sessionID: session.sessionID, generation: sessionGeneration, provider: provider) else { return }
                    backgroundPausedProviders.insert(providerKey(provider))
                } catch {
                    // 자동 중지 실패는 다른 대상의 중지를 막지 않으며 홈 오류 배너를 띄우지 않는다.
                }
            }
            guard pauseGeneration == backgroundPauseGeneration, sessionGeneration == sessionOperationGeneration else { return }
            beginPolling(accessToken: accessToken)
        }
    }

    private func withBackgroundTaskNamed(_ name: String, perform work: () async -> Void) async {
        let token = BackgroundTaskToken()
        token.identifier = UIApplication.shared.beginBackgroundTask(withName: name) {
            let identifier = token.identifier
            guard identifier != .invalid else { return }
            token.identifier = .invalid
            UIApplication.shared.endBackgroundTask(identifier)
        }
        await work()
        let identifier = token.identifier
        guard identifier != .invalid else { return }
        token.identifier = .invalid
        UIApplication.shared.endBackgroundTask(identifier)
    }

    func clearBackgroundYouTubePauseState() {
        backgroundPauseGeneration &+= 1
        backgroundPausedProviders = []
        shouldResumeAfterBackgroundPause = false
        backgroundPauseTask = nil
    }

    private func providerKey(_ provider: String?) -> String {
        provider ?? responseState.details.resolvedProvider
    }

    private func changeTargets(_ action: YouTubeAPI.StreamAction, accessToken: String?, provider: BroadcastSettingsProvider?) async {
        let providers = targetProviders(for: action).filter { provider == nil || providerKey($0) == provider?.rawValue }
        await changeTargets(action, accessToken: accessToken, providers: providers, presentsEnding: action == .stop && provider == nil)
    }

    private func changeTargets(_ action: YouTubeAPI.StreamAction, accessToken: String?, providers: [String?], presentsEnding: Bool = false) async {
        guard !isChangingStreamState, (!isChangingBroadcastMode || action == .stop), (!isHandlingUpgradeOffer || action == .stop),
              !broadcastModeRequestInFlight, backgroundPauseTask == nil else { return }
        clearError()
        guard let accessToken, !accessToken.isEmpty else { showError(.unauthorized); return }
        guard let session, !providers.isEmpty else { return }
        let operationGeneration = beginBroadcastOperation()
        let generation = sessionOperationGeneration
        let lifecycleGeneration = lifecycle.generation
        let presentsEndProgress = presentsEnding && lifecycle.beginEnding()
        isChangingStreamState = true
        defer {
            if isCurrentBroadcastOperation(operationGeneration) {
                isChangingStreamState = false
                if presentsEndProgress {
                    lifecycle.finishEnding(remainingTargets: currentLifecycleTargets,
                        generation: lifecycleGeneration, error: errorMessage)
                }
            }
        }
        for provider in providers {
            let key = providerKey(provider)
            targetActionErrors[key] = nil
            // 서버는 온디바이스 세션의 일시 중지를 거부한다. 앱 송출을 일시 중지 안내 화면·무음으로 바꾼다.
            if (action == .pause || action == .resume), directPublisher.states[key] != nil {
                directPublisher.setPaused(action == .pause, provider: key)
                backgroundPausedProviders.remove(key)
                continue
            }
            // 공유 계약(direct-publish-v1) 순서대로 stop 요청 뒤 연결을 끊는다. 요청이 실패해도 끊는다.
            // 치지직은 앱이 연결을 끊어야 방송이 끝난다.
            let closesDirectPublish = action == .stop && directPublisher.states[key] != nil
            do {
                let response = try await api.streamAction(action, session: session, accessToken: accessToken, provider: provider)
                if closesDirectPublish { await directPublisher.stop(provider: key) }
                guard isCurrentBroadcastOperation(operationGeneration) else { return }
                let normalized = action == .stop && !response.isFullSnapshot ? YouTubeSessionResponse(
                    stream: response.stream.markedStoppedByUser(), media: response.media,
                    details: response.details, isFullSnapshot: false, hasMedia: response.hasMedia
                ) : response
                guard applySessionResponse(normalized, sessionID: session.sessionID, generation: generation, provider: provider) else { return }
                backgroundPausedProviders.remove(key)
                if action == .stop { liveStartFailures.removeAll { $0.provider == key } }
            } catch {
                if closesDirectPublish { await directPublisher.stop(provider: key) }
                guard generation == sessionOperationGeneration, isCurrentBroadcastOperation(operationGeneration) else { return }
                handle(error)
                let title = BroadcastSettingsProvider(rawValue: key)?.title ?? key
                targetActionErrors[key] = title + ": " + (errorMessage ?? String(localized: "방송 제어를 완료하지 못했습니다.", table: "Simulcast"))
            }
        }
        let failures = providers.compactMap { targetActionErrors[providerKey($0)] }
        if !failures.isEmpty { errorMessage = failures.joined(separator: "\n") }
        // 대상 종료도 세션 DELETE와 구분한다. 다른 송출과 업링크·폴링을 유지한다.
        beginPolling(accessToken: accessToken)
    }
}

private final class BackgroundTaskToken: @unchecked Sendable {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    var identifier = UIBackgroundTaskIdentifier.invalid
}
