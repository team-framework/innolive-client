import AVFoundation
import Foundation

/// 관리자 전용 로컬 녹화다. 서버 세션·시그널링·송출 없이 카메라와 온디바이스 처리를 열고 기기에만 저장한다.
extension YouTubeIntegration {
    var canOpenLocalCapture: Bool {
        AIProcessingMode.localModelsAvailable && session == nil && !isPreparingSession
            && localCaptureID == nil && !videoUplink.isActive
            && (lifecycle.phase == .idle || (lifecycle.phase == .result && !lifecycle.isPresentingEnd))
    }

    var isLocalTakeRecording: Bool { localCaptureID != nil && lifecycle.phase == .live }

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
        do {
            try await videoUplink.startLocalOnly(id, preferredCameraID: preferredCameraID,
                                                 preferredAudioID: preferredAudioID,
                                                 preferredVideoQuality: preferredVideoQuality)
        } catch {
            guard localCaptureID == id else { return }
            localCaptureID = nil
            await videoUplink.stopAndWait()
            throw error
        }
    }

    /// The device is the take's only target, so the take is confirmed as soon as the writer is armed.
    func startLocalTake() {
        guard let id = localCaptureID, videoUplink.state == .connected,
              let generation = lifecycle.beginLocalTake() else { return }
        let capture = CaptureSessionID.local(id)
        recordingCoordinator.arm(sessionID: capture)
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

    /// A take still being saved finishes in the library after the capture closes.
    func closeLocalCapture() async {
        guard localCaptureID != nil else { return }
        stopLocalTake()
        localCaptureID = nil
        await videoUplink.stopAndWait()
        lifecycle.reset()
    }
}
