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
    @Published private(set) var isChangingAIProcessing = false
    @Published private(set) var isAIProcessingUnconfirmed = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var helpURL: URL?
    @Published private(set) var hasAcknowledgedYouTubeTransmission = false
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

    var canChangeBroadcastMode: Bool {
        session != nil && !liveEditingTargets.isEmpty && !isChangingBroadcastMode
            && !isSavingLiveSettings && !isChangingStreamState && !isEndingSession
            && !isPreparingSession && !isConnectingVideo && !isRecoveringVideoFailure
            && !isYouTubeConnectionOperationInProgress && !videoUplink.isSwitchingCamera
    }

    @discardableResult
    func saveBroadcastModeSettings(provider: BroadcastSettingsProvider, accessToken: String?) async -> Bool {
        guard canChangeBroadcastMode, let current = session, let accessToken, !accessToken.isEmpty,
              isSettingsAccountConnected(provider), !liveEditingTargets.contains(provider),
              provider != .youtube || hasAcknowledgedYouTubeTransmission else { return false }
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
    func changeBroadcastMode(resolution: String, targets: Set<BroadcastSettingsProvider>, accessToken: String?) async -> Bool {
        guard canChangeBroadcastMode, let current = session, let accessToken, !accessToken.isEmpty,
              ["720p", "fhd"].contains(resolution), !targets.isEmpty else { return false }
        if let snapshot = planStore.snapshot, !snapshot.canPrepare(.current(resolution: resolution, targetCount: targets.count)) {
            broadcastModeError = String(localized: "현재 요금제와 남은 시간으로 선택한 송출 방식을 사용할 수 없습니다.", table: "BroadcastMode")
            return false
        }
        let added = targets.subtracting(Set(liveEditingTargets)).sorted { $0.rawValue < $1.rawValue }
        if added.contains(.youtube), !hasAcknowledgedYouTubeTransmission {
            broadcastModeError = String(localized: "YouTube 전송 안내에 동의해 주세요.", table: "BroadcastMode")
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
            for provider in added {
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
            let snapshot = try await api.changeBroadcastMode(session: current, accessToken: accessToken,
                                                             resolution: resolution, targets: targets)
            guard isCurrent() else {
                if broadcastModeRequest == request, generation == sessionOperationGeneration,
                   session?.sessionID == current.sessionID { broadcastModeAwaitingStatus = true }
                return false
            }
            _ = applySessionResponse(snapshot, sessionID: current.sessionID, generation: generation)
            return true
        } catch {
            guard isCurrent() else {
                if broadcastModeRequest == request, generation == sessionOperationGeneration,
                   session?.sessionID == current.sessionID { broadcastModeAwaitingStatus = true }
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
        }
    ) {
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
        videoUplink.onConnectionInterrupted = { [weak self] in
            self?.reconnectVideoUsingExistingSession()
        }
    }

    func acknowledgeYouTubeTransmission(_ consent: SignupConsent) -> Bool {
        guard consent.isAccepted else { return false }
        consentStore.recordYouTubeTransmission()
        hasAcknowledgedYouTubeTransmission = true
        return true
    }

    private func clearPersistedYouTubeConnection() {
        preferencesStore.removeConnection()
        consentStore.clearYouTubeTransmission()
        hasAcknowledgedYouTubeTransmission = false
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
            && !isEndingSession
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

    var canPauseYouTubeBroadcast: Bool { !isChangingBroadcastMode && statePolicy.canPauseBroadcast }

    var canResumeYouTubeBroadcast: Bool { !isChangingBroadcastMode && statePolicy.canResumeBroadcast }

    var canChangeYouTubePauseState: Bool { !isChangingBroadcastMode && statePolicy.canChangePauseState }

    var streamStatusText: String { statePolicy.streamStatusText }

    var isYouTubeConnectionOperationInProgress: Bool {
        isConnecting || isRefreshingConnection || isDisconnecting || isConnectingCHZZK || isDisconnectingCHZZK
    }

    var isYouTubeAccountChangeBlocked: Bool {
        isBroadcastSettingsLocked || isChangingBroadcastMode
            || isYouTubeConnectionOperationInProgress
    }

    var canDisconnectYouTubeAccount: Bool {
        connection != nil && !isYouTubeAccountChangeBlocked
    }

    var chzzkAccount: StreamingAccountSummary? {
        streamingAccounts.first { $0.provider == "chzzk" }
    }

    var isCHZZKAccountChangeBlocked: Bool {
        isChangingBroadcastMode || isYouTubeConnectionOperationInProgress || isPreparingSession || isChangingStreamState
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
                // Keep a server-capable session so either direction can switch in place.
                var created = try await api.createSession(accessToken: accessToken, mode: .server)
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
                guard created.aiProcessing == nil || created.aiProcessing == AIProcessingMode.server.rawValue else {
                    try? await removePreviousSession(scope: scope, accessToken: accessToken)
                    throw WebRTCVideoUplinkError.failed(String(localized: "현재 서버에서 선택한 AI 처리 방식을 사용할 수 없습니다."))
                }
                if requestedMode == .onDevice {
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
                    session: WebRTCSessionCredentials(sessionID: session.sessionID, ownerToken: session.ownerToken, processingMode: session.processingMode, localAnonymizationEnabled: isAnonymizationEnabled),
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
              !isTogglingAnonymization else { return false }
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
        if let snapshot = planStore.snapshot, !snapshot.canPrepare(preparationPlanMode) {
            errorMessage = snapshot.isAllowed(preparationPlanMode)
                ? String(localized: "이번 달 방송 시간을 모두 사용했습니다.")
                : String(localized: "현재 요금제에서 허용하지 않는 방송 방식입니다. 요금제 및 사용량을 확인해 주세요.")
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
        if let snapshot = planStore.snapshot, !snapshot.canPrepare(.current(resolution: broadcastResolution, targetCount: lastPreparationProviders.count)) {
            errorMessage = snapshot.isAllowed(.current(resolution: broadcastResolution, targetCount: lastPreparationProviders.count))
                ? String(localized: "이번 달 방송 시간을 모두 사용했습니다.")
                : String(localized: "현재 요금제에서 허용하지 않는 방송 방식입니다. 요금제 및 사용량을 확인해 주세요.")
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
        guard !isChangingBroadcastMode else { return }
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
            if !liveResponse.details.failedTargets.isEmpty {
                errorMessage = liveResponse.details.failedTargets.map { failure in
                    let title = BroadcastSettingsProvider(rawValue: failure.provider)?.title ?? failure.provider
                    return title + ": " + String(localized: "라이브 시작에 실패했습니다. 다른 플랫폼의 방송은 유지됩니다.", table: "Simulcast")
                }.joined(separator: "\n")
            }
            if hasStartedYouTubeBroadcast { liveStartedAt = liveStartedAt ?? Date() }
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

    func toggleAnonymization(accessToken: String?) async {
        guard !isAIProcessingUnconfirmed else {
            errorMessage = String(localized: "AI 전환을 확인하지 못했습니다. 현재 비식별화 경로를 유지합니다. 사용할 방식을 다시 선택해 주세요.")
            return
        }
        clearError()
        guard !isTogglingAnonymization, !isChangingAIProcessing, !isAIProcessingUnconfirmed else { return }
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
        responseState.apply(response, provider: provider, clearsWarnings: clearsWarnings)
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
        targetActionErrors = [:]
        liveStartFailures = []
        preparationFailures = [:]
        isRemainingTimeStale = false
        responseState = BroadcastSessionSnapshot()
        stream = nil
        isChangingAIProcessing = false
        isTogglingAnonymization = false
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
                            processingMode: session.processingMode, localAnonymizationEnabled: self.isAnonymizationEnabled
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
        guard !isChangingStreamState, (!isChangingBroadcastMode || action == .stop),
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
            do {
                let response = try await api.streamAction(action, session: session, accessToken: accessToken, provider: provider)
                guard isCurrentBroadcastOperation(operationGeneration) else { return }
                let normalized = action == .stop && !response.isFullSnapshot ? YouTubeSessionResponse(
                    stream: response.stream.markedStoppedByUser(), media: response.media,
                    details: response.details, isFullSnapshot: false, hasMedia: response.hasMedia
                ) : response
                guard applySessionResponse(normalized, sessionID: session.sessionID, generation: generation, provider: provider) else { return }
                backgroundPausedProviders.remove(key)
                if action == .stop { liveStartFailures.removeAll { $0.provider == key } }
            } catch {
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
