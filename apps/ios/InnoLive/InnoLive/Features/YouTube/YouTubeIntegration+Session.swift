import Foundation
#if DEBUG
import os
#endif

extension YouTubeIntegration {
    func prepareSession(accessToken: String?, broadcastResolution: String? = nil) async -> Bool {
        guard !isEndingSession, !isSwitchingToOnDevice else { return false }
        if let task = sessionPreparationTask {
            let generation = sessionOperationGeneration
            let result = await task.value
            return result && generation == sessionOperationGeneration
                && sessionScope == (accessToken.flatMap { try? api.broadcastSessionScope(accessToken: $0) })
        }
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return false
        }
        if session != nil {
            return sessionScope == (try? api.broadcastSessionScope(accessToken: accessToken))
        }

        let generation = sessionOperationGeneration
        let requestedMode = preparationProcessingOverride ?? aiModeProvider()
        let sessionMode = requestedSessionMode
        let faceAnonymization = requestedFaceAnonymization
        isPreparingSession = true
        let task = Task { [self] in
            guard sessionOperationGeneration == generation else { return false }
            do {
                let scope = try api.broadcastSessionScope(accessToken: accessToken)
                try await removePreviousSession(scope: scope, accessToken: accessToken)
                guard isCurrentSessionOperation(generation, scope: scope, accessToken: accessToken) else { return false }
                // 온디바이스 직접 송출은 앱 직접 송출 세션으로 만든다(server #438). 서버 송출을 고르거나
                // 서버가 처리 방식을 받지 않으면 서버 세션에서 서버 AI를 끄고 중계 송출한다.
                var created: YouTubeBroadcastSession
                do {
                    created = try await api.createSession(accessToken: accessToken, mode: sessionMode, broadcastResolution: broadcastResolution,
                                                         faceAnonymization: faceAnonymization)
                } catch YouTubeAPIError.api("plan_resolution_not_allowed"?, _, _) where sessionMode == .server && broadcastResolution == "fhd" {
                    // 요금제가 FHD를 막으면 서버 세션은 720p로 다시 만든다. 카메라는 세션 응답 해상도에 맞춰진다.
                    // 온디바이스 세션은 준비 전 요금제 검사가 안내하므로 그대로 실패시킨다.
                    #if DEBUG
                    Self.qualityLogger.notice("session fhd rejected by plan; retrying 720p")
                    #endif
                    guard isCurrentSessionOperation(generation, scope: scope, accessToken: accessToken) else { return false }
                    created = try await api.createSession(accessToken: accessToken, mode: sessionMode, broadcastResolution: "720p",
                                                         faceAnonymization: faceAnonymization)
                }
                #if DEBUG
                Self.qualityLogger.notice("session created mode=\(sessionMode.rawValue, privacy: .public) requested=\(broadcastResolution ?? "none", privacy: .public) response=\(created.details.broadcastResolution ?? "none", privacy: .public)")
                #endif
                let record = StoredBroadcastSession(sessionID: created.sessionID, ownerToken: created.ownerToken)
                // 초기화 중 도착한 생성 응답도 기록해 다음 연결에서 정리한다.
                do {
                    try sessionStore.save(record, scope: scope)
                } catch {
                    unsavedSessions[scope.storageKey] = record
                    if isCurrentSessionOperation(generation, scope: scope, accessToken: accessToken) {
                        try? await removePreviousSession(scope: scope, accessToken: accessToken)
                    }
                    throw error
                }
                guard isCurrentSessionOperation(generation, scope: scope, accessToken: accessToken) else { return false }
                let createdOnDevice = created.aiProcessing == AIProcessingMode.onDevice.rawValue
                guard created.aiProcessing == nil || created.aiProcessing == AIProcessingMode.server.rawValue
                        || (createdOnDevice && sessionMode == .onDevice) else {
                    try? await removePreviousSession(scope: scope, accessToken: accessToken)
                    throw WebRTCVideoUplinkError.failed(String(localized: "현재 서버에서 선택한 AI 처리 방식을 사용할 수 없습니다."))
                }
                if requestedMode == .onDevice && !createdOnDevice {
                    // Legacy servers expose a per-session AI switch. Verify it is off before
                    // starting capture; an echoed request metadata field alone is not an acknowledgement.
                    do {
                        let confirmed = try await api.toggleAnonymization(session: created, accessToken: accessToken, enabled: false)
                        guard confirmed.media.anonymizationEnabled == false else { throw YouTubeAPIError.response }
                        guard isCurrentSessionOperation(generation, scope: scope, accessToken: accessToken) else { return false }
                        created.clientProcessingMode = .onDevice
                    } catch {
                        try? await removePreviousSession(scope: scope, accessToken: accessToken)
                        throw WebRTCVideoUplinkError.failed(String(localized: "서버 AI 중지를 확인하지 못해 온디바이스 영상 연결을 중단했습니다."))
                    }
                }
                sessionScope = scope
                applyCreatedSessionState(created, accessToken: accessToken)
                isAIProcessingUnconfirmed = false
                if requestedMode == .onDevice { isAnonymizationEnabled = true }
                return true
            } catch {
                if sessionOperationGeneration == generation {
                    handle(error)
                }
                return false
            }
        }
        sessionPreparationTask = task
        let result = await task.value
        sessionPreparationTask = nil
        isPreparingSession = false
        return result && sessionOperationGeneration == generation && session != nil
    }

    private func isCurrentSessionOperation(_ generation: UInt, scope: BroadcastSessionScope, accessToken: String) -> Bool {
        sessionOperationGeneration == generation
            && !Task.isCancelled
            && (try? api.broadcastSessionScope(accessToken: accessToken)) == scope
    }

    func removePreviousSession(scope: BroadcastSessionScope, accessToken: String) async throws {
        guard (try api.broadcastSessionScope(accessToken: accessToken)) == scope else {
            throw YouTubeAPIError.unauthorized
        }
        let previous = try unsavedSessions[scope.storageKey] ?? sessionStore.load(scope: scope)
        guard let previous else { return }
        try await api.deleteSession(previous, accessToken: accessToken)
        try sessionStore.remove(scope: scope)
        unsavedSessions.removeValue(forKey: scope.storageKey)
    }

    func deleteCurrentSession(accessToken: String?) async {
        guard let accessToken else { return }
        let generation = sessionOperationGeneration
        do {
            if let scope = sessionScope ?? (try? api.broadcastSessionScope(accessToken: accessToken)) {
                try await removePreviousSession(scope: scope, accessToken: accessToken)
            } else if let session {
                try await api.deleteSession(
                    .init(sessionID: session.sessionID, ownerToken: session.ownerToken), accessToken: accessToken
                )
            }
        } catch {
            if sessionOperationGeneration == generation { handle(error) }
        }
    }

    private func applyCreatedSessionState(_ created: YouTubeBroadcastSession, accessToken: String) {
        session = created
        isRemainingTimeStale = false
        responseState = BroadcastSessionSnapshot()
        applySessionResponse(
            YouTubeSessionResponse(
                stream: created.stream,
                media: created.media ?? YouTubeSessionMedia(anonymizationEnabled: nil, rawVideoTrack: nil),
                details: created.details,
                hasMedia: created.media != nil
            ),
            sessionID: created.sessionID,
            generation: sessionOperationGeneration
        )
        beginPolling(accessToken: accessToken)
    }

    @discardableResult
    func applySessionResponse(
        _ response: YouTubeSessionResponse,
        sessionID: String,
        generation: UInt,
        provider: String? = nil,
        clearsWarnings: Bool = false
    ) -> Bool {
        guard generation == sessionOperationGeneration, session?.sessionID == sessionID, !Task.isCancelled else {
            return false
        }
        let confirmedLiveTrack = videoTrack?.readyStateValue == .live ? videoTrack : nil
        isRemainingTimeStale = false
        let previouslyLive = Set(liveEditingTargets)
        let previousOffer = upgradeOffer
        responseState.apply(response, provider: provider, clearsWarnings: clearsWarnings)
        responseState.applyDirectPublishStates(directPublisher.states)
        stopDirectPublishForEndedTargets()
        if previousOffer != nil && upgradeOffer == nil { closeUpgradeOffer() }
        if previousOffer?.selected != upgradeOffer?.selected { savedUpgradeProviders = []; confirmedUpgradeMode = nil }
        synchronizeUpgradeOffer()
        responseRevision &+= 1
        broadcastModeAwaitingStatus = false
        reconcileBroadcastModeState()
        synchronizeBroadcastModeQuality()
        // 해상도가 빠진 스냅샷은 무시한다. 카메라 화질 동기화와 같은 값을 따른다.
        if let resolution = VideoUplinkResolution(serverValue: broadcastResolution) {
            videoUplink.setBroadcastResolution(resolution)
        }
        stream = responseState.stream
        unavailableLiveProviders.subtract(Set(liveEditingTargets).subtracting(previouslyLive))
        // 준비·저장 스냅샷은 트랙을 빠뜨릴 수 있다. 이미 확인한 라이브 입력은 유지한다.
        if let track = responseState.media?.rawVideoTrack {
            videoTrack = track
        } else if response.media.rawVideoTrack == nil {
            videoTrack = confirmedLiveTrack
        } else {
            videoTrack = nil
        }
        if session?.processingMode == .server, let enabled = responseState.media?.anonymizationEnabled {
            isAnonymizationEnabled = enabled
        }
        if let enabled = response.media.screenBlurEnabled {
            isScreenBlurEnabled = enabled
        }
        if let config = response.media.mosaicConfig {
            mosaicConfig = config
        }
        synchronizeBroadcastLifecycle()
        if !isYouTubeBroadcastActive && !isChangingBroadcastMode {
            liveStartedAt = nil
            if let generation = ownedOrientationLockGeneration {
                releaseBroadcastOrientationLock(generation: generation)
            }
        }
        return true
    }

    func clearSessionResponseState() {
        resetLiveEditing()
        resetBroadcastMode()
        resetUpgradeOffer()
        targetActionErrors = [:]
        liveStartFailures = []
        preparationFailures = [:]
        isRemainingTimeStale = false
        responseState = BroadcastSessionSnapshot()
        stream = nil
        isChangingAIProcessing = false
        isTogglingAnonymization = false
        isTogglingScreenBlur = false
        isScreenBlurEnabled = false
        isChangingMosaicConfig = false
        pendingMosaicConfig = nil
        mosaicConfig = BroadcastMosaicConfig()
        mosaicConfigErrorMessage = nil
    }

    func targetProviders(for action: YouTubeAPI.StreamAction) -> [String?] {
        if action == .stop, let switching = responseState.details.resolutionSwitch, switching.status == "switching" {
            return switching.targets.filter { BroadcastSettingsProvider(rawValue: $0) != nil }.map { Optional($0) }
        }
        guard let targets = responseState.details.targets else { return [nil] }
        return targets.filter { target in
            guard target.provider == "youtube" || target.provider == "chzzk" else { return false }
            let policy = YouTubeBroadcastStatePolicy(stream: target.stream, isChangingStreamState: false)
            switch action {
            case .goLive: return policy.broadcastPhase == .prepared
            case .stop: return policy.isBroadcastActive
            case .pause: return policy.canPauseBroadcast
            case .resume: return policy.canResumeBroadcast
            }
        }.map { Optional($0.provider) }
    }

    func beginPolling(accessToken: String) {
        stopPolling()
        let generation = pollingGeneration
        let sessionGeneration = sessionOperationGeneration
        let interval = pollingInterval
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: interval)
                } catch {
                    break
                }
                guard let self,
                      let session = self.session,
                      !Task.isCancelled,
                      self.sessionOperationGeneration == sessionGeneration,
                      self.pollingGeneration == generation else {
                    break
                }
                do {
                    let revision = self.responseRevision
                    let snapshot = try await self.api.sessionStatus(session: session, accessToken: accessToken)
                    guard !Task.isCancelled,
                          self.pollingGeneration == generation,
                          self.responseRevision == revision,
                          self.session?.sessionID == session.sessionID else {
                        continue
                    }
                    self.applySessionResponse(snapshot, sessionID: session.sessionID, generation: sessionGeneration)
                    await self.resumeBackgroundTargetsIfNeeded(accessToken: accessToken)
                } catch {
                    guard !Task.isCancelled, self.pollingGeneration == generation else {
                        break
                    }
                    self.isRemainingTimeStale = true
                    // 폴링 실패는 이미 시작된 송출 상태를 지우지 않는다. 다음 주기에 재시도한다.
                }
            }
        }
    }

    func stopPolling() {
        pollingGeneration &+= 1
        pollingTask?.cancel()
        pollingTask = nil
    }

    func waitForVideoTrack(session: YouTubeBroadcastSession, accessToken: String) async throws -> Bool {
        notePreparationPhase(.confirmingVideo)
        if Self.isDirectPublish(session) { return isVideoConnected }
        let generation = sessionOperationGeneration
        for _ in 0..<15 {
            guard videoUplink.state == .connected else {
                throw WebRTCVideoUplinkError.failed(
                    videoUplink.errorMessage ?? String(localized: "카메라 영상 연결이 끊겼습니다. 다시 시작해 주세요.")
                )
            }
            let snapshot = try await api.sessionStatus(session: session, accessToken: accessToken)
            guard applySessionResponse(snapshot, sessionID: session.sessionID, generation: generation) else {
                throw CancellationError()
            }
            if snapshot.media.rawVideoTrack?.readyStateValue == .live {
                guard videoUplink.state == .connected else {
                    throw WebRTCVideoUplinkError.failed(
                        videoUplink.errorMessage ?? String(localized: "카메라 영상 연결이 끊겼습니다. 다시 시작해 주세요.")
                    )
                }
                videoUplink.markPublisherReady()
                return true
            }
            try await Task.sleep(for: .seconds(1))
        }
        throw YouTubeAPIError.videoTrackUnavailable
    }
}
