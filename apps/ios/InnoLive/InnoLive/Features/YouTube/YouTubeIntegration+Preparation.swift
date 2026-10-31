import Foundation

extension YouTubeIntegration {
    /// 플랫폼 계정·방송 설정 없이 세션과 영상 연결만 열어 서버 또는 온디바이스 비식별화 결과를 보여 준다.
    var isPrivacyOnlySession: Bool {
        isPrivacyOnlyPreparation && session != nil && preparationStatus == nil && !statePolicy.isBroadcastActive
            && statePolicy.broadcastPhase != .prepared
    }

    var canContinuePreparedBroadcast: Bool {
        preparationStatus?.isFailed == true && !isChangingStreamState && broadcastPreparationTask == nil
            && lastPreparationProviders.contains(where: isProviderPrepared)
    }

    @discardableResult
    func continueWithPreparedTargets() -> Bool {
        guard canContinuePreparedBroadcast else { return false }
        lastPreparationProviders = lastPreparationProviders.filter(isProviderPrepared)
        preparationStatus = nil
        clearError()
        return true
    }

    /// Billing follows the server session, including legacy sessions using local AI over server egress.
    var planProcessingMode: AIProcessingMode {
        guard let session else { return requestedSessionMode }
        return AIProcessingMode(rawValue: session.aiProcessing ?? "server") ?? .server
    }

    /// 새 세션의 처리 방식이다. 서버 송출을 고른 온디바이스는 `server` 세션에서 서버 AI만 끈다.
    /// 서버 시간 소진 뒤의 온디바이스 전환(`preparationProcessingOverride`)은 항상 앱 직접 송출이다.
    var requestedSessionMode: AIProcessingMode {
        if let preparationProcessingOverride { return preparationProcessingOverride }
        let mode = aiModeProvider()
        return mode == .onDevice && transportProvider() == .server ? .server : mode
    }

    /// 새 세션의 얼굴 비식별화 방법이다. 합성은 서버 AI에서만 쓴다. 서버 송출을 고른 온디바이스는 서버 AI를 끄므로 블러로 둔다.
    var requestedFaceAnonymization: FaceAnonymizationMode {
        (preparationProcessingOverride ?? aiModeProvider()) == .server ? faceAnonymizationProvider() : .blur
    }

    /// 다음·현재 세션이 얼굴 합성인지다. 서버가 합성 세션의 설명 앞에 안내문을 붙여 설명 한도가 줄어든다.
    /// 세션이 있는 동안에는 비식별화 방법을 바꿀 수 없어 현재 세션과 같다.
    var usesFaceSwap: Bool { requestedFaceAnonymization == .faceSwap }

    /// 송출 경로는 다음 세션부터 적용한다. 온디바이스 세션은 경로에 따라 서버 세션 종류가 달라 그대로 바꿀 수 없다.
    func changeOnDeviceTransport(_ transport: OnDeviceTransport) -> Bool {
        guard !isYouTubeBroadcastActive, !isSwitchingToOnDevice, !isPreparingSession, !isConnectingVideo,
              !isEndingSession, !isChangingAIProcessing else { return false }
        clearError()
        guard session?.processingMode != .onDevice else {
            errorMessage = String(localized: "방송 준비를 취소한 뒤 송출 경로를 변경할 수 있습니다.")
            return false
        }
        if transport == .server, planStore.snapshot?.supportsServerBroadcast == false {
            errorMessage = String(localized: "현재 요금제는 서버 송출을 지원하지 않습니다.")
            return false
        }
        persistTransport(transport)
        return true
    }

    var offersOnDevicePreparation: Bool {
        planProcessingMode == .server && !statePolicy.isBroadcastActive
            && (serverPlanPreparationRejected || planStore.snapshot?.serverUnavailable == true)
            && planStore.snapshot?.preparationBlockMessage(preparationPlanMode, processing: .onDevice) == nil
    }

    /// End the old session before preparing a new one; the caller reuses the normal consent/camera flow.
    func switchPreparationToOnDevice(accessToken: String?) async -> Bool {
        guard offersOnDevicePreparation, !isSwitchingToOnDevice, !isYouTubeConnectionOperationInProgress, !isChangingStreamState,
              !isChangingAIProcessing, !isPreparingSession, !isConnectingVideo, !isEndingSession,
              !isChangingBroadcastMode, !isTogglingAnonymization, !isTogglingScreenBlur,
              broadcastPreparationTask == nil, let accessToken, !accessToken.isEmpty else { return false }
        guard localModelsAvailable() else {
            errorMessage = String(localized: "이 앱에 온디바이스 모델이 포함되어 있지 않습니다.")
            return false
        }
        isSwitchingToOnDevice = true
        let switchID = UUID()
        processingSwitchID = switchID
        defer { if processingSwitchID == switchID { isSwitchingToOnDevice = false } }
        let hadSession = session != nil
        await cancelBroadcastPreparation(accessToken: accessToken)
        guard processingSwitchID == switchID else { return false }
        if !hadSession {
            do {
                let scope = try api.broadcastSessionScope(accessToken: accessToken)
                try await removePreviousSession(scope: scope, accessToken: accessToken)
            } catch {
                if processingSwitchID == switchID { handle(error) }
                return false
            }
        }
        guard processingSwitchID == switchID, session == nil,
              retainedSessionRecord(accessToken: accessToken) == nil else { return false }
        preparationProcessingOverride = .onDevice
        persistAIProcessingMode(.onDevice)
        serverPlanPreparationRejected = false
        lastPreparationProviders = []
        preparationFailures = [:]
        clearError()
        return true
    }

    var preparationPlanMode: BroadcastPlanMode {
        let count = broadcastPreparationTask != nil ? lastPreparationProviders.count : selectedBroadcastProviders.count
        return .current(resolution: broadcastResolution ?? preparationResolution, targetCount: count)
    }

    @discardableResult
    func prepareYouTubeStream(accessToken: String?, provider: BroadcastSettingsProvider = .youtube, useEditor: Bool = false) async -> Bool {
        guard !isAIProcessingUnconfirmed else {
            errorMessage = String(localized: "AI 전환을 확인하지 못했습니다. 현재 비식별화 경로를 유지합니다. 사용할 방식을 다시 선택해 주세요.")
            return false
        }
        clearError()
        guard !isChangingStreamState, !isChangingAIProcessing, !isAIProcessingUnconfirmed,
              !isYouTubeConnectionOperationInProgress else { return false }
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return false
        }
        guard provider == .youtube ? connection != nil : isSettingsAccountConnected(provider) else {
            errorMessage = String(localized: "선택한 플랫폼의 계정을 먼저 연결해 주세요.")
            return false
        }
        guard isVideoConnected else {
            showError(.videoNotConnected)
            return false
        }
        guard let session else {
            showError(.sessionRequired)
            return false
        }
        if let message = planStore.snapshot?.preparationBlockMessage(preparationPlanMode, processing: planProcessingMode) {
            errorMessage = message
            return false
        }
        let settings = useEditor ? settingsEditor.youtube : broadcastSettings.normalized
        let chzzkSettings = settingsEditor.chzzk
        let validation = provider == .youtube ? settings.validation(faceSwap: usesFaceSwap) : chzzkSettings.validation
        guard validation.isEmpty else {
            if useEditor { settingsEditor.showValidation() }
            errorMessage = validation.sorted { $0.key < $1.key }.first?.value
            return false
        }
        if provider == .youtube { broadcastSettings = settings }

        settingsEditor.cancelDefaults(provider: provider)
        let editorGeneration = settingsEditor.contextGeneration
        let generation = sessionOperationGeneration
        isChangingStreamState = true
        defer { if generation == sessionOperationGeneration { isChangingStreamState = false } }
        notePreparationPhase(.savingSettings)
        do {
            let savedSnapshot: YouTubeSessionResponse
            if provider == .youtube {
                savedSnapshot = try await api.saveBroadcastSettings(session: session, accessToken: accessToken, settings: settings)
            } else {
                savedSnapshot = try await api.saveCHZZKBroadcastSettings(session: session, accessToken: accessToken, settings: chzzkSettings)
            }
            guard !useEditor || settingsEditor.contextGeneration == editorGeneration else { return false }
            guard applySessionResponse(savedSnapshot, sessionID: session.sessionID, generation: generation, provider: provider.rawValue) else { return false }
            if useEditor { settingsEditor.persist(provider: provider) }

            notePreparationPhase(.preparingStream)
            let preparedSnapshot = try await api.prepareStream(
                session: session,
                accessToken: accessToken,
                provider: provider,
                accountID: provider == .youtube ? selectedYouTubeAccountID : nil
            )
            guard !useEditor || settingsEditor.contextGeneration == editorGeneration else { return false }
            guard applySessionResponse(preparedSnapshot, sessionID: session.sessionID, generation: generation, provider: provider.rawValue, clearsWarnings: true) else { return false }
            // YouTube는 라이브 전환 전에 영상이 도착해야 하므로 준비 응답의 주소로 바로 송출한다.
            if isDirectPublishSession, !Self.receivesDirectIngestAtGoLive(provider.rawValue) {
                guard await startDirectPublish(provider: provider.rawValue, endpoint: preparedSnapshot.ingestEndpoint,
                                               session: session, accessToken: accessToken, generation: generation) else { return false }
            }
            return true
        } catch {
            guard generation == sessionOperationGeneration, !useEditor || settingsEditor.contextGeneration == editorGeneration else { return false }
            if let fieldError = error as? BroadcastSettingsFieldError { settingsEditor.showFieldError(fieldError) }
            else { handleSettingsError(error, provider: provider) }
            return false
        }
    }

    @discardableResult
    func startBroadcastPreparation(
        accessToken: String?,
        provider: BroadcastSettingsProvider = .youtube,
        providers: [BroadcastSettingsProvider]? = nil,
        useEditor: Bool = true,
        permissions: BroadcastPreparationPermissions,
        preferredCameraID: String? = nil,
        preferredAudioID: String? = nil,
        preferredVideoQuality: CameraQualityPreset = .defaultValue,
        handoffCamera: @escaping () async -> Void = {},
        restoreLocalPreview: @escaping () async -> Void = {},
        videoConnector: (() async -> Bool)? = nil
    ) async -> Bool {
        if let task = broadcastPreparationTask {
            return await task.value
        }
        guard !isYouTubeConnectionOperationInProgress, !isSwitchingToOnDevice else { return false }
        cancelBroadcastCountdown()
        let lifecycleGeneration = hasStartedYouTubeBroadcast ? nil : lifecycle.beginPreparation()
        let chosen = providers ?? [provider]
        let targets = preparationStatus?.isFailed == true && !lastPreparationProviders.isEmpty ? lastPreparationProviders : chosen
        let task = Task { @MainActor in
            await self.performBroadcastPreparation(
                accessToken: accessToken,
                providers: targets,
                useEditor: useEditor,
                permissions: permissions,
                preferredCameraID: preferredCameraID,
                preferredAudioID: preferredAudioID,
                preferredVideoQuality: preferredVideoQuality,
                handoffCamera: handoffCamera,
                restoreLocalPreview: restoreLocalPreview,
                videoConnector: videoConnector
            )
        }
        broadcastPreparationTask = task
        let result = await task.value
        if let lifecycleGeneration {
            lifecycle.finishPreparation(generation: lifecycleGeneration,
                error: result ? nil : (errorMessage ?? String(localized: "방송을 준비하지 못했습니다.")))
        }
        if broadcastPreparationTask == task {
            broadcastPreparationTask = nil
        }
        return result
    }

    func cancelBroadcastPreparation(accessToken: String?) async {
        invalidateBroadcastLifecycle()
        let hasWork = preparationStatus != nil
            || broadcastPreparationTask != nil
            || session != nil
            || isVideoConnected
            || statePolicy.isBroadcastActive
        guard hasWork else { return }

        preparationGeneration &+= 1
        sessionOperationGeneration &+= 1
        invalidateBroadcastOperation()
        preparationStatus = BroadcastPreparationStatus(phase: .cancelling)
        let task = broadcastPreparationTask
        if let task {
            _ = await task.value
        }
        if broadcastPreparationTask == task {
            broadcastPreparationTask = nil
        }
        isChangingStreamState = false
        isPreparingSession = false
        isConnectingVideo = false
        await stopPreparedTargetsIfNeeded(accessToken: accessToken)
        isChangingStreamState = false
        await stopAllDirectPublish()
        await videoUplink.stopAndWait()
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
        if retainedSessionRecord(accessToken: accessToken) == nil {
            clearError()
        }
        preparationStatus = nil
    }

    private func performBroadcastPreparation(
        accessToken: String?,
        providers: [BroadcastSettingsProvider],
        useEditor: Bool,
        permissions: BroadcastPreparationPermissions,
        preferredCameraID: String?,
        preferredAudioID: String?,
        preferredVideoQuality: CameraQualityPreset,
        handoffCamera: () async -> Void,
        restoreLocalPreview: () async -> Void,
        videoConnector: (() async -> Bool)?
    ) async -> Bool {
        let generation = preparationGeneration
        guard preparationStatus?.phase != .cancelling else { return false }
        preparationResolution = planProcessingMode == .onDevice ? Self.sessionResolution(for: preferredVideoQuality) : nil
        lastPreparationProviders = providers
        lastPreparationProvider = providers.first
        // 대상이 없으면 계정·방송 설정 검사 없이 공통 조건만 확인한다.
        for provider in providers.isEmpty ? [nil] : providers.map(Optional.some) {
            if preparationBlocker(provider: provider, accessToken: accessToken, useEditor: useEditor, permissions: permissions) != nil {
                preparationResolution = nil
                return false
            }
        }
        isPrivacyOnlyPreparation = providers.isEmpty

        let canReuseConnection = session != nil && isVideoConnected
        if !canReuseConnection {
            guard await createAndConnectForPreparation(
                accessToken: accessToken,
                providers: providers,
                useEditor: useEditor,
                generation: generation,
                preferredCameraID: preferredCameraID,
                preferredAudioID: preferredAudioID,
                preferredVideoQuality: preferredVideoQuality,
                handoffCamera: handoffCamera,
                restoreLocalPreview: restoreLocalPreview,
                videoConnector: videoConnector
            ) else { return false }
        }

        guard preparationGeneration == generation else { return false }
        let prepared = await saveAndPrepareTargets(accessToken: accessToken, providers: providers, useEditor: useEditor, generation: generation)
        guard preparationGeneration == generation else { return false }
        if prepared {
            preparationStatus = nil
            clearError()
        }
        return prepared
    }

    private func preparationBlocker(
        provider: BroadcastSettingsProvider?,
        accessToken: String?,
        useEditor: Bool,
        permissions: BroadcastPreparationPermissions
    ) -> String? {
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return errorMessage
        }
        if !permissions.hasMediaTransmissionConsent {
            errorMessage = String(localized: "방송 영상·음성 전송에 동의한 뒤 방송을 준비할 수 있습니다.")
            return errorMessage
        }
        if !permissions.cameraAuthorized {
            errorMessage = String(localized: "카메라를 허용한 뒤 방송을 준비할 수 있습니다.")
            return errorMessage
        }
        if !permissions.microphoneAuthorized {
            errorMessage = String(localized: "마이크를 허용한 뒤 방송을 준비할 수 있습니다.")
            return errorMessage
        }
        if let provider {
            let accountReady = provider == .youtube
                ? (connection != nil && connection?.requiresReconnection == false)
                : isSettingsAccountConnected(provider)
            guard accountReady else {
                errorMessage = String(localized: "선택한 플랫폼의 계정을 먼저 연결해 주세요.")
                return errorMessage
            }
            let validation = provider == .youtube
                ? (useEditor ? settingsEditor.youtube : broadcastSettings.normalized).validation(faceSwap: usesFaceSwap)
                : settingsEditor.chzzk.validation
            if useEditor, settingsEditor.provider == provider { settingsEditor.showValidation() }
            if let message = validation.sorted(by: { $0.key < $1.key }).first?.value {
                errorMessage = message
                return message
            }
        }
        if let message = planStore.snapshot?.preparationBlockMessage(.current(resolution: broadcastResolution ?? preparationResolution, targetCount: lastPreparationProviders.count), processing: planProcessingMode) {
            errorMessage = message
            return errorMessage
        }
        return nil
    }

    /// 세션 생성 `broadcast_resolution` 값이다. 처리 위치와 관계없이 방송 설정의 송출 화질을 따른다.
    private static func sessionResolution(for quality: CameraQualityPreset) -> String {
        quality.resolution.serverValue
    }

    private func createAndConnectForPreparation(
        accessToken: String?,
        providers: [BroadcastSettingsProvider],
        useEditor: Bool,
        generation: UInt,
        preferredCameraID: String?,
        preferredAudioID: String?,
        preferredVideoQuality: CameraQualityPreset,
        handoffCamera: () async -> Void,
        restoreLocalPreview: () async -> Void,
        videoConnector: (() async -> Bool)?
    ) async -> Bool {
        preparationStatus = BroadcastPreparationStatus(phase: .creatingSession)
        // 서버 세션도 선택한 화질을 요청한다. 요금제 사전 검사에는 넣지 않고, FHD 거절(403)은 720p로 다시 만든다.
        guard await prepareSession(accessToken: accessToken, broadcastResolution: Self.sessionResolution(for: preferredVideoQuality)) else {
            guard preparationGeneration == generation else { return false }
            failPreparation(.creatingSession)
            return false
        }
        guard preparationGeneration == generation else { return false }
        if useEditor, let accessToken, let session {
            for provider in providers {
                await settingsEditor.loadDefaults(session: session, accessToken: accessToken, provider: provider)
                guard preparationGeneration == generation else { return false }
                let validation = provider == .youtube
                    ? settingsEditor.youtube.validation(faceSwap: usesFaceSwap) : settingsEditor.chzzk.validation
                if let message = validation.sorted(by: { $0.key < $1.key }).first?.value {
                    settingsEditor.select(provider)
                    settingsEditor.showValidation()
                    errorMessage = message
                    await discardUnconnectedSession(accessToken: accessToken)
                    guard preparationGeneration == generation else { return false }
                    failPreparation(.creatingSession)
                    return false
                }
            }
        }
        guard preparationGeneration == generation else { return false }
        notePreparationPhase(.connectingServer)
        await handoffCamera()
        guard preparationGeneration == generation else { return false }
        let connected: Bool
        if let videoConnector {
            connected = await videoConnector()
            if connected { notePreparationPhase(.confirmingVideo) }
        } else {
            connected = await connectVideo(
                accessToken: accessToken,
                preferredCameraID: preferredCameraID,
                preferredAudioID: preferredAudioID,
                preferredVideoQuality: preferredVideoQuality
            )
        }
        guard preparationGeneration == generation else { return false }
        guard connected, isVideoConnected else {
            let phase: BroadcastPreparationPhase = preparationStatus?.phase == .confirmingVideo ? .confirmingVideo : .connectingServer
            await discardUnconnectedSession(accessToken: accessToken)
            guard preparationGeneration == generation else { return false }
            await restoreLocalPreview()
            guard preparationGeneration == generation else { return false }
            failPreparation(phase)
            return false
        }
        return true
    }

    private func saveAndPrepareTargets(
        accessToken: String?,
        providers: [BroadcastSettingsProvider],
        useEditor: Bool,
        generation: UInt
    ) async -> Bool {
        for provider in providers {
            guard preparationGeneration == generation else { return false }
            preparationFailures[provider] = nil
            if isProviderPrepared(provider) { continue }
            preparationStatus = BroadcastPreparationStatus(phase: .savingSettings)
            let prepared = await prepareYouTubeStream(accessToken: accessToken, provider: provider, useEditor: useEditor)
            guard preparationGeneration == generation else { return false }
            if !prepared || !isProviderPrepared(provider) {
                let phase: BroadcastPreparationPhase = preparationStatus?.phase == .savingSettings ? .savingSettings : .preparingStream
                let message = provider.title + ": " + (errorMessage ?? phase.failureMessage)
                preparationFailures[provider] = BroadcastPreparationStatus(phase: phase, failedPhase: phase, message: message)
            }
        }
        guard preparationGeneration == generation else { return false }
        let failures = providers.compactMap { preparationFailures[$0] }
        if let first = failures.first {
            errorMessage = failures.compactMap(\.message).joined(separator: "\n")
            failPreparation(first.phase)
            return false
        }
        return providers.allSatisfy(isProviderPrepared)
    }

    private func isProviderPrepared(_ provider: BroadcastSettingsProvider) -> Bool {
        if let target = responseState.details.targets?.first(where: { $0.provider == provider.rawValue }) {
            guard target.stream.statusValue != .stopped else { return false }
            switch target.stream.broadcastPhaseValue {
            case .prepared, .live, .goingLive: return true
            default: return false
            }
        }
        guard responseState.details.targets == nil else { return false }
        switch statePolicy.broadcastPhase {
        case .prepared, .live, .goingLive: return provider.rawValue == (responseState.details.provider ?? "youtube")
        default: return false
        }
    }

    private func discardUnconnectedSession(accessToken: String?) async {
        stopPolling()
        await stopAllDirectPublish()
        await videoUplink.stopAndWait()
        await deleteCurrentSession(accessToken: accessToken)
        session = nil
        sessionScope = nil
        clearSessionResponseState()
        videoTrack = nil
        videoConnectionConfiguration = nil
    }

    private func stopPreparedTargetsIfNeeded(accessToken: String?) async {
        guard session != nil else { return }
        guard statePolicy.isBroadcastActive || statePolicy.broadcastPhase == .prepared else { return }
        await stopYouTubeStream(accessToken: accessToken)
    }

    private func retainedSessionRecord(accessToken: String?) -> StoredBroadcastSession? {
        guard let accessToken, let scope = try? api.broadcastSessionScope(accessToken: accessToken) else { return nil }
        if let unsaved = unsavedSessions[scope.storageKey] { return unsaved }
        return try? sessionStore.load(scope: scope)
    }

    func notePreparationPhase(_ phase: BroadcastPreparationPhase) {
        guard var status = preparationStatus, !status.isFailed, status.phase != .cancelling else { return }
        status.phase = phase
        status.message = nil
        preparationStatus = status
    }

    private func failPreparation(_ phase: BroadcastPreparationPhase) {
        guard preparationStatus?.phase != .cancelling else { return }
        if errorMessage == nil { errorMessage = phase.failureMessage }
        preparationStatus = BroadcastPreparationStatus(phase: phase, failedPhase: phase, message: errorMessage)
    }
}
