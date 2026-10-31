import Foundation
#if DEBUG
import os
#endif

extension YouTubeIntegration {
    var canChangeBroadcastMode: Bool {
        // 방송 중 방식 전환은 서버 egress를 교체하는 동작이라 앱 직접 송출 세션에는 쓸 수 없다.
        session != nil && !isDirectPublishSession && !liveEditingTargets.isEmpty && !isChangingBroadcastMode
            && !isSavingLiveSettings && !isChangingStreamState && !isEndingSession
            && !isPreparingSession && !isConnectingVideo && !isRecoveringVideoFailure
            && !isYouTubeConnectionOperationInProgress && !videoUplink.isSwitchingCamera && !isHandlingUpgradeOffer
    }

    @discardableResult
    func saveBroadcastModeSettings(provider: BroadcastSettingsProvider, accessToken: String?) async -> Bool {
        guard canChangeBroadcastMode, let current = session, let accessToken, !accessToken.isEmpty,
              isSettingsAccountConnected(provider), !liveEditingTargets.contains(provider),
              hasAcknowledgedTransmission(for: provider) else { return false }
        settingsEditor.select(provider)
        settingsEditor.showValidation()
        guard settingsEditor.validation.isEmpty else { return false }
        settingsEditor.cancelDefaults(provider: provider)
        let generation = sessionOperationGeneration
        let editorGeneration = settingsEditor.contextGeneration
        let scope = settingsScopeKey(accessToken: accessToken)
        let request = UUID()
        broadcastModeRequest = request
        broadcastModeRequestInFlight = true
        isChangingBroadcastMode = true
        broadcastModeError = nil
        stopPolling()
        func isCurrent() -> Bool {
            generation == sessionOperationGeneration && session?.sessionID == current.sessionID
                && broadcastModeRequest == request && settingsEditor.contextGeneration == editorGeneration
                && settingsScopeKey(accessToken: accessToken) == scope && !Task.isCancelled
        }
        defer {
            if broadcastModeRequest == request {
                broadcastModeRequestInFlight = false
                reconcileBroadcastModeState()
                if generation == sessionOperationGeneration, session?.sessionID == current.sessionID {
                    beginPolling(accessToken: accessToken)
                }
            }
        }
        do {
            let snapshot: YouTubeSessionResponse
            if provider == .youtube {
                snapshot = try await api.saveBroadcastSettings(session: current, accessToken: accessToken, settings: settingsEditor.youtube)
            } else {
                snapshot = try await api.saveCHZZKBroadcastSettings(session: current, accessToken: accessToken, settings: settingsEditor.chzzk)
            }
            guard isCurrent() else { return false }
            _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation, provider: provider.rawValue)
            settingsEditor.persist(provider: provider)
            return true
        } catch {
            guard isCurrent() else { return false }
            if let field = error as? BroadcastSettingsFieldError { settingsEditor.showFieldError(field) }
            broadcastModeError = String(localized: "추가할 플랫폼의 방송 정보를 저장하지 못했습니다. 입력값을 확인한 뒤 다시 시도해 주세요.", table: "BroadcastMode")
            return false
        }
    }

    // 추가 대상은 UI에서 설정을 편집한 뒤 이 메서드를 호출한다. 저장 성공 후에만 전환한다.
    @discardableResult
    func changeBroadcastMode(resolution: String, targets: Set<BroadcastSettingsProvider>, accessToken: String?,
                             upgradeOption: BroadcastUpgradeOption? = nil,
                             savedAddedProviders: Set<BroadcastSettingsProvider> = []) async -> Bool {
        guard canChangeBroadcastMode, let current = session, let accessToken, !accessToken.isEmpty,
              ["720p", "fhd"].contains(resolution), !targets.isEmpty else { return false }
        if let upgradeOption {
            guard upgradeOffer?.isExpired() == false,
                  upgradeOffer?.options?.contains(upgradeOption) == true else { return false }
        }
        if let message = planStore.snapshot?.preparationBlockMessage(.current(resolution: resolution, targetCount: targets.count), processing: planProcessingMode) {
            broadcastModeError = message
            return false
        }
        let added = targets.subtracting(Set(liveEditingTargets)).sorted { $0.rawValue < $1.rawValue }
        if let provider = added.first(where: { !hasAcknowledgedTransmission(for: $0) }) {
            broadcastModeError = provider == .youtube
                ? String(localized: "YouTube 전송 안내에 동의해 주세요.", table: "BroadcastMode")
                : String(localized: "치지직 전송 안내에 동의해 주세요.", table: "BroadcastMode")
            return false
        }
        for provider in added {
            guard isSettingsAccountConnected(provider) else {
                broadcastModeError = String(localized: "추가할 플랫폼의 계정을 연결해 주세요.", table: "BroadcastMode")
                return false
            }
            let validation = provider == .youtube
                ? settingsEditor.youtube.validation(faceSwap: usesFaceSwap) : settingsEditor.chzzk.validation
            guard validation.isEmpty else {
                settingsEditor.select(provider)
                settingsEditor.showValidation()
                broadcastModeError = String(localized: "추가할 플랫폼의 방송 정보를 입력해 주세요.", table: "BroadcastMode")
                return false
            }
        }
        let request = UUID()
        let generation = sessionOperationGeneration
        let editorGeneration = settingsEditor.contextGeneration
        let scope = settingsScopeKey(accessToken: accessToken)
        broadcastModeRequest = request
        broadcastModeRequestInFlight = true
        isChangingBroadcastMode = true
        broadcastModeError = nil
        broadcastModeFailures = []
        stopPolling()
        func isCurrent() -> Bool {
            broadcastModeRequest == request && generation == sessionOperationGeneration
                && session?.sessionID == current.sessionID && settingsEditor.contextGeneration == editorGeneration
                && settingsScopeKey(accessToken: accessToken) == scope && !Task.isCancelled
        }
        defer {
            if broadcastModeRequest == request {
                broadcastModeRequestInFlight = false
                reconcileBroadcastModeState()
                if generation == sessionOperationGeneration, session?.sessionID == current.sessionID {
                    beginPolling(accessToken: accessToken)
                }
            }
        }
        do {
            for provider in added where !savedAddedProviders.contains(provider) {
                settingsEditor.cancelDefaults(provider: provider)
                let snapshot: YouTubeSessionResponse
                if provider == .youtube {
                    snapshot = try await api.saveBroadcastSettings(session: current, accessToken: accessToken, settings: settingsEditor.youtube)
                } else {
                    snapshot = try await api.saveCHZZKBroadcastSettings(session: current, accessToken: accessToken, settings: settingsEditor.chzzk)
                }
                guard isCurrent() else { return false }
                _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation, provider: provider.rawValue)
                settingsEditor.persist(provider: provider)
            }
            guard isCurrent() else { return false }
            if let upgradeOption {
                guard upgradeOffer?.isExpired() == false,
                      upgradeOffer?.options?.contains(upgradeOption) == true else { return false }
            }
            let snapshot = try await api.changeBroadcastMode(session: current, accessToken: accessToken,
                                                             resolution: resolution, targets: targets,
                                                             accountID: added.contains(.youtube) ? selectedYouTubeAccountID : nil)
            guard isCurrent() else {
                if broadcastModeRequest == request, generation == sessionOperationGeneration,
                   session?.sessionID == current.sessionID { broadcastModeAwaitingStatus = true }
                return false
            }
            _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation)
            if upgradeOption != nil { closeUpgradeOffer() }
            return true
        } catch {
            guard isCurrent() else {
                if broadcastModeRequest == request, generation == sessionOperationGeneration,
                   session?.sessionID == current.sessionID { broadcastModeAwaitingStatus = true }
                return false
            }
            if upgradeOption != nil, isUpgradeOfferNotFound(error) {
                closeUpgradeOffer()
                return false
            }
            if let field = error as? BroadcastSettingsFieldError { settingsEditor.showFieldError(field) }
            broadcastModeError = String(localized: "송출 방식을 변경하지 못했습니다. 현재 방송 상태를 확인한 뒤 다시 시도해 주세요.", table: "BroadcastMode")
            // 응답이 유실돼도 서버에서 전환을 시작했을 수 있다. 조회할 때까지 편집을 잠근다.
            if let snapshot = try? await api.sessionStatus(session: current, accessToken: accessToken), isCurrent() {
                _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation)
            } else if isCurrent() {
                broadcastModeAwaitingStatus = true
            }
            return false
        }
    }

    func reconcileBroadcastModeState() {
        let switching = responseState.details.resolutionSwitch?.status == "switching"
        isChangingBroadcastMode = broadcastModeRequestInFlight || broadcastModeAwaitingStatus
            || switching || broadcastModeQualityTask != nil
        if let state = responseState.details.resolutionSwitch, state.status != "switching",
           state != observedModeCompletion {
            observedModeCompletion = state
            broadcastModeFailures = state.failedTargets ?? []
            if !broadcastModeFailures.isEmpty {
                let providers = broadcastModeFailures.map { $0.provider == "youtube" ? "YouTube" : "CHZZK" }.joined(separator: ", ")
                broadcastModeError = providers + String(localized: " 송출을 전환하지 못했습니다. 대상 상태를 확인해 주세요.", table: "BroadcastMode")
            }
            if state.status == "failed" || state.status == "canceled" {
                broadcastModeError = String(localized: "송출 방식 전환을 완료하지 못했습니다. 현재 방송 상태를 확인해 주세요.", table: "BroadcastMode")
            }
        }
    }

    func resetBroadcastMode() {
        broadcastModeRequest = UUID()
        broadcastModeRequestInFlight = false
        broadcastModeAwaitingStatus = false
        broadcastModeQualityRequest = UUID()
        broadcastModeQualityTask?.cancel()
        broadcastModeQualityTask = nil
        observedModeCompletion = nil
        isChangingBroadcastMode = false
        broadcastModeError = nil
        broadcastModeFailures = []
    }

    func synchronizeBroadcastModeQuality() {
        guard broadcastModeQualityTask == nil, let current = session,
              let active = videoUplink.activeVideoQuality, let resolution = broadcastResolution,
              let sessionResolution = VideoUplinkResolution(serverValue: resolution) else { return }
        let quality = active.with(resolution: sessionResolution)
        guard active != quality else { return }
        #if DEBUG
        Self.qualityLogger.notice("camera quality switch session=\(resolution, privacy: .public) from=\(active.rawValue, privacy: .public) to=\(quality.rawValue, privacy: .public)")
        #endif
        let generation = sessionOperationGeneration
        let request = UUID()
        broadcastModeQualityRequest = request
        broadcastModeQualityTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if broadcastModeQualityRequest == request {
                    broadcastModeQualityTask = nil
                    reconcileBroadcastModeState()
                }
            }
            do {
                try await videoUplink.switchVideoQuality(to: quality)
                #if DEBUG
                Self.qualityLogger.notice("camera quality switch done to=\(quality.rawValue, privacy: .public)")
                #endif
            } catch {
                #if DEBUG
                Self.qualityLogger.notice("camera quality switch failed to=\(quality.rawValue, privacy: .public) error=\(String(describing: error), privacy: .public)")
                #endif
                guard !Task.isCancelled, generation == sessionOperationGeneration,
                      session?.sessionID == current.sessionID, broadcastModeQualityRequest == request else { return }
                broadcastModeError = String(localized: "서버 해상도에 맞춰 카메라 화질을 변경하지 못했습니다. 카메라 상태를 확인해 주세요.", table: "BroadcastMode")
            }
        }
        reconcileBroadcastModeState()
    }
}
