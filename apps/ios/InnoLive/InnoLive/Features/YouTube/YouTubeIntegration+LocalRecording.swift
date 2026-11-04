import AVFoundation
import Foundation

extension YouTubeIntegration {
    var canOpenLocalCapture: Bool {
        AIProcessingMode.localModelsAvailable && session == nil && !isPreparingSession
            && localCaptureID == nil && !videoUplink.isActive
            && (lifecycle.phase == .idle || (lifecycle.phase == .result && !lifecycle.isPresentingEnd))
    }

    var isLocalTakeRecording: Bool { localCaptureID != nil && lifecycle.phase == .live }

    /// 관리자 로컬 모드에는 세션을 만들 서버가 없어서, 비식별화 시작을 누르면 대신 이 기기에서 녹화한다.
    var startsPrivacyOnlyLocally: Bool { operatingMode.broadcastUnavailableReason != nil }

    func startPrivacyOnlyLocally(
        preferredCameraID: String?,
        preferredAudioID: String?,
        preferredVideoQuality: CameraQualityPreset,
        handoffCamera: () async -> Void,
        restoreLocalPreview: () async -> Void
    ) async -> Bool {
        clearError()
        guard storedAdministrator(accessToken: api.currentAccessToken(fallback: "")) else {
            errorMessage = OfflineGrant.unconfirmedMessage
            return false
        }
        await handoffCamera()
        do {
            try await openLocalCapture(preferredCameraID: preferredCameraID, preferredAudioID: preferredAudioID,
                                       preferredVideoQuality: preferredVideoQuality)
            try startLocalTake()
            if isLocalTakeRecording { return true }
        } catch {
            errorMessage = error.localizedDescription
        }
        await closeLocalCapture()
        await restoreLocalPreview()
        return false
    }

    func openLocalCapture(
        preferredCameraID: String?,
        preferredAudioID: String?,
        preferredVideoQuality: CameraQualityPreset
    ) async throws {
        guard canOpenLocalCapture else {
            throw WebRTCVideoUplinkError.failed(String(localized: "방송 준비 중에는 로컬 녹화를 시작할 수 없습니다."))
        }
        let id = UUID()
        localCaptureID = id
        operatingMode.send(.capture(active: true))
        do {
            try await videoUplink.startLocalOnly(id, preferredCameraID: preferredCameraID,
                                                 preferredAudioID: preferredAudioID,
                                                 preferredVideoQuality: preferredVideoQuality)
        } catch {
            guard localCaptureID == id else { return }
            localCaptureID = nil
            operatingMode.send(.capture(active: false))
            await videoUplink.stopAndWait()
            throw error
        }
    }

    /// 이 계정에 관리자 이용 권한이 없으면 오류를 던진다. 기간이 지난 이용 권한은 오래된 권한으로 표시만 한다.
    func startLocalTake() throws {
        let now = Date.now
        let accountID = OfflineGrant.accountID(fromAccessToken: api.currentAccessToken(fallback: ""))
        guard let grant = offlineGrantStore.load(), grant.allowsLocalTake(at: now, accountID: accountID) else {
            throw WebRTCVideoUplinkError.failed(OfflineGrant.unconfirmedMessage)
        }
        offlineGrantStore.save(grant.observing(now))
        isOfflineGrantStale = grant.status(at: now, accountID: accountID) != .valid
        if let snapshot = planStore.snapshot, snapshot.usage.hasDeviceRemainingSeconds,
           snapshot.usage.deviceRemainingSeconds == 0 {
            throw WebRTCVideoUplinkError.failed(String(localized: "이번 달 온디바이스 시간을 모두 사용해 로컬 녹화를 시작할 수 없어요."))
        }
        guard modelReadiness.isReady else {
            throw WebRTCVideoUplinkError.failed(String(localized: "AI 모델을 준비하는 중입니다. 준비가 끝나면 녹화할 수 있어요."))
        }
        guard let id = localCaptureID, videoUplink.state == .connected,
              let generation = lifecycle.beginLocalTake() else { return }
        let capture = CaptureSessionID.local(id)
        recordingCoordinator.arm(sessionID: capture, source: .protected, mosaic: videoUplink.onDeviceMosaic)
        lifecycle.reconcileTargets([CaptureSessionID.localTarget], generation: generation)
        recordingCoordinator.reconcile(targets: [CaptureSessionID.localTarget], sessionID: capture)
        let audio = localRecordingAudio
        _ = localMicrophone.start { buffer, when in
            audio.snapshot()?.appendAudio(buffer, when: when)
        }
    }

    func stopLocalTake() {
        guard isLocalTakeRecording else { return }
        localMicrophone.stop()
        lifecycle.finishLocalSession(warning: nil)
        recordingCoordinator.endLocally()
    }

    /// `keepsResult`면 끝난 촬영을 지우지 않고 홈 종료 화면에 남긴다.
    func closeLocalCapture(keepsResult: Bool = false) async {
        guard localCaptureID != nil else { return }
        stopLocalTake()
        localCaptureID = nil
        operatingMode.send(.capture(active: false))
        await videoUplink.stopAndWait()
        if !keepsResult { lifecycle.reset() }
    }
}
