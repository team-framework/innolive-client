import AVFoundation
import Foundation

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

    func startLocalTake() {
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

    func closeLocalCapture() async {
        guard localCaptureID != nil else { return }
        stopLocalTake()
        localCaptureID = nil
        await videoUplink.stopAndWait()
        lifecycle.reset()
    }
}
