import Foundation

extension YouTubeIntegration {
    var liveEditingTargets: [BroadcastSettingsProvider] {
        if let targets = responseState.details.targets {
            return targets.filter { $0.stream.broadcastPhaseValue == .live && $0.stream.statusValue != .stopped }
                .compactMap { BroadcastSettingsProvider(rawValue: $0.provider) }
        }
        return stream?.broadcastPhaseValue == .live && stream?.statusValue != .stopped ? [.youtube] : []
    }

    func canEditLiveBroadcast(_ provider: BroadcastSettingsProvider) -> Bool {
        session != nil && liveEditingTargets.contains(provider) && !unavailableLiveProviders.contains(provider) && !isChangingStreamState
            && !isChangingBroadcastMode && responseState.details.resolutionSwitch?.status != "switching"
            && !isEndingSession && !isHandlingUpgradeOffer
    }

    func beginLiveEditing(_ provider: BroadcastSettingsProvider, accessToken: String?) {
        guard !isSavingLiveSettings, canEditLiveBroadcast(provider) else { return }
        liveSettingsEditor.reset()
        liveSettingsEditor.select(provider)
        liveSettingsEditor.editYouTube { $0 = settingsEditor.youtube }
        liveSettingsEditor.editCHZZK { $0 = settingsEditor.chzzk }
        liveSettingsError = nil
        liveSettingsSessionID = session?.sessionID
        liveSettingsGeneration = sessionOperationGeneration
        liveSettingsScope = settingsScopeKey(accessToken: accessToken)
    }

    @discardableResult
    func saveLiveSettings(accessToken: String?) async -> Bool {
        let editor = liveSettingsEditor
        let provider = editor.provider
        guard !isSavingLiveSettings, canEditLiveBroadcast(provider), let session,
              session.sessionID == liveSettingsSessionID, sessionOperationGeneration == liveSettingsGeneration,
              settingsScopeKey(accessToken: accessToken) == liveSettingsScope,
              let accessToken, !accessToken.isEmpty else { return false }
        editor.showValidation(live: true)
        guard editor.liveValidation.isEmpty else { return false }
        let generation = sessionOperationGeneration
        let editorGeneration = editor.contextGeneration
        let revision = responseRevision
        let request = UUID()
        liveSettingsRequest = request
        isSavingLiveSettings = true
        liveSettingsError = nil
        let youtubeSettings = editor.youtube
        let chzzkSettings = editor.chzzk
        defer { if liveSettingsRequest == request { isSavingLiveSettings = false } }
        func isCurrent() -> Bool {
            generation == sessionOperationGeneration && self.session?.sessionID == session.sessionID
                && editorGeneration == editor.contextGeneration && liveSettingsRequest == request
                && settingsScopeKey(accessToken: accessToken) == liveSettingsScope && !Task.isCancelled
        }
        do {
            let snapshot = try await api.updateLiveBroadcast(session: session, accessToken: accessToken,
                provider: provider, youtube: youtubeSettings, chzzk: chzzkSettings)
            guard isCurrent(), canEditLiveBroadcast(provider) else { return false }
            // 편집 응답이 늦으면 그동안 조회한 종료·다른 대상 상태를 유지한다.
            if revision == responseRevision {
                _ = applySessionResponse(snapshot, sessionID: session.sessionID, generation: generation, provider: provider.rawValue)
            }
            guard canEditLiveBroadcast(provider) else { return false }
            if provider == .youtube {
                settingsEditor.editYouTube {
                    $0.title = youtubeSettings.title.trimmingCharacters(in: .whitespacesAndNewlines)
                    $0.description = youtubeSettings.description
                    $0.categoryID = youtubeSettings.categoryID
                }
                editor.editYouTube { $0.title = youtubeSettings.title.trimmingCharacters(in: .whitespacesAndNewlines) }
            } else {
                settingsEditor.editCHZZK { $0 = chzzkSettings; $0.title = chzzkSettings.title.trimmingCharacters(in: .whitespacesAndNewlines) }
                editor.editCHZZK { $0.title = chzzkSettings.title.trimmingCharacters(in: .whitespacesAndNewlines) }
            }
            settingsEditor.persist(provider: provider)
            return true
        } catch {
            guard isCurrent() else { return false }
            if let field = error as? BroadcastSettingsFieldError { editor.showFieldError(field) }
            else if let error = error as? YouTubeAPIError {
                if case let .api(code, _, _) = error, code == "broadcast_not_live" {
                    unavailableLiveProviders.insert(provider)
                    liveSettingsError = String(localized: "방송이 종료되어 정보를 수정할 수 없습니다.")
                    // 최신 서버 상태로 버튼을 잠근다. 입력은 편집기에 유지한다.
                    if let status = try? await api.sessionStatus(session: session, accessToken: accessToken), isCurrent() {
                        _ = applySessionResponse(status, sessionID: session.sessionID, generation: generation)
                    }
                } else {
                    liveSettingsError = String(localized: "방송 정보를 저장하지 못했습니다. 입력값을 유지했습니다. 다시 시도해 주세요.")
                }
            } else { liveSettingsError = String(localized: "방송 정보를 저장하지 못했습니다. 입력값을 유지했습니다. 다시 시도해 주세요.") }
            return false
        }
    }

    func resetLiveEditing() {
        liveSettingsRequest = UUID()
        isSavingLiveSettings = false
        liveSettingsError = nil
        liveSettingsSessionID = nil
        unavailableLiveProviders = []
        liveSettingsEditor.reset()
    }
}
