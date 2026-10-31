import Foundation

extension YouTubeIntegration {
    func connectVideo(
        accessToken: String?,
        preferredCameraID: String?,
        preferredAudioID: String?,
        preferredVideoQuality: CameraQualityPreset
    ) async -> Bool {
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return false
        }
        guard let session else {
            showError(.sessionRequired)
            return false
        }
        guard let serverURL = AuthenticationConfiguration.serverURL(path: "/") else {
            showError(.configuration)
            return false
        }

        let generation = sessionOperationGeneration
        isConnectingVideo = true
        defer { isConnectingVideo = false }
        var currentAccessToken = accessToken
        var shouldRetryAfterRefresh = true
        var terminalError: Error?

        while true {
            do {
                var iceServers: [WebRTCIceServer] = []
                if !Self.isDirectPublish(session) {
                    let configuration = try await api.webrtcSessionConfiguration(accessToken: currentAccessToken)
                    iceServers = configuration.iceServers
                    videoRecoveryPolicy = configuration.recovery
                }
                guard generation == sessionOperationGeneration else { return false }
                let refreshedAccessToken = api.currentAccessToken(fallback: currentAccessToken)
                try await videoUplink.start(
                    session: WebRTCSessionCredentials(sessionID: session.sessionID, ownerToken: session.ownerToken, processingMode: session.processingMode, localAnonymizationEnabled: isAnonymizationEnabled,
                                                      sendsMicrophoneToServer: !Self.isDirectPublish(session),
                                                      isDirectPublish: Self.isDirectPublish(session), broadcastResolution: uplinkResolution),
                    accessToken: refreshedAccessToken,
                    serverURL: serverURL,
                    iceServers: iceServers,
                    preferredCameraID: preferredCameraID,
                    preferredAudioID: preferredAudioID,
                    preferredVideoQuality: preferredVideoQuality
                )
                guard generation == sessionOperationGeneration else { return false }
                let isReady = try await waitForVideoTrack(session: session, accessToken: refreshedAccessToken)
                guard generation == sessionOperationGeneration else { return false }
                guard isReady, videoUplink.state == .connected else {
                    throw WebRTCVideoUplinkError.failed(
                        videoUplink.errorMessage ?? String(localized: "카메라 영상 연결이 끊겼습니다. 다시 시작해 주세요.")
                    )
                }
                videoConnectionConfiguration = VideoConnectionConfiguration(
                    preferredCameraID: preferredCameraID,
                    preferredAudioID: preferredAudioID,
                    preferredVideoQuality: preferredVideoQuality
                )
                return true
            } catch WebRTCVideoUplinkError.unauthorized where shouldRetryAfterRefresh {
                guard generation == sessionOperationGeneration else { return false }
                shouldRetryAfterRefresh = false
                await videoUplink.stopAndWait()
                guard generation == sessionOperationGeneration else { return false }

                guard await api.refreshAuthentication() == .refreshed else {
                    terminalError = WebRTCVideoUplinkError.unauthorized
                    break
                }
                currentAccessToken = api.currentAccessToken(fallback: currentAccessToken)
            } catch {
                terminalError = error
                break
            }
        }

        guard generation == sessionOperationGeneration else { return false }
        await videoUplink.stopAndWait()
        guard generation == sessionOperationGeneration else { return false }
        videoConnectionConfiguration = nil
        await deleteCurrentSession(accessToken: currentAccessToken)
        guard generation == sessionOperationGeneration else { return false }
        self.session = nil
        sessionScope = nil
        self.stream = nil
        self.liveStartedAt = nil
        self.videoTrack = nil
        handle(terminalError ?? WebRTCVideoUplinkError.failed(String(localized: "카메라 영상 연결을 완료하지 못했습니다.")))
        return false
    }

    func switchCamera(to cameraID: String) async -> Bool {
        clearError()
        do {
            try await videoUplink.switchCamera(to: cameraID)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? String(localized: "카메라를 전환하지 못했습니다. 다시 시도해 주세요.")
            return false
        }
    }

    func switchAudioInput(to audioInputID: String) -> Bool {
        clearError()
        do {
            try videoUplink.switchAudioInput(to: audioInputID)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? String(localized: "오디오 기기를 전환하지 못했습니다. 다시 시도해 주세요.")
            return false
        }
    }

    func switchVideoQuality(to quality: CameraQualityPreset) async -> Bool {
        guard !hasStartedYouTubeBroadcast, !isChangingBroadcastMode else { return false }
        clearError()
        do {
            try await videoUplink.switchVideoQuality(to: quality)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? String(localized: "화질을 변경하지 못했습니다. 다시 시도해 주세요.")
            return false
        }
    }

    func recoverFromVideoUplinkFailure(accessToken: String?) async {
        guard !isRecoveringVideoFailure, !isEndingSession else { return }
        cancelBroadcastCountdown()
        countdownTask?.cancel()
        countdownTask = nil
        isEndingSession = true
        defer { isEndingSession = false }
        isRecoveringVideoFailure = true
        defer { isRecoveringVideoFailure = false }

        sessionOperationGeneration &+= 1
        invalidateBroadcastOperation()
        clearBackgroundYouTubePauseState()
        if let task = sessionPreparationTask { _ = await task.value }
        let failureMessage = videoUplink.errorMessage
            ?? String(localized: "카메라 영상 연결이 끊겼습니다. 비식별화를 다시 시작해 주세요.")

        await stopAllDirectPublish()
        await videoUplink.stopAndWait()
        lifecycle.finishLocalSession(warning: String(localized: "연결이 끊겨 기기 송출을 중단했습니다. 플랫폼 종료 상태를 확인해 주세요."))
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
        errorMessage = failureMessage
        helpURL = nil
        forceReleaseBroadcastOrientationLock()

    }

    func reconnectVideoUsingExistingSession() {
        guard !isReconnectingVideo,
              reconnectTask == nil,
              let session,
              let configuration = videoConnectionConfiguration,
              let serverURL = AuthenticationConfiguration.serverURL(path: "/") else {
            return
        }

        isReconnectingVideo = true
        reconnectTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                self.isReconnectingVideo = false
                self.reconnectTask = nil
                self.videoRecoveryStatus = nil
                self.videoUplink.isRecoveringExistingSession = false
            }

            // 서버는 정책 시간 동안 세션을 복구 대기로 둔다(서버 교체 배포 중에는 새 서버가 넘겨받은
            // 세션). 그 안에서는 한 시도가 실패해도 방송을 끝내지 않고 다음 시도로 간다.
            self.videoUplink.isRecoveringExistingSession = true
            var policy = self.videoRecoveryPolicy
            let clock = ContinuousClock()
            let startedAt = clock.now
            var attempt = 1
            var shouldDelayBeforeAttempt = false
            var didRefreshAfterSignalingUnauthorized = false

            while attempt <= policy.maximumAttempts, clock.now - startedAt < policy.window {
                self.videoRecoveryStatus = VideoRecoveryStatus(
                    attempt: attempt,
                    maximumAttempts: policy.maximumAttempts
                )
                guard !Task.isCancelled else { return }
                if shouldDelayBeforeAttempt {
                    do {
                        try await Task.sleep(for: policy.retryDelay)
                    } catch {
                        return
                    }
                    guard !Task.isCancelled else { return }
                    shouldDelayBeforeAttempt = false
                }

                do {
                    let accessToken = self.api.currentAccessToken(fallback: self.videoUplink.accessToken ?? "")
                    guard !accessToken.isEmpty else {
                        throw WebRTCVideoUplinkError.unauthorized
                    }
                    var iceServers: [WebRTCIceServer] = []
                    if !Self.isDirectPublish(session) {
                        let configuration = try await self.api.webrtcSessionConfiguration(accessToken: accessToken)
                        iceServers = configuration.iceServers
                        policy = configuration.recovery
                        self.videoRecoveryPolicy = configuration.recovery
                    }
                    guard !Task.isCancelled else { return }
                    await self.videoUplink.stopAndWait()
                    guard !Task.isCancelled else { return }
                    try await self.videoUplink.start(
                        session: WebRTCSessionCredentials(
                            sessionID: session.sessionID,
                            ownerToken: session.ownerToken,
                            processingMode: session.processingMode, localAnonymizationEnabled: self.isAnonymizationEnabled,
                            sendsMicrophoneToServer: !Self.isDirectPublish(session),
                            isDirectPublish: Self.isDirectPublish(session),
                            broadcastResolution: self.uplinkResolution
                        ),
                        accessToken: self.api.currentAccessToken(fallback: accessToken),
                        serverURL: serverURL,
                        iceServers: iceServers,
                        preferredCameraID: configuration.preferredCameraID,
                        preferredAudioID: configuration.preferredAudioID,
                        preferredVideoQuality: configuration.preferredVideoQuality
                    )
                    guard !Task.isCancelled else { return }
                    guard try await self.waitForVideoTrack(session: session, accessToken: accessToken) else {
                        throw WebRTCVideoUplinkError.failed(String(localized: "카메라 영상 연결을 복구하지 못했습니다."))
                    }
                    self.videoUplink.markPublisherReady()
                    return
                } catch WebRTCVideoUplinkError.unauthorized where !didRefreshAfterSignalingUnauthorized {
                    didRefreshAfterSignalingUnauthorized = true
                    guard !Task.isCancelled else { return }
                    await self.videoUplink.stopAndWait()
                    guard !Task.isCancelled else { return }
                    switch await self.api.refreshAuthentication() {
                    case .refreshed:
                        continue
                    case .invalid:
                        return
                    case .unavailable:
                        guard !Task.isCancelled else { return }
                        self.videoUplink.markReconnectFailed(String(localized: "네트워크 연결을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요."))
                        return
                    }
                } catch {
                    guard !Task.isCancelled else { return }
                    await self.videoUplink.stopAndWait()
                    guard !Task.isCancelled else { return }
                    attempt += 1
                    shouldDelayBeforeAttempt = attempt <= policy.maximumAttempts
                }
            }

            guard !Task.isCancelled else { return }
            self.videoUplink.markReconnectFailed(String(localized: "네트워크 연결을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요."))
        }
    }
}
