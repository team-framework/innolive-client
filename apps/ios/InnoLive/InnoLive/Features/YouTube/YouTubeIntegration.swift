import Combine
import Foundation
import UIKit
#if DEBUG
import os
#endif

@MainActor
final class YouTubeIntegration: ObservableObject {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    #if DEBUG
    /// 화질 진단용이다. Console에서 `category:quality`로 세션 해상도와 카메라 화질 전환을 본다.
    static let qualityLogger = Logger(subsystem: "com.framework.innolive", category: "quality")
    #endif

    let lifecycle = BroadcastLifecycle()
    let recordingStore: RecordingSessionStore
    lazy var recordingCoordinator = BroadcastRecordingCoordinator(store: recordingStore, lifecycle: lifecycle) { [weak self] recorder in
        self?.videoUplink.setRecordingSink(recorder)
        self?.directPublisher.setRecordingSink(recorder)
    }
    private var recordingSubscription: AnyCancellable?
    private var recordingStoreSubscription: AnyCancellable?
    private var lifecycleSubscription: AnyCancellable?
    var countdownTask: Task<Void, Never>?
    let countdownSleep: @MainActor () async throws -> Void

    // 기능별 `YouTubeIntegration+*.swift` 파일이 상태를 갱신하도록 setter를 모듈 범위로 열었다.
    // 화면 코드는 이 상태를 읽기만 하고 변경은 이 타입의 메서드로 요청한다.
    @Published var connection: YouTubeConnection?
    @Published var session: YouTubeBroadcastSession?
    @Published var stream: YouTubeStreamState?
    /// 대상별 앱 직접 송출 상태다(#481). 화면이 바로 갱신되도록 송출기 상태를 옮겨 둔다.
    @Published private(set) var directPublishStates: [String: DirectPublishState] = [:]
    /// 직접 송출 중 마이크를 열지 못해 무음으로 보내고 있다.
    @Published private(set) var isDirectMicrophoneUnavailable = false
    @Published var videoTrack: YouTubeVideoTrackState?
    @Published var responseState = BroadcastSessionSnapshot()
    @Published var isFeatureAvailable = true
    @Published var isConnecting = false
    @Published var isRefreshingConnection = false
    @Published var isDisconnecting = false
    @Published var isConnectingCHZZK = false
    @Published var isDisconnectingCHZZK = false
    @Published var chzzkAccountMessage: String?
    @Published var isPreparingSession = false
    @Published var isConnectingVideo = false
    @Published var preparationStatus: BroadcastPreparationStatus?
    @Published var selectedBroadcastProviders: Set<BroadcastSettingsProvider> = [.youtube]
    @Published var lastPreparationProviders: [BroadcastSettingsProvider] = []
    @Published var preparationFailures: [BroadcastSettingsProvider: BroadcastPreparationStatus] = [:]
    /// 마지막 준비가 송출 대상 없이 비식별화만 연 경우다. 플랫폼을 고른 다음 준비는 같은 연결에 대상을 추가한다.
    @Published var isPrivacyOnlyPreparation = false

    @Published var isSwitchingToOnDevice = false
    @Published var serverPlanPreparationRejected = false
    var preparationProcessingOverride: AIProcessingMode?
    var preparationResolution: String?
    var processingSwitchID = UUID()

    @Published var lastPreparationProvider: BroadcastSettingsProvider?
    @Published var isChangingStreamState = false
    @Published var isRecoveringVideoFailure = false
    @Published var videoRecoveryStatus: VideoRecoveryStatus?
    @Published var isAnonymizationEnabled = false
    @Published var isTogglingAnonymization = false
    @Published var isScreenBlurEnabled = false
    @Published var isTogglingScreenBlur = false

    /// 서버가 확정한 블러 강도·영역이다. 새 세션은 서버 AI 기본값으로 시작한다.
    @Published var mosaicConfig = BroadcastMosaicConfig()
    @Published var isChangingMosaicConfig = false
    @Published var mosaicConfigErrorMessage: String?
    var pendingMosaicConfig: BroadcastMosaicConfig?

    @Published var isChangingAIProcessing = false
    @Published var isAIProcessingUnconfirmed = false
    @Published var errorMessage: String?
    @Published var helpURL: URL?
    @Published var hasAcknowledgedYouTubeTransmission = false
    @Published var hasAcknowledgedCHZZKTransmission = false
    @Published var broadcastSettings: YouTubeBroadcastSettings {
        didSet {
            if !suppressBroadcastSettingsPersistence {
                persistBroadcastSettings()
            }
        }
    }

    let liveSettingsEditor: BroadcastSettingsEditor
    @Published var isSavingLiveSettings = false
    @Published var liveSettingsError: String?
    var liveSettingsRequest = UUID()
    var liveSettingsSessionID: String?
    var liveSettingsGeneration: UInt = 0
    var liveSettingsScope = ""
    var unavailableLiveProviders: Set<BroadcastSettingsProvider> = []

    @Published var isChangingBroadcastMode = false
    @Published var broadcastModeError: String?
    @Published var broadcastModeFailures: [BroadcastTargetFailure] = []
    var broadcastModeRequest = UUID()
    var broadcastModeRequestInFlight = false
    var broadcastModeQualityTask: Task<Void, Never>?
    var broadcastModeQualityRequest = UUID()
    var observedModeCompletion: BroadcastResolutionSwitch?

    @Published var isHandlingUpgradeOffer = false
    @Published var upgradeOfferError: String?
    var upgradeOfferRequest = UUID()
    var upgradeOfferExpirationTask: Task<Void, Never>?
    var dismissedUpgradeOffer = false
    var savedUpgradeProviders: Set<BroadcastSettingsProvider> = []
    var confirmedUpgradeMode: String?

    var broadcastModeAwaitingStatus = false

    let settingsEditor: BroadcastSettingsEditor
    @Published var streamingAccounts: [YouTubeStreamingAccountSummary] = []

    @Published var selectedYouTubeAccountID: String?

    let videoUplink = WebRTCVideoUplink()

    let aiModeProvider: () -> AIProcessingMode
    let localModelsAvailable: () -> Bool
    let persistAIProcessingMode: (AIProcessingMode) -> Void
    let transportProvider: () -> OnDeviceTransport
    let persistTransport: (OnDeviceTransport) -> Void
    let faceAnonymizationProvider: () -> FaceAnonymizationMode
    let api: YouTubeAPI
    let sessionStore: any BroadcastSessionStoring
    var sessionScope: BroadcastSessionScope?
    var sessionPreparationTask: Task<Bool, Never>?
    var broadcastPreparationTask: Task<Bool, Never>?
    var preparationGeneration: UInt = 0
    var sessionOperationGeneration: UInt = 0
    var isEndingSession = false
    var unsavedSessions: [String: StoredBroadcastSession] = [:]
    let authorization = YouTubeAuthorization()
    let chzzkAuthorization: any CHZZKAuthorizing
    let preferencesStore: YouTubePreferencesStore
    let consentStore: ConsentAcknowledgementStore
    private let orientationLock: any BroadcastOrientationLocking
    private var suppressBroadcastSettingsPersistence = false
    var connectionOperationGeneration: UInt = 0
    var pollingTask: Task<Void, Never>?
    var pollingGeneration = 0
    var responseRevision: UInt = 0
    let pollingInterval: Duration
    // stream.started_at은 prepare에서 egress가 시작된 시각이므로 공개 방송 타이머에 사용하지 않는다.
    var liveStartedAt: Date?
    var reconnectTask: Task<Void, Never>?
    var isReconnectingVideo = false
    var videoConnectionConfiguration: VideoConnectionConfiguration?
    /// 영상 재연결 횟수·시간이다. `/webrtc/config`에서 받은 서버 정책을 쓴다.
    var videoRecoveryPolicy = WebRTCRecoveryPolicy.default
    let maximumGoLiveAttempts = 15
    var ownedOrientationLockGeneration: UInt?
    private var broadcastOperationGeneration: UInt = 0
    var backgroundPausedProviders: Set<String> = []
    @Published var liveStartFailures: [BroadcastTargetFailure] = []
    @Published var targetActionErrors: [String: String] = [:]
    var shouldResumeAfterBackgroundPause = false
    var hasObservedActiveScene = false
    var backgroundPauseTask: Task<Void, Never>?
    var backgroundPauseGeneration: UInt = 0
    let directPublisher: any DirectBroadcastPublishing
    let heartbeatInterval: Duration
    var heartbeatTask: Task<Void, Never>?
    var heartbeatGeneration: UInt = 0
    var heartbeatBuilder = DirectHeartbeatBuilder()

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
        transportProvider: @escaping () -> OnDeviceTransport = { .selected },
        persistTransport: @escaping (OnDeviceTransport) -> Void = {
            UserDefaults.standard.set($0.rawValue, forKey: OnDeviceTransport.storageKey)
        },
        faceAnonymizationProvider: @escaping () -> FaceAnonymizationMode = { .selected },
        directPublisher: (any DirectBroadcastPublishing)? = nil,
        heartbeatInterval: Duration = .seconds(15),
        recordingStore: RecordingSessionStore? = nil,
        countdownSleep: @escaping @MainActor () async throws -> Void = { try await Task.sleep(for: .seconds(1)) }
    ) {
        self.recordingStore = recordingStore ?? RecordingSessionStore()
        self.countdownSleep = countdownSleep
        self.directPublisher = directPublisher ?? DirectBroadcastPublisher()
        self.heartbeatInterval = heartbeatInterval
        self.aiModeProvider = aiModeProvider
        self.localModelsAvailable = localModelsAvailable
        self.persistAIProcessingMode = persistAIProcessingMode
        self.transportProvider = transportProvider
        self.persistTransport = persistTransport
        self.faceAnonymizationProvider = faceAnonymizationProvider
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
        recordingSubscription = recordingCoordinator.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        recordingStoreSubscription = self.recordingStore.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        lifecycleSubscription = lifecycle.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
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
        settingsEditor.faceSwapProvider = { [weak self] in self?.usesFaceSwap ?? false }
        liveSettingsEditor.faceSwapProvider = { [weak self] in self?.usesFaceSwap ?? false }
    }

    let planStore = PlanStore()
    @Published var isRemainingTimeStale = false

    var currentPlanMode: BroadcastPlanMode {
        .current(resolution: broadcastResolution, targetCount: visibleBroadcastTargets.isEmpty
                 ? selectedBroadcastProviders.count : visibleBroadcastTargets.count)
    }

    func refreshPlan(accessToken: String?) async {
        await planStore.refresh(api: api, accessToken: accessToken)
    }

    /// 실험실 표시 여부다. 확인하지 못하면 숨긴다.
    func isAdministrator(accessToken: String?) async -> Bool {
        guard let accessToken, !accessToken.isEmpty else { return false }
        return (try? await api.isAdministrator(accessToken: accessToken)) ?? false
    }

    func configureAuthentication(_ authentication: AuthSession) {
        planStore.reset()
        settingsEditor.reset()
        resetLiveEditing()
        resetBroadcastMode()
        streamingAccounts = []
        selectedYouTubeAccountID = nil
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
    var isVideoConnected: Bool {
        guard videoUplink.state == .connected else { return false }
        if isDirectPublishSession { return videoUplink.hasRemoteVideo }
        return videoTrack?.readyStateValue == .live
    }

    var streamStartedAt: Date? {
        liveStartedAt
    }

    var statePolicy: YouTubeBroadcastStatePolicy {
        YouTubeBroadcastStatePolicy(
            stream: stream,
            isChangingStreamState: isChangingStreamState || isChangingBroadcastMode,
            targets: responseState.details.targets
        )
    }

    var visibleBroadcastTargets: [BroadcastTargetState] { statePolicy.visibleTargets }
    var broadcastRemainingTime: BroadcastRemainingTime { responseState.details.remainingTime }
    var broadcastResolution: String? { responseState.details.broadcastResolution }
    /// 업링크 송신 정책의 해상도. 서버가 값을 주지 않으면 서버 기본값 720p다.
    var uplinkResolution: VideoUplinkResolution { VideoUplinkResolution(serverValue: broadcastResolution) ?? .hd720 }

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

    func reset() {
        invalidateBroadcastLifecycle()
        processingSwitchID = UUID()
        isSwitchingToOnDevice = false
        preparationProcessingOverride = nil
        preparationResolution = nil
        serverPlanPreparationRejected = false
        planStore.reset()
        settingsEditor.reset()
        resetLiveEditing()
        resetBroadcastMode()
        resetUpgradeOffer()
        streamingAccounts = []
        selectedYouTubeAccountID = nil
        preparationGeneration &+= 1
        preparationStatus = nil
        selectedBroadcastProviders = [.youtube]
        lastPreparationProvider = nil
        lastPreparationProviders = []
        isPrivacyOnlyPreparation = false
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

    func clearError() {
        errorMessage = nil
        helpURL = nil
        videoUplink.dismissError()
    }

    func handleSettingsError(_ error: Error, provider: BroadcastSettingsProvider) {
        if provider == .chzzk, let error = error as? YouTubeAPIError {
            showError(error)
            errorMessage = error.userMessage.replacingOccurrences(of: "YouTube", with: provider.title)
            helpURL = error.helpURL
        } else {
            handle(error)
        }
    }

    func handle(_ error: Error) {
        if let error = error as? YouTubeAPIError {
            if error == .featureUnavailable { isFeatureAvailable = false }
            showError(error)
            return
        }
        if let uplinkError = videoUplink.errorMessage {
            errorMessage = uplinkError
            return
        }
        // 사용자에게 보일 문구가 있는 오류는 대체 문구로 가리지 않는다.
        if case let .failed(message) = error as? WebRTCVideoUplinkError {
            errorMessage = message
            return
        }
        if let error = error as? DirectPublishError, let message = error.errorDescription {
            errorMessage = message
            return
        }
        errorMessage = String(localized: "방송 연결을 완료하지 못했습니다. 다시 시도해 주세요.")
    }

    func showError(_ error: YouTubeAPIError) {
        if case .api(let code, _, _) = error, planProcessingMode == .server,
           ["monthly_limit_exhausted", "plan_server_streaming_not_allowed"].contains(code) {
            serverPlanPreparationRejected = true
        }
        errorMessage = error.userMessage
        helpURL = error.helpURL
    }

    func persistConnection() {
        guard let connection else { return }
        preferencesStore.saveConnection(connection)
    }

    private func persistBroadcastSettings() {
        preferencesStore.saveBroadcastSettings(broadcastSettings)
    }

    @discardableResult
    func lockBroadcastOrientationIfNeeded() -> UInt? {
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

    func releaseBroadcastOrientationLock(generation: UInt) {
        guard ownedOrientationLockGeneration == generation else { return }
        ownedOrientationLockGeneration = nil
        videoUplink.clearBroadcastOrientationLock()
        orientationLock.unlock(generation: generation)
    }

    func forceReleaseBroadcastOrientationLock() {
        guard let generation = ownedOrientationLockGeneration else {
            videoUplink.clearBroadcastOrientationLock()
            return
        }
        ownedOrientationLockGeneration = nil
        videoUplink.clearBroadcastOrientationLock()
        orientationLock.unlock(generation: generation)
    }

    func beginBroadcastOperation() -> UInt {
        broadcastOperationGeneration &+= 1
        return broadcastOperationGeneration
    }

    func isCurrentBroadcastOperation(_ generation: UInt) -> Bool {
        broadcastOperationGeneration == generation
    }

    func invalidateBroadcastOperation() {
        broadcastOperationGeneration &+= 1
        isChangingStreamState = false
    }

    func beginConnectionOperation() -> UInt {
        connectionOperationGeneration &+= 1
        return connectionOperationGeneration
    }

    func isCurrentConnectionOperation(_ generation: UInt) -> Bool {
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

struct VideoConnectionConfiguration {
    let preferredCameraID: String?
    let preferredAudioID: String?
    let preferredVideoQuality: CameraQualityPreset
}
