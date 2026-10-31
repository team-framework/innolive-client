import Foundation

extension YouTubeIntegration {
    var canToggleScreenBlur: Bool {
        session?.processingMode == .server && !isTogglingScreenBlur
            && !isChangingAIProcessing && !isTogglingAnonymization && !isAIProcessingUnconfirmed && !isEndingSession
    }

    var canChangeMosaicConfig: Bool {
        session?.processingMode == .server && !isChangingAIProcessing && !isAIProcessingUnconfirmed && !isEndingSession
    }

    func changeAIProcessingMode(_ mode: AIProcessingMode, accessToken: String?) async -> Bool {
        guard !isYouTubeBroadcastActive, !isSwitchingToOnDevice, !isChangingBroadcastMode, !isChangingStreamState, !isPreparingSession,
              !isConnectingVideo, !isEndingSession, !isChangingAIProcessing,
              !isTogglingAnonymization, !isTogglingScreenBlur, !isScreenBlurEnabled, !isChangingMosaicConfig else { return false }
        clearError()
        if mode == .onDevice && !localModelsAvailable() {
            errorMessage = String(localized: "이 앱에 온디바이스 모델이 포함되어 있지 않습니다.")
            return false
        }
        guard let current = session else {
            preparationProcessingOverride = nil
            persistAIProcessingMode(mode)
            return true
        }
        guard let accessToken, !accessToken.isEmpty else { showError(.unauthorized); return false }
        if isDirectPublishSession {
            guard mode != .onDevice else { return true }
            // 서버는 온디바이스 세션의 처리 방식 변경을 거부한다. 새 세션이 필요하다.
            errorMessage = String(localized: "앱 직접 송출 세션에서는 AI 처리 방식을 바꿀 수 없습니다. 방송 준비를 취소한 뒤 다시 선택해 주세요.", table: "DirectPublish")
            return false
        }
        guard current.aiProcessing == nil || current.aiProcessing == AIProcessingMode.server.rawValue else {
            errorMessage = String(localized: "현재 서버에서 선택한 AI 처리 방식을 사용할 수 없습니다.")
            return false
        }
        if mode == .onDevice, current.processingMode == .server, transportProvider() == .direct {
            // 연결 유지 전환은 서버 송출이 된다. 앱 직접 송출을 고른 경우 서버 시간으로 차감되지 않게 새 세션을 요구한다.
            errorMessage = String(localized: "앱 직접 송출은 새 방송 준비가 필요합니다. 방송 준비를 취소한 뒤 다시 선택해 주세요.")
            return false
        }
        let generation = sessionOperationGeneration
        let requestedAnonymization = isAnonymizationEnabled
        isChangingAIProcessing = true
        defer { if generation == sessionOperationGeneration { isChangingAIProcessing = false } }
        do {
            if mode == .onDevice {
                try await videoUplink.prepareLocalProcessing()
                guard generation == sessionOperationGeneration, session?.sessionID == current.sessionID else { return false }
                // Protect all subsequent camera frames before disabling the server's AI.
                // On an ambiguous HTTP failure this path remains protected and retryable.
                videoUplink.setAIProcessingMode(.onDevice, anonymizationEnabled: true)
                session?.clientProcessingMode = .onDevice
                isAnonymizationEnabled = true
                persistAIProcessingMode(.onDevice)
            }
            let serverEnabled = mode == .server && requestedAnonymization
            let confirmed = try await api.toggleAnonymization(session: current, accessToken: accessToken, enabled: serverEnabled)
            guard generation == sessionOperationGeneration, session?.sessionID == current.sessionID else { return false }
            guard confirmed.media.anonymizationEnabled == serverEnabled else { throw YouTubeAPIError.response }
            applySessionResponse(confirmed, sessionID: current.sessionID, generation: generation)
            videoUplink.setAIProcessingMode(mode, anonymizationEnabled: requestedAnonymization)
            session?.clientProcessingMode = mode
            isAnonymizationEnabled = requestedAnonymization
            isAIProcessingUnconfirmed = false
            persistAIProcessingMode(mode)
            return true
        } catch {
            guard generation == sessionOperationGeneration, session?.sessionID == current.sessionID else { return false }
            isAIProcessingUnconfirmed = true
            errorMessage = String(localized: "AI 전환을 확인하지 못했습니다. 현재 비식별화 경로를 유지합니다. 사용할 방식을 다시 선택해 주세요.")
            return false
        }
    }

    func toggleScreenBlur(accessToken: String?) async {
        guard canToggleScreenBlur, let session else { return }
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }
        isTogglingScreenBlur = true
        let generation = sessionOperationGeneration
        defer { if generation == sessionOperationGeneration { isTogglingScreenBlur = false } }
        do {
            let response = try await api.toggleScreenBlur(
                session: session, accessToken: accessToken, enabled: !isScreenBlurEnabled
            )
            guard response.media.screenBlurEnabled != nil else { throw YouTubeAPIError.response }
            applySessionResponse(response, sessionID: session.sessionID, generation: generation)
        } catch {
            guard generation == sessionOperationGeneration, self.session?.sessionID == session.sessionID else { return }
            handle(error)
        }
    }

    /// 송출을 유지한 채 다음 프레임부터 블러 강도·영역을 바꾼다. 요청 중에 들어온 값은
    /// 마지막 것만 이어서 보낸다.
    func updateMosaicConfig(_ config: BroadcastMosaicConfig, accessToken: String?) async {
        guard canChangeMosaicConfig, let session else { return }
        guard !isChangingMosaicConfig else {
            pendingMosaicConfig = config
            return
        }
        mosaicConfigErrorMessage = nil
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            mosaicConfigErrorMessage = errorMessage
            return
        }
        isChangingMosaicConfig = true
        let generation = sessionOperationGeneration
        defer { if generation == sessionOperationGeneration { isChangingMosaicConfig = false } }
        var next: BroadcastMosaicConfig? = config
        while let request = next {
            pendingMosaicConfig = nil
            do {
                let response = try await api.updateMosaicConfig(session: session, accessToken: accessToken, config: request)
                guard response.media.mosaicConfig != nil else { throw YouTubeAPIError.response }
                guard applySessionResponse(response, sessionID: session.sessionID, generation: generation) else { return }
            } catch {
                guard generation == sessionOperationGeneration, self.session?.sessionID == session.sessionID else { return }
                pendingMosaicConfig = nil
                handle(error)
                mosaicConfigErrorMessage = errorMessage
                return
            }
            next = pendingMosaicConfig.flatMap { $0 == mosaicConfig ? nil : $0 }
        }
    }

    func toggleAnonymization(accessToken: String?) async {
        guard !isAIProcessingUnconfirmed else {
            errorMessage = String(localized: "AI 전환을 확인하지 못했습니다. 현재 비식별화 경로를 유지합니다. 사용할 방식을 다시 선택해 주세요.")
            return
        }
        clearError()
        guard !isTogglingAnonymization, !isTogglingScreenBlur, !isChangingAIProcessing, !isAIProcessingUnconfirmed else { return }
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }
        guard let session else {
            showError(.sessionRequired)
            return
        }

        if session.processingMode == .onDevice {
            isAnonymizationEnabled.toggle()
            videoUplink.setLocalAnonymizationEnabled(isAnonymizationEnabled)
            return
        }

        isTogglingAnonymization = true
        let generation = sessionOperationGeneration
        defer { if generation == sessionOperationGeneration { isTogglingAnonymization = false } }
        do {
            let response = try await api.toggleAnonymization(
                session: session,
                accessToken: accessToken,
                enabled: !isAnonymizationEnabled
            )
            applySessionResponse(response, sessionID: session.sessionID, generation: generation)
        } catch {
            guard generation == sessionOperationGeneration else { return }
            handle(error)
        }
    }
}
