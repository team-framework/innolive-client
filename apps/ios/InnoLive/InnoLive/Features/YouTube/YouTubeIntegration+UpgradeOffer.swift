import Foundation

extension YouTubeIntegration {
    var upgradeOffer: BroadcastUpgradeOffer? { responseState.details.upgradeOffer }

    var selectedUpgradeOption: BroadcastUpgradeOption? { upgradeOffer?.selectedOption }

    var upgradeSettingsProviders: [BroadcastSettingsProvider] {
        guard let targets = selectedUpgradeOption?.supportedTargets else { return [] }
        return targets.subtracting(Set(liveEditingTargets)).sorted { $0.rawValue < $1.rawValue }
    }

    // 확인 전 호출도 거절해 재시작 확인 취소 시 API를 보내지 않는다.
    @discardableResult
    func chooseUpgradeOption(mode: String, restartConfirmed: Bool = false, accessToken: String?) async -> Bool {
        guard canChangeBroadcastMode, let offer = upgradeOffer, !offer.isExpired(),
              let option = offer.options?.first(where: { $0.mode == mode }),
              let targets = option.supportedTargets, (!option.restartsBroadcast || restartConfirmed),
              let current = session, let accessToken, !accessToken.isEmpty else { return false }
        upgradeOfferError = nil
        if !option.needsSettings {
            return await changeBroadcastMode(resolution: option.resolution, targets: targets, accessToken: accessToken,
                                             upgradeOption: option)
        }
        // 같은 선택의 설정 재진입은 select를 반복하거나 보류 시간을 연장하지 않는다.
        if offer.selected == mode { confirmedUpgradeMode = mode; return true }
        let request = UUID()
        let generation = sessionOperationGeneration
        let scope = settingsScopeKey(accessToken: accessToken)
        upgradeOfferRequest = request
        isHandlingUpgradeOffer = true
        upgradeOfferExpirationTask?.cancel()
        stopPolling()
        func isCurrent() -> Bool {
            upgradeOfferRequest == request && generation == sessionOperationGeneration
                && session?.sessionID == current.sessionID && settingsScopeKey(accessToken: accessToken) == scope
                && !Task.isCancelled
        }
        defer {
            if upgradeOfferRequest == request {
                isHandlingUpgradeOffer = false
                synchronizeUpgradeOffer()
            }
            if generation == sessionOperationGeneration, session?.sessionID == current.sessionID {
                beginPolling(accessToken: accessToken)
            }
        }
        do {
            let snapshot = try await api.selectUpgradeOffer(session: current, accessToken: accessToken, mode: mode)
            guard isCurrent() else { return false }
            _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation)
            guard upgradeOffer?.selected == mode, upgradeOffer?.isExpired() == false else { return false }
            confirmedUpgradeMode = mode
            return true
        } catch {
            guard isCurrent() else { return false }
            handleUpgradeOfferError(error)
            if !isUpgradeOfferNotFound(error),
               let snapshot = try? await api.sessionStatus(session: current, accessToken: accessToken), isCurrent() {
                _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation)
            }
            return false
        }
    }

    @discardableResult
    func saveUpgradeOfferSettings(provider: BroadcastSettingsProvider, accessToken: String?) async -> Bool {
        guard let option = selectedUpgradeOption, option.needsSettings,
              !option.restartsBroadcast || confirmedUpgradeMode == option.mode,
              upgradeOffer?.isExpired() == false, upgradeSettingsProviders.contains(provider),
              let targets = option.supportedTargets else { return false }
        let request = upgradeOfferRequest
        let saved = await saveBroadcastModeSettings(provider: provider, accessToken: accessToken)
        guard saved, request == upgradeOfferRequest, selectedUpgradeOption?.mode == option.mode,
              upgradeOffer?.isExpired() == false else { return false }
        savedUpgradeProviders.insert(provider)
        guard Set(upgradeSettingsProviders).isSubset(of: savedUpgradeProviders) else { return true }
        return await changeBroadcastMode(resolution: option.resolution, targets: targets, accessToken: accessToken,
                                         upgradeOption: option, savedAddedProviders: savedUpgradeProviders)
    }

    @discardableResult
    func declineUpgradeOffer(accessToken: String?) async -> Bool {
        guard upgradeOffer != nil, !isHandlingUpgradeOffer, !isChangingBroadcastMode,
              !isSavingLiveSettings, !isChangingStreamState, !isEndingSession,
              let current = session, let accessToken, !accessToken.isEmpty else { return false }
        let request = UUID()
        let generation = sessionOperationGeneration
        let scope = settingsScopeKey(accessToken: accessToken)
        upgradeOfferRequest = request
        isHandlingUpgradeOffer = true
        upgradeOfferError = nil
        stopPolling()
        defer {
            if upgradeOfferRequest == request { isHandlingUpgradeOffer = false }
            if generation == sessionOperationGeneration, session?.sessionID == current.sessionID {
                beginPolling(accessToken: accessToken)
            }
        }
        func isCurrent() -> Bool {
            request == upgradeOfferRequest && generation == sessionOperationGeneration
                && session?.sessionID == current.sessionID && settingsScopeKey(accessToken: accessToken) == scope
                && !Task.isCancelled
        }
        do {
            let snapshot = try await api.declineUpgradeOffer(session: current, accessToken: accessToken)
            guard isCurrent() else { return false }
            _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation)
            closeUpgradeOffer()
            return true
        } catch {
            guard isCurrent() else { return false }
            handleUpgradeOfferError(error)
            return false
        }
    }

    private func handleUpgradeOfferError(_ error: Error) {
        if isUpgradeOfferNotFound(error) {
            closeUpgradeOffer()
        } else {
            upgradeOfferError = String(localized: "제안을 처리하지 못했습니다. 다시 시도해 주세요.", table: "UpgradeOffer")
        }
    }

    func isUpgradeOfferNotFound(_ error: Error) -> Bool {
        guard let apiError = error as? YouTubeAPIError, case let .api(code, _, _) = apiError else { return false }
        return code == "upgrade_offer_not_found"
    }

    func closeUpgradeOffer() {
        dismissedUpgradeOffer = true
        responseState.details.upgradeOffer = nil
        upgradeOfferExpirationTask?.cancel()
        upgradeOfferExpirationTask = nil
        upgradeOfferRequest = UUID()
        isHandlingUpgradeOffer = false
        upgradeOfferError = nil
        savedUpgradeProviders = []
        confirmedUpgradeMode = nil
    }

    func synchronizeUpgradeOffer() {
        upgradeOfferExpirationTask?.cancel()
        upgradeOfferExpirationTask = nil
        guard !dismissedUpgradeOffer else { responseState.details.upgradeOffer = nil; return }
        guard let offer = upgradeOffer else { return }
        guard let expiration = offer.expirationDate, expiration > Date() else { closeUpgradeOffer(); return }
        let generation = sessionOperationGeneration
        let sessionID = session?.sessionID
        upgradeOfferExpirationTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(expiration.timeIntervalSinceNow)) }
            catch { return }
            guard let self, !Task.isCancelled, generation == self.sessionOperationGeneration,
                  sessionID == self.session?.sessionID, self.upgradeOffer?.expiresAt == offer.expiresAt else { return }
            self.closeUpgradeOffer()
        }
    }

    func resetUpgradeOffer() {
        closeUpgradeOffer()
        dismissedUpgradeOffer = false
    }
}
