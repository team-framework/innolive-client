import Combine
import Foundation
import UIKit

@MainActor
final class YouTubeIntegration: ObservableObject {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    @Published private(set) var connection: YouTubeConnection?
    @Published private(set) var session: YouTubeBroadcastSession?
    @Published private(set) var stream: YouTubeStreamState?
    /// 대상별 앱 직접 송출 상태다(#481). 화면이 바로 갱신되도록 송출기 상태를 옮겨 둔다.
    @Published private(set) var directPublishStates: [String: DirectPublishState] = [:]
    /// 직접 송출 중 마이크를 열지 못해 무음으로 보내고 있다.
    @Published private(set) var isDirectMicrophoneUnavailable = false
    @Published private(set) var videoTrack: YouTubeVideoTrackState?
    @Published private(set) var responseState = BroadcastSessionSnapshot()
    @Published private(set) var isFeatureAvailable = true
    @Published private(set) var isConnecting = false
    @Published private(set) var isRefreshingConnection = false
    @Published private(set) var isDisconnecting = false
    @Published private(set) var isConnectingCHZZK = false
    @Published private(set) var isDisconnectingCHZZK = false
    @Published private(set) var chzzkAccountMessage: String?
    @Published private(set) var isPreparingSession = false
    @Published private(set) var isConnectingVideo = false
    @Published private(set) var preparationStatus: BroadcastPreparationStatus?
    @Published var selectedBroadcastProviders: Set<BroadcastSettingsProvider> = [.youtube]
    @Published private(set) var lastPreparationProviders: [BroadcastSettingsProvider] = []
    @Published private(set) var preparationFailures: [BroadcastSettingsProvider: BroadcastPreparationStatus] = [:]
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
        guard let session else { return aiModeProvider() }
        return AIProcessingMode(rawValue: session.aiProcessing ?? "server") ?? .server
    }

    var preparationPlanMode: BroadcastPlanMode {
        let count = broadcastPreparationTask != nil ? lastPreparationProviders.count : selectedBroadcastProviders.count
        return .current(resolution: broadcastResolution, targetCount: count)
    }

    @Published private(set) var lastPreparationProvider: BroadcastSettingsProvider?
    @Published private(set) var isChangingStreamState = false
    @Published private(set) var isRecoveringVideoFailure = false
    @Published private(set) var videoRecoveryStatus: VideoRecoveryStatus?
    @Published private(set) var isAnonymizationEnabled = false
    @Published private(set) var isTogglingAnonymization = false
    @Published private(set) var isScreenBlurEnabled = false
    @Published private(set) var isTogglingScreenBlur = false

    var canToggleScreenBlur: Bool {
        session?.processingMode == .server && !isTogglingScreenBlur
            && !isChangingAIProcessing && !isTogglingAnonymization && !isAIProcessingUnconfirmed && !isEndingSession
    }
    @Published private(set) var isChangingAIProcessing = false
    @Published private(set) var isAIProcessingUnconfirmed = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var helpURL: URL?
    @Published private(set) var hasAcknowledgedYouTubeTransmission = false
    @Published private(set) var hasAcknowledgedCHZZKTransmission = false
    @Published var broadcastSettings: YouTubeBroadcastSettings {
        didSet {
            if !suppressBroadcastSettingsPersistence {
                persistBroadcastSettings()
            }
        }
    }

    let liveSettingsEditor: BroadcastSettingsEditor
    @Published private(set) var isSavingLiveSettings = false
    @Published private(set) var liveSettingsError: String?
    private var liveSettingsRequest = UUID()
    private var liveSettingsSessionID: String?
    private var liveSettingsGeneration: UInt = 0
    private var liveSettingsScope = ""
    private var unavailableLiveProviders: Set<BroadcastSettingsProvider> = []

    @Published private(set) var isChangingBroadcastMode = false
    @Published private(set) var broadcastModeError: String?
    @Published private(set) var broadcastModeFailures: [BroadcastTargetFailure] = []
    private var broadcastModeRequest = UUID()
    private var broadcastModeRequestInFlight = false
    private var broadcastModeQualityTask: Task<Void, Never>?
    private var broadcastModeQualityRequest = UUID()
    private var observedModeCompletion: BroadcastResolutionSwitch?

    @Published private(set) var isHandlingUpgradeOffer = false
    @Published private(set) var upgradeOfferError: String?
    private var upgradeOfferRequest = UUID()
    private var upgradeOfferExpirationTask: Task<Void, Never>?
    private var dismissedUpgradeOffer = false
    private var savedUpgradeProviders: Set<BroadcastSettingsProvider> = []
    private var confirmedUpgradeMode: String?

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

    private func isUpgradeOfferNotFound(_ error: Error) -> Bool {
        guard let apiError = error as? YouTubeAPIError, case let .api(code, _, _) = apiError else { return false }
        return code == "upgrade_offer_not_found"
    }

    private func closeUpgradeOffer() {
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

    private func synchronizeUpgradeOffer() {
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

    private func resetUpgradeOffer() {
        closeUpgradeOffer()
        dismissedUpgradeOffer = false
    }

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
            let validation = provider == .youtube ? settingsEditor.youtube.validation : settingsEditor.chzzk.validation
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
                                                             resolution: resolution, targets: targets)
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

    private var broadcastModeAwaitingStatus = false

    private func reconcileBroadcastModeState() {
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

    private func resetBroadcastMode() {
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

    private func synchronizeBroadcastModeQuality() {
        guard broadcastModeQualityTask == nil, let current = session,
              let active = videoUplink.activeVideoQuality, let resolution = broadcastResolution,
              ["720p", "fhd"].contains(resolution) else { return }
        let quality: CameraQualityPreset = resolution == "fhd"
            ? (active.framesPerSecond == 24 ? .fullHD24 : .fullHD30)
            : (active.framesPerSecond == 24 ? .hd24 : .hd30)
        guard active != quality else { return }
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
            } catch {
                guard !Task.isCancelled, generation == sessionOperationGeneration,
                      session?.sessionID == current.sessionID, broadcastModeQualityRequest == request else { return }
                broadcastModeError = String(localized: "서버 해상도에 맞춰 카메라 화질을 변경하지 못했습니다. 카메라 상태를 확인해 주세요.", table: "BroadcastMode")
            }
        }
        reconcileBroadcastModeState()
    }

    let settingsEditor: BroadcastSettingsEditor
    @Published private(set) var streamingAccounts: [YouTubeStreamingAccountSummary] = []

    func configureSettingsEditor(accessToken: String?) {
        let scope = accessToken.flatMap { try? api.broadcastSessionScope(accessToken: $0) }
        var channels = Dictionary(streamingAccounts.map { ($0.provider, $0.channelID) }, uniquingKeysWith: { _, last in last })
        if let connection { channels["youtube"] = connection.channel.id }
        settingsEditor.configure(scope: scope, channels: channels)
    }

    func settingsScopeKey(accessToken: String?) -> String {
        let scope = accessToken.flatMap { try? api.broadcastSessionScope(accessToken: $0) }
        return (scope?.storageKey ?? "") + ":" + streamingAccounts.map { $0.provider + ":" + $0.channelID }.sorted().joined(separator: ":")
            + ":" + (connection?.channel.id ?? "")
    }

    func isSettingsAccountConnected(_ provider: BroadcastSettingsProvider) -> Bool {
        if provider == .youtube { return connection != nil && connection?.requiresReconnection == false }
        return streamingAccounts.contains { $0.provider == provider.rawValue && !$0.reconnectRequired }
    }

    let videoUplink = WebRTCVideoUplink()

    private let aiModeProvider: () -> AIProcessingMode
    private let localModelsAvailable: () -> Bool
    private let persistAIProcessingMode: (AIProcessingMode) -> Void
    private let api: YouTubeAPI
    private let sessionStore: any BroadcastSessionStoring
    private var sessionScope: BroadcastSessionScope?
    private var sessionPreparationTask: Task<Bool, Never>?
    private var broadcastPreparationTask: Task<Bool, Never>?
    private var preparationGeneration: UInt = 0
    private var sessionOperationGeneration: UInt = 0
    private var isEndingSession = false
    private var unsavedSessions: [String: StoredBroadcastSession] = [:]
    private let authorization = YouTubeAuthorization()
    private let chzzkAuthorization: any CHZZKAuthorizing
    private let preferencesStore: YouTubePreferencesStore
    private let consentStore: ConsentAcknowledgementStore
    private let orientationLock: any BroadcastOrientationLocking
    private var suppressBroadcastSettingsPersistence = false
    private var connectionOperationGeneration: UInt = 0
    private var pollingTask: Task<Void, Never>?
    private var pollingGeneration = 0
    private var responseRevision: UInt = 0
    private let pollingInterval: Duration
    // stream.started_at은 prepare에서 egress가 시작된 시각이므로 공개 방송 타이머에 사용하지 않는다.
    private var liveStartedAt: Date?
    private var reconnectTask: Task<Void, Never>?
    private var isReconnectingVideo = false
    private var videoConnectionConfiguration: VideoConnectionConfiguration?
    private let maximumVideoReconnectAttempts = 3
    private let maximumGoLiveAttempts = 15
    private var ownedOrientationLockGeneration: UInt?
    private var broadcastOperationGeneration: UInt = 0
    private var backgroundPausedProviders: Set<String> = []
    @Published private(set) var liveStartFailures: [BroadcastTargetFailure] = []
    @Published private(set) var targetActionErrors: [String: String] = [:]
    private var shouldResumeAfterBackgroundPause = false
    private var hasObservedActiveScene = false
    private var backgroundPauseTask: Task<Void, Never>?
    private var backgroundPauseGeneration: UInt = 0
    private let directPublisher: any DirectBroadcastPublishing
    private let heartbeatInterval: Duration
    private var heartbeatTask: Task<Void, Never>?
    private var heartbeatGeneration: UInt = 0
    private var heartbeatBuilder = DirectHeartbeatBuilder()

    convenience init() {
        self.init(preferencesStore: YouTubePreferencesStore())
    }

    init(
        preferencesStore: YouTubePreferencesStore,
        api: YouTubeAPI? = nil,
        chzzkAuthorization: (any CHZZKAuthorizing)? = nil,
        orientationLock: (any BroadcastOrientationLocking)? = nil,
        consentStore: ConsentAcknowledgementStore = ConsentAcknowledgementStore(),
        sessionStore: (any BroadcastSessionStoring)? = nil,
        pollingInterval: Duration = .seconds(2),
        aiModeProvider: @escaping () -> AIProcessingMode = { .selected },
        localModelsAvailable: @escaping () -> Bool = { AIProcessingMode.localModelsAvailable },
        persistAIProcessingMode: @escaping (AIProcessingMode) -> Void = {
            UserDefaults.standard.set($0.rawValue, forKey: AIProcessingMode.storageKey)
        },
        directPublisher: (any DirectBroadcastPublishing)? = nil,
        heartbeatInterval: Duration = .seconds(15)
    ) {
        self.directPublisher = directPublisher ?? DirectBroadcastPublisher()
        self.heartbeatInterval = heartbeatInterval
        self.aiModeProvider = aiModeProvider
        self.localModelsAvailable = localModelsAvailable
        self.persistAIProcessingMode = persistAIProcessingMode
        let resolvedAPI = api ?? YouTubeAPI()
        self.api = resolvedAPI
        self.chzzkAuthorization = chzzkAuthorization ?? CHZZKAuthorization()
        settingsEditor = BroadcastSettingsEditor(api: resolvedAPI, preferences: preferencesStore)
        liveSettingsEditor = BroadcastSettingsEditor(api: resolvedAPI, preferences: preferencesStore)
        self.sessionStore = sessionStore ?? BroadcastSessionStore()
        self.pollingInterval = pollingInterval
        self.preferencesStore = preferencesStore
        self.consentStore = consentStore
        self.orientationLock = orientationLock ?? BroadcastOrientationController.shared
        broadcastSettings = preferencesStore.loadBroadcastSettings()
        connection = preferencesStore.loadConnection()
        hasAcknowledgedYouTubeTransmission = consentStore.hasAcknowledgedYouTubeTransmission
        hasAcknowledgedCHZZKTransmission = consentStore.hasAcknowledgedCHZZKTransmission
        videoUplink.onConnectionInterrupted = { [weak self] in
            self?.reconnectVideoUsingExistingSession()
        }
        videoUplink.setDirectMediaSink(self.directPublisher.mediaSink)
        self.directPublisher.onStateChange = { [weak self] in
            guard let self else { return }
            self.directPublishStates = self.directPublisher.states
            self.isDirectMicrophoneUnavailable = self.directPublisher.isMicrophoneUnavailable
            self.refreshDirectPublishOverlay()
        }
        self.directPublisher.onPermanentFailure = { [weak self] provider in
            Task { @MainActor [weak self] in await self?.handleDirectPublishFailure(provider: provider) }
        }
    }

    func hasAcknowledgedTransmission(for provider: BroadcastSettingsProvider) -> Bool {
        switch provider {
        case .youtube: hasAcknowledgedYouTubeTransmission
        case .chzzk: hasAcknowledgedCHZZKTransmission
        }
    }

    func acknowledgeTransmission(_ consent: SignupConsent, for provider: BroadcastSettingsProvider) -> Bool {
        guard consent.isAccepted else { return false }
        switch provider {
        case .youtube:
            consentStore.recordYouTubeTransmission()
            hasAcknowledgedYouTubeTransmission = true
        case .chzzk:
            consentStore.recordCHZZKTransmission()
            hasAcknowledgedCHZZKTransmission = true
        }
        return true
    }

    func acknowledgeYouTubeTransmission(_ consent: SignupConsent) -> Bool {
        acknowledgeTransmission(consent, for: .youtube)
    }

    private func clearPersistedYouTubeConnection() {
        preferencesStore.removeConnection()
        consentStore.clearYouTubeTransmission()
        hasAcknowledgedYouTubeTransmission = false
    }

    private func clearCHZZKTransmissionAcknowledgement() {
        consentStore.clearCHZZKTransmission()
        hasAcknowledgedCHZZKTransmission = false
    }

    let planStore = PlanStore()
    @Published private(set) var isRemainingTimeStale = false

    var currentPlanMode: BroadcastPlanMode {
        .current(resolution: broadcastResolution, targetCount: visibleBroadcastTargets.isEmpty
                 ? selectedBroadcastProviders.count : visibleBroadcastTargets.count)
    }

    func refreshPlan(accessToken: String?) async {
        await planStore.refresh(api: api, accessToken: accessToken)
    }

    func configureAuthentication(_ authentication: AuthSession) {
        planStore.reset()
        settingsEditor.reset()
        resetLiveEditing()
        resetBroadcastMode()
        streamingAccounts = []
        chzzkAccountMessage = nil
        sessionOperationGeneration &+= 1
        invalidateConnectionOperation()
        api.configureAuthentication(
            accessTokenProvider: { [weak authentication] in
                authentication?.currentAccessToken()
            },
            refreshSession: { [weak authentication] in
                guard let authentication else { return .invalid }
                return await authentication.refreshSession()
            },
            onInvalidRefresh: { [weak self, weak authentication] in
                self?.reset()
                authentication?.expireSession()
            }
        )
    }

    var isConnected: Bool { connection != nil }
    // 송출을 시작하기 전에는 서버의 stream.publisher_active가 항상 false다.
    // 카메라 업링크 준비 여부는 세션 media.raw_video_track으로 판단한다.
    var isVideoConnected: Bool { videoUplink.state == .connected && videoTrack?.readyStateValue == .live }

    var streamStartedAt: Date? {
        liveStartedAt
    }

    private var statePolicy: YouTubeBroadcastStatePolicy {
        YouTubeBroadcastStatePolicy(
            stream: stream,
            isChangingStreamState: isChangingStreamState || isChangingBroadcastMode,
            targets: responseState.details.targets
        )
    }

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

    private func resetLiveEditing() {
        liveSettingsRequest = UUID()
        isSavingLiveSettings = false
        liveSettingsError = nil
        liveSettingsSessionID = nil
        unavailableLiveProviders = []
        liveSettingsEditor.reset()
    }

    var visibleBroadcastTargets: [BroadcastTargetState] { statePolicy.visibleTargets }
    var broadcastRemainingTime: BroadcastRemainingTime { responseState.details.remainingTime }
    var broadcastResolution: String? { responseState.details.broadcastResolution }

    var broadcastPhase: String {
        statePolicy.broadcastPhase.rawValue
    }

    var isBroadcastSettingsLocked: Bool {
        statePolicy.isBroadcastSettingsLocked
            || preparationStatus?.isRunning == true
            || preparationStatus?.phase == .cancelling
    }

    var isYouTubeBroadcastActive: Bool { statePolicy.isBroadcastActive }

    // 시작 요청 직후에는 서버가 egress 출력 형식을 확정할 때까지 started_at이 비어 있다.
    // 이 구간은 실제 송출 전 준비 상태이므로 버튼의 방송 중 UI와 분리한다.
    var hasStartedYouTubeBroadcast: Bool { statePolicy.hasStartedBroadcast }

    var isWaitingForYouTubeBroadcastStart: Bool { statePolicy.isWaitingForBroadcastStart }

    var isYouTubeBroadcastPaused: Bool { statePolicy.isBroadcastPaused }

    var canPauseYouTubeBroadcast: Bool { !isChangingBroadcastMode && !isHandlingUpgradeOffer && statePolicy.canPauseBroadcast }

    var canResumeYouTubeBroadcast: Bool { !isChangingBroadcastMode && !isHandlingUpgradeOffer && statePolicy.canResumeBroadcast }

    var canChangeYouTubePauseState: Bool { !isChangingBroadcastMode && !isHandlingUpgradeOffer && statePolicy.canChangePauseState }

    var streamStatusText: String { statePolicy.streamStatusText }

    var isYouTubeConnectionOperationInProgress: Bool {
        isConnecting || isRefreshingConnection || isDisconnecting || isConnectingCHZZK || isDisconnectingCHZZK
    }

    var isYouTubeAccountChangeBlocked: Bool {
        isBroadcastSettingsLocked || isChangingBroadcastMode || isHandlingUpgradeOffer
            || isYouTubeConnectionOperationInProgress
    }

    var canDisconnectYouTubeAccount: Bool {
        connection != nil && !isYouTubeAccountChangeBlocked
    }

    var chzzkAccount: StreamingAccountSummary? {
        streamingAccounts.first { $0.provider == "chzzk" }
    }

    var isCHZZKAccountChangeBlocked: Bool {
        isChangingBroadcastMode || isHandlingUpgradeOffer || isYouTubeConnectionOperationInProgress || isPreparingSession || isChangingStreamState
            || isEndingSession || preparationStatus?.isRunning == true
            || preparationStatus?.phase == .cancelling
            || StreamingAccountPolicy.isInUse("chzzk", snapshot: responseState)
    }

    var canDisconnectCHZZKAccount: Bool { chzzkAccount != nil && !isCHZZKAccountChangeBlocked }

    func connectCHZZK(presenting viewController: UIViewController, accessToken: String?) async {
        guard !isCHZZKAccountChangeBlocked else { return }
        chzzkAccountMessage = nil
        guard let accessToken, !accessToken.isEmpty else {
            chzzkAccountMessage = YouTubeAPIError.unauthorized.userMessage
            return
        }
        let generation = beginConnectionOperation()
        isConnectingCHZZK = true
        defer { if isCurrentConnectionOperation(generation) { isConnectingCHZZK = false } }
        do {
            let attempt = try CHZZKAuthorizationAttempt.random()
            let configuration = try await api.chzzkConfiguration(state: attempt.state)
            guard isCurrentConnectionOperation(generation) else { return }
            let authorization = try await chzzkAuthorization.authorize(
                configuration: configuration, attempt: attempt, presenting: viewController
            )
            guard isCurrentConnectionOperation(generation), !Task.isCancelled else { return }
            _ = try attempt.remaining()
            guard authorization.state == attempt.state else { throw CHZZKAuthorizationError.stateMismatch }
            let response = try await api.connectCHZZK(authorization: authorization, accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }
            streamingAccounts.removeAll { $0.provider == "chzzk" }
            streamingAccounts.append(response.account)
            // 서버 응답을 반영한 뒤 전체 목록을 다시 읽어 YouTube도 함께 동기화한다.
            await synchronizeAccountsAfterCHZZKChange(accessToken: accessToken, generation: generation)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            chzzkAccountMessage = chzzkMessage(for: error)
        }
    }

    func disconnectCHZZKAccount(accessToken: String?) async {
        guard canDisconnectCHZZKAccount else { return }
        chzzkAccountMessage = nil
        guard let accessToken, !accessToken.isEmpty else {
            chzzkAccountMessage = YouTubeAPIError.unauthorized.userMessage
            return
        }
        let generation = beginConnectionOperation()
        isDisconnectingCHZZK = true
        defer { if isCurrentConnectionOperation(generation) { isDisconnectingCHZZK = false } }
        do {
            try await api.disconnectStreamingAccount(accessToken: accessToken, provider: "chzzk")
            guard isCurrentConnectionOperation(generation) else { return }
            streamingAccounts.removeAll { $0.provider == "chzzk" }
            await synchronizeAccountsAfterCHZZKChange(accessToken: accessToken, generation: generation)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            chzzkAccountMessage = chzzkMessage(for: error)
        }
    }

    private func synchronizeAccountsAfterCHZZKChange(accessToken: String, generation: UInt) async {
        do {
            let accounts = try await api.streamingAccounts(accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }
            applyStreamingAccounts(accounts)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            chzzkAccountMessage = chzzkMessage(for: error)
        }
    }

    private func applyStreamingAccounts(_ accounts: [StreamingAccountSummary]) {
        streamingAccounts = accounts
        if let connection = accounts.first(where: { $0.provider == "youtube" })?.youtubeConnection {
            self.connection = connection
            persistConnection()
        } else {
            connection = nil
            clearPersistedYouTubeConnection()
        }
        if !accounts.contains(where: { $0.provider == "chzzk" }) {
            clearCHZZKTransmissionAcknowledgement()
        }
    }

    private func chzzkMessage(for error: Error) -> String {
        if let error = error as? CHZZKAuthorizationError { return error.userMessage }
        if let error = error as? YouTubeAPIError {
            if error == .unauthorized { return error.userMessage }
            if case let .api(code, _, _) = error {
                switch code {
                case "unauthorized": return YouTubeAPIError.unauthorized.userMessage
                case "invalid_auth_code": return String(localized: "치지직 인가 코드가 만료되었거나 유효하지 않습니다. 다시 연결해 주세요.", table: "CHZZK")
                case "streaming_account_in_use": return String(localized: "치지직 방송을 종료한 뒤 연결을 해제해 주세요.", table: "CHZZK")
                case "chzzk_scope_missing": return String(localized: "치지직 연결에 필요한 권한을 모두 허용해 주세요.", table: "CHZZK")
                case "chzzk_channel_missing": return String(localized: "이 치지직 계정에 채널이 없습니다. 채널을 먼저 만들어 주세요.", table: "CHZZK")
                case "streaming_reconnect_required": return String(localized: "치지직 계정을 다시 연결해 주세요.", table: "CHZZK")
                default: break
                }
            }
        }
        return String(localized: "치지직 계정 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", table: "CHZZK")
    }

    func refreshAvailability() async {
        do {
            _ = try await api.configuration()
            isFeatureAvailable = true
        } catch let error as YouTubeAPIError where error == .featureUnavailable {
            isFeatureAvailable = false
        } catch {
            // 서버가 일시적으로 응답하지 않아도 버튼을 숨기지 않고, 사용자가 다시 시도할 수 있게 둔다.
        }
    }

    func refreshConnection(accessToken: String?) async {
        guard !isYouTubeConnectionOperationInProgress,
              let accessToken,
              !accessToken.isEmpty else {
            return
        }

        let generation = beginConnectionOperation()
        isRefreshingConnection = true
        defer {
            if connectionOperationGeneration == generation {
                isRefreshingConnection = false
            }
        }

        do {
            let accounts = try await api.streamingAccounts(accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }

            applyStreamingAccounts(accounts)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            // Keep the last known connection when the refresh is unavailable.
            handle(error)
        }
    }

    func connect(presenting viewController: UIViewController, accessToken: String?) async {
        guard !isYouTubeAccountChangeBlocked else { return }
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }

        let generation = beginConnectionOperation()
        isConnecting = true
        defer {
            if connectionOperationGeneration == generation {
                isConnecting = false
            }
        }
        do {
            let configuration = try await api.configuration()
            guard isCurrentConnectionOperation(generation) else { return }
            let serverAuthCode = try await authorization.authorize(
                configuration: configuration,
                presenting: viewController
            )
            guard isCurrentConnectionOperation(generation) else { return }
            let response = try await api.connect(serverAuthCode: serverAuthCode, accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }
            connection = YouTubeConnection(provider: response.provider, channel: response.channel)
            persistConnection()
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            handle(error)
        }
    }

    func disconnectYouTubeAccount(accessToken: String?) async {
        guard canDisconnectYouTubeAccount else { return }
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }

        let generation = beginConnectionOperation()
        isDisconnecting = true
        defer {
            if connectionOperationGeneration == generation {
                isDisconnecting = false
            }
        }

        do {
            try await api.disconnectStreamingAccount(accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }
            connection = nil
            streamingAccounts.removeAll { $0.provider == "youtube" }
            clearPersistedYouTubeConnection()
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            // A failed DELETE does not prove that the server removed the link.
            handle(error)
        }
    }

    func prepareSession(accessToken: String?) async -> Bool {
        guard !isEndingSession else { return false }
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
        let requestedMode = aiModeProvider()
        isPreparingSession = true
        let task = Task { [self] in
            guard sessionOperationGeneration == generation else { return false }
            do {
                let scope = try api.broadcastSessionScope(accessToken: accessToken)
                try await removePreviousSession(scope: scope, accessToken: accessToken)
                guard isCurrentSessionOperation(generation, scope: scope, accessToken: accessToken) else { return false }
                // 온디바이스 선택은 앱 직접 송출 세션으로 만든다(server #438). 서버가 처리 방식을
                // 받지 않으면 기존처럼 서버 세션에서 서버 AI를 끄고 중계 송출한다.
                var created = try await api.createSession(accessToken: accessToken, mode: requestedMode)
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
                        || (createdOnDevice && requestedMode == .onDevice) else {
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

    private func removePreviousSession(scope: BroadcastSessionScope, accessToken: String) async throws {
        guard (try api.broadcastSessionScope(accessToken: accessToken)) == scope else {
            throw YouTubeAPIError.unauthorized
        }
        let previous = try unsavedSessions[scope.storageKey] ?? sessionStore.load(scope: scope)
        guard let previous else { return }
        try await api.deleteSession(previous, accessToken: accessToken)
        try sessionStore.remove(scope: scope)
        unsavedSessions.removeValue(forKey: scope.storageKey)
    }

    private func deleteCurrentSession(accessToken: String?) async {
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
                let iceServers = try await api.webrtcConfiguration(accessToken: currentAccessToken)
                guard generation == sessionOperationGeneration else { return false }
                let refreshedAccessToken = api.currentAccessToken(fallback: currentAccessToken)
                try await videoUplink.start(
                    session: WebRTCSessionCredentials(sessionID: session.sessionID, ownerToken: session.ownerToken, processingMode: session.processingMode, localAnonymizationEnabled: isAnonymizationEnabled,
                                                      sendsMicrophoneToServer: !Self.isDirectPublish(session)),
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

    func changeAIProcessingMode(_ mode: AIProcessingMode, accessToken: String?) async -> Bool {
        guard !isYouTubeBroadcastActive, !isChangingBroadcastMode, !isChangingStreamState, !isPreparingSession,
              !isConnectingVideo, !isEndingSession, !isChangingAIProcessing,
              !isTogglingAnonymization, !isTogglingScreenBlur, !isScreenBlurEnabled else { return false }
        clearError()
        if mode == .onDevice && !localModelsAvailable() {
            errorMessage = String(localized: "이 앱에 온디바이스 모델이 포함되어 있지 않습니다.")
            return false
        }
        guard let current = session else {
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

    func endBroadcast(accessToken: String?) async {
        guard !isEndingSession else { return }
        isEndingSession = true
        defer { isEndingSession = false }
        sessionOperationGeneration &+= 1
        invalidateBroadcastOperation()
        if let task = sessionPreparationTask { _ = await task.value }
        clearBackgroundYouTubePauseState()
        if isYouTubeBroadcastActive {
            await stopYouTubeStream(accessToken: accessToken)
        }
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
    }

    func recoverFromVideoUplinkFailure(accessToken: String?) async {
        guard !isRecoveringVideoFailure, !isEndingSession else { return }
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

    func saveEditorSettings(accessToken: String) async -> Bool {
        guard !isBroadcastSettingsLocked else { return false }
        clearError()
        let provider = settingsEditor.provider
        settingsEditor.showValidation()
        guard settingsEditor.validation.isEmpty else { return false }
        guard let session else {
            settingsEditor.persist(provider: provider)
            return true
        }
        settingsEditor.cancelDefaults(provider: provider)
        let editorGeneration = settingsEditor.contextGeneration
        let generation = sessionOperationGeneration
        isChangingStreamState = true
        defer { if generation == sessionOperationGeneration { isChangingStreamState = false } }
        do {
            let snapshot: YouTubeSessionResponse
            if provider == .youtube {
                snapshot = try await api.saveBroadcastSettings(session: session, accessToken: accessToken, settings: settingsEditor.youtube)
            } else {
                snapshot = try await api.saveCHZZKBroadcastSettings(session: session, accessToken: accessToken, settings: settingsEditor.chzzk)
            }
            guard settingsEditor.contextGeneration == editorGeneration else { return false }
            guard applySessionResponse(snapshot, sessionID: session.sessionID, generation: generation, provider: provider.rawValue) else { return false }
            settingsEditor.persist(provider: provider)
            return true
        } catch {
            guard generation == sessionOperationGeneration, settingsEditor.contextGeneration == editorGeneration else { return false }
            if let field = error as? BroadcastSettingsFieldError { settingsEditor.showFieldError(field) }
            else { handleSettingsError(error, provider: provider) }
            return false
        }
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
        let validation = provider == .youtube ? settings.validation : chzzkSettings.validation
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
                provider: provider
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
        guard !isYouTubeConnectionOperationInProgress else { return false }
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
        if broadcastPreparationTask == task {
            broadcastPreparationTask = nil
        }
        return result
    }

    func cancelBroadcastPreparation(accessToken: String?) async {
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
        guard !providers.isEmpty else {
            errorMessage = String(localized: "방송할 플랫폼을 하나 이상 선택해 주세요.", table: "Simulcast")
            return false
        }
        lastPreparationProviders = providers
        lastPreparationProvider = providers.first
        for provider in providers {
            if preparationBlocker(provider: provider, accessToken: accessToken, useEditor: useEditor, permissions: permissions) != nil {
                return false
            }
        }

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
        provider: BroadcastSettingsProvider,
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
        let accountReady = provider == .youtube
            ? (connection != nil && connection?.requiresReconnection == false)
            : isSettingsAccountConnected(provider)
        guard accountReady else {
            errorMessage = String(localized: "선택한 플랫폼의 계정을 먼저 연결해 주세요.")
            return errorMessage
        }
        let validation = provider == .youtube
            ? (useEditor ? settingsEditor.youtube.validation : broadcastSettings.normalized.validation)
            : settingsEditor.chzzk.validation
        if useEditor, settingsEditor.provider == provider { settingsEditor.showValidation() }
        if let message = validation.sorted(by: { $0.key < $1.key }).first?.value {
            errorMessage = message
            return message
        }
        if let message = planStore.snapshot?.preparationBlockMessage(.current(resolution: broadcastResolution, targetCount: lastPreparationProviders.count), processing: planProcessingMode) {
            errorMessage = message
            return errorMessage
        }
        return nil
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
        guard await prepareSession(accessToken: accessToken) else {
            guard preparationGeneration == generation else { return false }
            failPreparation(.creatingSession)
            return false
        }
        guard preparationGeneration == generation else { return false }
        if useEditor, let accessToken, let session {
            for provider in providers {
                await settingsEditor.loadDefaults(session: session, accessToken: accessToken, provider: provider)
                guard preparationGeneration == generation else { return false }
                let validation = provider == .youtube ? settingsEditor.youtube.validation : settingsEditor.chzzk.validation
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

    private func notePreparationPhase(_ phase: BroadcastPreparationPhase) {
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
                fallback: String(localized: "YouTube 방송을 먼저 준비해 주세요."),
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
            }
        }
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
                errorMessage = liveResponse.details.failedTargets.map { failure in
                    let title = BroadcastSettingsProvider(rawValue: failure.provider)?.title ?? failure.provider
                    return title + ": " + String(localized: "라이브 시작에 실패했습니다. 다른 플랫폼의 방송은 유지됩니다.", table: "Simulcast")
                }.joined(separator: "\n")
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
        let legacy = responseState.details.targets == nil && (responseState.details.provider ?? "youtube") == provider.rawValue
        return YouTubeBroadcastStatePolicy(stream: target ?? (legacy ? stream : nil), isChangingStreamState: isChangingStreamState)
    }

    func handleAppMovedToBackground(accessToken: String?) async {
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
        hasObservedActiveScene = true
        shouldResumeAfterBackgroundPause = true
        if let backgroundPauseTask {
            await backgroundPauseTask.value
            if self.backgroundPauseTask == backgroundPauseTask { self.backgroundPauseTask = nil }
        }
        await resumeBackgroundTargetsIfNeeded(accessToken: accessToken)
    }

    private func resumeBackgroundTargetsIfNeeded(accessToken: String?) async {
        guard hasObservedActiveScene, shouldResumeAfterBackgroundPause, !backgroundPausedProviders.isEmpty,
              !isChangingStreamState, backgroundPauseTask == nil else { return }
        let providers = targetProviders(for: .resume).filter { backgroundPausedProviders.contains(providerKey($0)) }
        guard !providers.isEmpty else { return }
        await changeTargets(.resume, accessToken: accessToken, providers: providers)
        if hasObservedActiveScene { shouldResumeAfterBackgroundPause = !backgroundPausedProviders.isEmpty }
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

    func reset() {
        planStore.reset()
        settingsEditor.reset()
        resetLiveEditing()
        resetBroadcastMode()
        resetUpgradeOffer()
        streamingAccounts = []
        preparationGeneration &+= 1
        preparationStatus = nil
        selectedBroadcastProviders = [.youtube]
        lastPreparationProvider = nil
        lastPreparationProviders = []
        preparationFailures = [:]
        sessionOperationGeneration &+= 1
        sessionScope = nil
        invalidateConnectionOperation()
        chzzkAccountMessage = nil
        invalidateBroadcastOperation()
        clearBackgroundYouTubePauseState()
        stopPolling()
        reconnectTask?.cancel()
        reconnectTask = nil
        videoRecoveryStatus = nil
        stopDirectHeartbeat()
        for provider in directPublisher.states.keys {
            Task { @MainActor [directPublisher] in await directPublisher.stop(provider: provider) }
        }
        videoUplink.setDirectPublishBitrateLimited(false)
        videoUplink.stop()
        videoConnectionConfiguration = nil
        connection = nil
        session = nil
        clearSessionResponseState()
        liveStartedAt = nil
        videoTrack = nil
        isAnonymizationEnabled = false
        isAIProcessingUnconfirmed = false
        errorMessage = nil
        helpURL = nil
        preferencesStore.removeConnection()
        forceReleaseBroadcastOrientationLock()
    }

    func resetForAccountDeletion() {
        reset()
        clearPersistedYouTubeConnection()
        clearCHZZKTransmissionAcknowledgement()
        suppressBroadcastSettingsPersistence = true
        broadcastSettings = .defaultValue
        suppressBroadcastSettingsPersistence = false
        preferencesStore.removeAccountData()
    }

    func dismissError() {
        clearError()
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
    private func applySessionResponse(
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
        if !isYouTubeBroadcastActive && !isChangingBroadcastMode {
            liveStartedAt = nil
            if let generation = ownedOrientationLockGeneration {
                releaseBroadcastOrientationLock(generation: generation)
            }
        }
        return true
    }

    private func clearSessionResponseState() {
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
    }

    private func targetProviders(for action: YouTubeAPI.StreamAction) -> [String?] {
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

    private func beginPolling(accessToken: String) {
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

    private func stopPolling() {
        pollingGeneration &+= 1
        pollingTask?.cancel()
        pollingTask = nil
    }

    private func waitForVideoTrack(session: YouTubeBroadcastSession, accessToken: String) async throws -> Bool {
        notePreparationPhase(.confirmingVideo)
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

    private func reconnectVideoUsingExistingSession() {
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
            }

            var attempt = 1
            var shouldDelayBeforeAttempt = false
            var didRefreshAfterSignalingUnauthorized = false

            while attempt <= self.maximumVideoReconnectAttempts {
                self.videoRecoveryStatus = VideoRecoveryStatus(
                    attempt: attempt,
                    maximumAttempts: self.maximumVideoReconnectAttempts
                )
                guard !Task.isCancelled else { return }
                if shouldDelayBeforeAttempt {
                    do {
                        try await Task.sleep(for: .seconds(2))
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
                    let iceServers = try await self.api.webrtcConfiguration(accessToken: accessToken)
                    guard !Task.isCancelled else { return }
                    await self.videoUplink.stopAndWait()
                    guard !Task.isCancelled else { return }
                    try await self.videoUplink.start(
                        session: WebRTCSessionCredentials(
                            sessionID: session.sessionID,
                            ownerToken: session.ownerToken,
                            processingMode: session.processingMode, localAnonymizationEnabled: self.isAnonymizationEnabled,
                            sendsMicrophoneToServer: !Self.isDirectPublish(session)
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
                    shouldDelayBeforeAttempt = attempt <= self.maximumVideoReconnectAttempts
                }
            }

            guard !Task.isCancelled else { return }
            self.videoUplink.markReconnectFailed(String(localized: "네트워크 연결을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요."))
        }
    }

    private func clearError() {
        errorMessage = nil
        helpURL = nil
        videoUplink.dismissError()
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

    private func clearBackgroundYouTubePauseState() {
        backgroundPauseGeneration &+= 1
        backgroundPausedProviders = []
        shouldResumeAfterBackgroundPause = false
        backgroundPauseTask = nil
    }

    private func providerKey(_ provider: String?) -> String {
        provider ?? responseState.details.provider ?? "youtube"
    }

    private func changeTargets(_ action: YouTubeAPI.StreamAction, accessToken: String?, provider: BroadcastSettingsProvider?) async {
        let providers = targetProviders(for: action).filter { provider == nil || providerKey($0) == provider?.rawValue }
        await changeTargets(action, accessToken: accessToken, providers: providers)
    }

    private func changeTargets(_ action: YouTubeAPI.StreamAction, accessToken: String?, providers: [String?]) async {
        guard !isChangingStreamState, (!isChangingBroadcastMode || action == .stop), (!isHandlingUpgradeOffer || action == .stop),
              !broadcastModeRequestInFlight, backgroundPauseTask == nil else { return }
        clearError()
        guard let accessToken, !accessToken.isEmpty else { showError(.unauthorized); return }
        guard let session, !providers.isEmpty else { return }
        let operationGeneration = beginBroadcastOperation()
        let generation = sessionOperationGeneration
        isChangingStreamState = true
        defer { if isCurrentBroadcastOperation(operationGeneration) { isChangingStreamState = false } }
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

    private func handleSettingsError(_ error: Error, provider: BroadcastSettingsProvider) {
        if provider == .chzzk, let error = error as? YouTubeAPIError {
            errorMessage = error.userMessage.replacingOccurrences(of: "YouTube", with: provider.title)
            helpURL = error.helpURL
        } else {
            handle(error)
        }
    }

    private func handle(_ error: Error) {
        if let error = error as? YouTubeAPIError {
            if error == .featureUnavailable { isFeatureAvailable = false }
            showError(error)
            return
        }
        if let uplinkError = videoUplink.errorMessage {
            errorMessage = uplinkError
            return
        }
        errorMessage = String(localized: "YouTube 연결을 완료하지 못했습니다. 다시 시도해 주세요.")
    }

    private func showError(_ error: YouTubeAPIError) {
        errorMessage = error.userMessage
        helpURL = error.helpURL
    }

    private func persistConnection() {
        guard let connection else { return }
        preferencesStore.saveConnection(connection)
    }

    private func persistBroadcastSettings() {
        preferencesStore.saveBroadcastSettings(broadcastSettings)
    }

    // MARK: 앱 직접 송출 (#481)

    /// 서버가 송출하지 않고 앱이 플랫폼으로 직접 보내는 세션이다(server #438).
    var isDirectPublishSession: Bool { session.map(Self.isDirectPublish) ?? false }

    static func isDirectPublish(_ session: YouTubeBroadcastSession) -> Bool {
        session.aiProcessing == AIProcessingMode.onDevice.rawValue
    }

    /// 치지직은 RTMP 연결이 곧 공개 방송이라 서버가 라이브 전환 응답에서 주소를 준다. 나머지는 준비 응답에서 준다.
    static func receivesDirectIngestAtGoLive(_ provider: String) -> Bool {
        provider == BroadcastSettingsProvider.chzzk.rawValue
    }

    /// 주소가 없으면 송출을 시작하지 않고 실패로 처리한다. 영상 없이 남은 플랫폼 방송은 서버에서 정리한다.
    private func startDirectPublish(provider: String, endpoint: DirectIngestEndpoint?, session: YouTubeBroadcastSession,
                                    accessToken: String, generation: UInt) async -> Bool {
        let quality = videoConnectionConfiguration?.preferredVideoQuality ?? .defaultValue
        do {
            guard let endpoint else { throw DirectPublishError.missingIngest(provider: provider) }
            try await directPublisher.start(provider: provider, endpoint: endpoint, quality: quality)
            guard generation == sessionOperationGeneration, self.session?.sessionID == session.sessionID else {
                await directPublisher.stop(provider: provider)
                return false
            }
            videoUplink.setDirectPublishBitrateLimited(true)
            refreshDirectPublishOverlay()
            return true
        } catch {
            guard generation == sessionOperationGeneration, self.session?.sessionID == session.sessionID else { return false }
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? DirectPublishError.connectionFailed(provider: provider).localizedDescription
            // 영상 없이 남은 플랫폼 방송을 정리한다. 실패해도 위 오류를 유지한다.
            if let response = try? await api.streamAction(.stop, session: session, accessToken: accessToken, provider: provider) {
                let message = errorMessage
                applySessionResponse(response, sessionID: session.sessionID, generation: generation, provider: provider)
                errorMessage = message
            }
            return false
        }
    }

    private func stopAllDirectPublish() async {
        stopDirectHeartbeat()
        await directPublisher.stopAll()
        videoUplink.setDirectPublishBitrateLimited(false)
    }

    private func refreshDirectPublishOverlay() {
        guard session != nil else { return }
        responseState.applyDirectPublishStates(directPublisher.states)
        stream = responseState.stream
        if directPublisher.states.isEmpty { videoUplink.setDirectPublishBitrateLimited(false) }
    }

    /// 서버에서 방송이 끝난 대상(스튜디오 종료, 한도 종료 등)은 앱 송출도 닫는다.
    private func stopDirectPublishForEndedTargets() {
        guard let targets = responseState.details.targets else { return }
        for provider in directPublisher.states.keys {
            guard let target = targets.first(where: { $0.provider == provider }) else { continue }
            if target.stream.broadcastPhaseValue == .idle {
                Task { @MainActor [directPublisher] in await directPublisher.stop(provider: provider) }
            }
        }
    }

    private func handleDirectPublishFailure(provider: String) async {
        let accessToken = api.currentAccessToken(fallback: videoUplink.accessToken ?? "")
        guard let session, !accessToken.isEmpty else {
            await directPublisher.stop(provider: provider)
            return
        }
        let generation = sessionOperationGeneration
        let title = BroadcastSettingsProvider(rawValue: provider)?.title ?? provider
        let message = String(format: String(localized: "%@ 송출 연결이 끊겨 방송을 종료했습니다.", table: "DirectPublish"), title)
        if let response = try? await api.streamAction(.stop, session: session, accessToken: accessToken, provider: provider) {
            applySessionResponse(response, sessionID: session.sessionID, generation: generation, provider: provider)
        }
        await directPublisher.stop(provider: provider)
        guard generation == sessionOperationGeneration else { return }
        errorMessage = message
        beginPolling(accessToken: accessToken)
    }

    // MARK: 직접 송출 하트비트 (server #434, #436)

    var isSendingDirectHeartbeat: Bool { heartbeatTask != nil }

    private func beginDirectHeartbeat(accessToken: String) {
        guard heartbeatTask == nil, isDirectPublishSession else { return }
        heartbeatGeneration &+= 1
        let generation = heartbeatGeneration
        let sessionGeneration = sessionOperationGeneration
        let interval = heartbeatInterval
        heartbeatBuilder.reset()
        heartbeatTask = Task { [weak self] in
            var isFirst = true
            while !Task.isCancelled {
                if !isFirst {
                    do { try await Task.sleep(for: interval) } catch { return }
                }
                isFirst = false
                guard let self, self.heartbeatGeneration == generation,
                      self.sessionOperationGeneration == sessionGeneration else { return }
                // 실패해도 방송은 유지하고 다음 주기에 다시 보낸다. 누락분은 재전송하지 않는다.
                guard await self.sendDirectHeartbeat(generation: generation, sessionGeneration: sessionGeneration,
                                                     fallbackAccessToken: accessToken) else {
                    if self.heartbeatGeneration == generation { self.heartbeatTask = nil }
                    return
                }
            }
        }
    }

    private func stopDirectHeartbeat() {
        heartbeatGeneration &+= 1
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    /// 계속 보낼지 돌려준다. 라이브 대상이 없다는 409를 받으면 멈춘다.
    private func sendDirectHeartbeat(generation: UInt, sessionGeneration: UInt, fallbackAccessToken: String) async -> Bool {
        guard let session, isDirectPublishSession else { return false }
        let accessToken = api.currentAccessToken(fallback: fallbackAccessToken)
        guard !accessToken.isEmpty else { return true }
        var status = await directPublisher.heartbeatStatus()
        guard heartbeatGeneration == generation, sessionOperationGeneration == sessionGeneration else { return false }
        // 서버에서 라이브인데 앱 연결이 없는 대상은 끊긴 상태로 보고한다.
        let reported = Set(status.targets.map(\.provider))
        for provider in liveEditingTargets.map(\.rawValue) where !reported.contains(provider) {
            status.targets.append(.init(provider: provider, connected: false, sentBytes: nil, reconnects: 0))
        }
        guard !status.targets.isEmpty else { return false }
        let request = heartbeatBuilder.makeRequest(
            status: status,
            anonymizationEnabled: isAnonymizationEnabled,
            device: DirectHeartbeatDevice.current(),
            model: DirectHeartbeatDevice.model,
            now: Date()
        )
        do {
            let response = try await api.sendHeartbeat(session: session, accessToken: accessToken, request: request)
            guard heartbeatGeneration == generation, sessionOperationGeneration == sessionGeneration,
                  self.session?.sessionID == session.sessionID else { return false }
            await applyHeartbeatResponse(response, session: session, accessToken: accessToken)
            return true
        } catch let error as YouTubeAPIError {
            if case let .api(code, _, _) = error, let code, Self.heartbeatTerminalCodes.contains(code) {
                // 보고 작업은 곧 취소되므로 정리는 별도 작업에서 한다. 취소된 작업에서는 세션 조회가 실패한다.
                Task { @MainActor [weak self] in
                    await self?.endDirectPublishAfterHeartbeat(code: code, session: session, accessToken: accessToken,
                                                               sessionGeneration: sessionGeneration)
                }
                return false
            }
            return true
        } catch {
            return true
        }
    }

    /// 공유 계약(direct-publish-v1)에서 모든 연결을 끊어야 하는 하트비트 오류다.
    /// 409 라이브 대상 없음, 403 소유권 상실, 404 세션 삭제. 401은 요청 계층이 인증을 갱신해 다시 보낸다.
    nonisolated static let heartbeatTerminalCodes: Set<String> = ["heartbeat_not_applicable", "forbidden", "not_found"]

    private func endDirectPublishAfterHeartbeat(code: String, session: YouTubeBroadcastSession, accessToken: String,
                                                sessionGeneration: UInt) async {
        guard sessionOperationGeneration == sessionGeneration, self.session?.sessionID == session.sessionID else { return }
        let titles = directPublisher.states.keys.sorted().map { BroadcastSettingsProvider(rawValue: $0)?.title ?? $0 }
        await stopAllDirectPublish()
        guard sessionOperationGeneration == sessionGeneration else { return }
        // 409는 세션이 남아 있어 서버 상태로 화면을 맞춘다. 403·404는 세션 조회도 실패하므로 기존 폴링이 정리한다.
        if code == "heartbeat_not_applicable",
           let status = try? await api.sessionStatus(session: session, accessToken: accessToken) {
            applySessionResponse(status, sessionID: session.sessionID, generation: sessionGeneration)
        }
        guard sessionOperationGeneration == sessionGeneration, !titles.isEmpty else { return }
        errorMessage = String(format: String(localized: "서버가 %@ 방송을 종료해 송출을 멈췄습니다.", table: "DirectPublish"),
                              titles.joined(separator: ", "))
    }

    private func applyHeartbeatResponse(_ response: DirectHeartbeatResponse, session: YouTubeBroadcastSession,
                                        accessToken: String) async {
        let generation = sessionOperationGeneration
        responseState.details.notices = response.notices
        if response.remainingTime != .missing {
            responseState.details.remainingTime = response.remainingTime
            isRemainingTimeStale = false
        }
        guard !response.stopTargets.isEmpty else { return }
        // 서버가 끝낸 대상(한도 도달, 입력 없음, 플랫폼에서 종료)은 즉시 끊는다.
        // 치지직은 이 연결 종료가 방송 종료다.
        for provider in response.stopTargets {
            await directPublisher.stop(provider: provider)
        }
        let titles = response.stopTargets.map { BroadcastSettingsProvider(rawValue: $0)?.title ?? $0 }
        if let status = try? await api.sessionStatus(session: session, accessToken: accessToken) {
            applySessionResponse(status, sessionID: session.sessionID, generation: generation)
        }
        guard generation == sessionOperationGeneration else { return }
        errorMessage = String(format: String(localized: "서버가 %@ 방송을 종료해 송출을 멈췄습니다.", table: "DirectPublish"),
                              titles.joined(separator: ", "))
    }

    #if DEBUG
    func applyLiveEditingSnapshotForTesting(_ snapshot: YouTubeSessionResponse) {
        guard let session else { return }
        _ = applySessionResponse(snapshot, sessionID: session.sessionID, generation: sessionOperationGeneration)
    }

    func markServerVideoReadyForTesting() {
        videoUplink.updateState(.connected, "connected")
        videoTrack = YouTubeVideoTrackState(id: "video", kind: "video", readyState: "live")
    }

    func seedPreparedVideoSessionForTesting(
        connection: YouTubeConnection,
        session: YouTubeBroadcastSession,
        videoTrack: YouTubeVideoTrackState
    ) {
        self.connection = connection
        self.session = session
        self.stream = session.stream
        self.responseState.stream = session.stream
        self.responseState.details = session.details
        self.videoTrack = videoTrack
        videoUplink.updateState(.connected, "connected")
    }
    #endif

    @discardableResult
    private func lockBroadcastOrientationIfNeeded() -> UInt? {
        if let ownedOrientationLockGeneration {
            if let orientation = orientationLock.lockedOrientation {
                videoUplink.applyBroadcastOrientationLock(orientation)
            }
            return ownedOrientationLockGeneration
        }
        guard !orientationLock.isLocked else {
            if let orientation = orientationLock.lockedOrientation {
                videoUplink.applyBroadcastOrientationLock(orientation)
            }
            return nil
        }
        let generation = orientationLock.lockToCurrentInterfaceOrientation()
        ownedOrientationLockGeneration = generation
        if let orientation = orientationLock.lockedOrientation {
            videoUplink.applyBroadcastOrientationLock(orientation)
        }
        return generation
    }

    private func releaseBroadcastOrientationLock(generation: UInt) {
        guard ownedOrientationLockGeneration == generation else { return }
        ownedOrientationLockGeneration = nil
        videoUplink.clearBroadcastOrientationLock()
        orientationLock.unlock(generation: generation)
    }

    private func forceReleaseBroadcastOrientationLock() {
        guard let generation = ownedOrientationLockGeneration else {
            videoUplink.clearBroadcastOrientationLock()
            return
        }
        ownedOrientationLockGeneration = nil
        videoUplink.clearBroadcastOrientationLock()
        orientationLock.unlock(generation: generation)
    }

    private func beginBroadcastOperation() -> UInt {
        broadcastOperationGeneration &+= 1
        return broadcastOperationGeneration
    }

    private func isCurrentBroadcastOperation(_ generation: UInt) -> Bool {
        broadcastOperationGeneration == generation
    }

    private func invalidateBroadcastOperation() {
        broadcastOperationGeneration &+= 1
        isChangingStreamState = false
    }

    private func beginConnectionOperation() -> UInt {
        connectionOperationGeneration &+= 1
        return connectionOperationGeneration
    }

    private func isCurrentConnectionOperation(_ generation: UInt) -> Bool {
        connectionOperationGeneration == generation
    }

    private func invalidateConnectionOperation() {
        connectionOperationGeneration &+= 1
        api.invalidateAuthenticationRequests()
        isConnecting = false
        isRefreshingConnection = false
        isDisconnecting = false
        isConnectingCHZZK = false
        isDisconnectingCHZZK = false
        chzzkAuthorization.cancel()
    }
}

private struct VideoConnectionConfiguration {
    let preferredCameraID: String?
    let preferredAudioID: String?
    let preferredVideoQuality: CameraQualityPreset
}

private final class BackgroundTaskToken: @unchecked Sendable {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    var identifier = UIBackgroundTaskIdentifier.invalid
}
