import AVFoundation
import Combine
import Foundation
import UIKit

enum QuickFaceRegistrationPhase: Equatable {
    case idle
    case preparing
    case detecting(String)
    case failed(String)
}

private enum QuickFaceRegistrationFrameSource: Equatable {
    case cameraManager
    case webRTC
}

/// Finds selectable faces in the original camera for one-tap registration on the home screen.
/// `GuestFaceRegistrar` registers the tapped face, so selection can end immediately.
/// The settings screen keeps `FaceRegistrationViewModel`.
@MainActor
final class QuickFaceRegistrationViewModel: ObservableObject {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    @Published private(set) var phase: QuickFaceRegistrationPhase = .idle
    @Published private(set) var isUsingWebRTCFrames = false
    @Published private(set) var faces: [FaceSelectionCandidate] = []
    @Published private(set) var frameSize: CGSize = .zero

    private let detector = FaceDetectionService()
    private var latestFrame: FaceSelectionFrame?
    private var activeFrameSource: QuickFaceRegistrationFrameSource?
    private var detectionGeneration: UInt = 0
    private var activeLifecycleSessionID: UUID?

    func setPreviewAspectRatio(_ ratio: CGFloat) {
        detector.setPreviewAspectRatio(ratio)
    }

    /// The frame whose faces are on screen, if `index` is a face the user may pick.
    func frameForSelection(at index: Int) -> FaceSelectionFrame? {
        guard case .detecting = phase,
              let frame = latestFrame,
              frame.faces.indices.contains(index),
              frame.faces[index].isSelectable else { return nil }
        return frame
    }

    func beginDetection(
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        activeLifecycleSessionID = lifecycleSessionID
        detectionGeneration &+= 1
        let generation = detectionGeneration
        clearFaces()
        detector.reset()
        phase = .preparing
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)

        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }

        guard cameraManager.authorizationStatus == .authorized else {
            phase = .failed(String(localized: "카메라 권한을 허용한 뒤 다시 시도해 주세요."))
            return
        }

        let detector = detector
        let orientation = UIDevice.current.orientation

        if videoUplink.canProvideFaceRegistrationFrames {
            activeFrameSource = .webRTC
            isUsingWebRTCFrames = true
            let started = videoUplink.startFaceFrameDelivery { [weak self, detector] pixelBuffer, cameraPosition in
                guard let frame = detector.detectFaces(
                    pixelBuffer: pixelBuffer,
                    cameraPosition: cameraPosition,
                    deviceOrientation: orientation
                ) else { return }
                Task { @MainActor [weak self] in
                    self?.handle(frame, lifecycleSessionID: lifecycleSessionID, generation: generation)
                }
            }
            guard started else {
                guard !Task.isCancelled,
                      isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
                activeFrameSource = nil
                isUsingWebRTCFrames = false
                phase = .failed(String(localized: "서버에 연결된 카메라 영상을 가져오지 못했습니다. 다시 시도해 주세요."))
                return
            }
            guard !Task.isCancelled,
                  isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
            phase = .detecting(String(localized: "얼굴이 화면에 보이도록 비춰 주세요."))
            return
        }

        guard !videoUplink.isActive,
              !videoUplink.isCapturingCamera,
              !videoUplink.isSwitchingCamera else {
            activeFrameSource = nil
            isUsingWebRTCFrames = false
            phase = .failed(String(localized: "서버 카메라 연결이 완료된 뒤 다시 시도해 주세요."))
            return
        }

        isUsingWebRTCFrames = false
        await cameraManager.startDefaultCamera()
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
        activeFrameSource = .cameraManager
        let started = await cameraManager.startFaceFrameDelivery { [weak self, detector] sampleBuffer, cameraPosition in
            guard let frame = detector.detectFaces(
                sampleBuffer: sampleBuffer,
                cameraPosition: cameraPosition,
                deviceOrientation: orientation
            ) else { return }
            Task { @MainActor [weak self] in
                self?.handle(frame, lifecycleSessionID: lifecycleSessionID, generation: generation)
            }
        }

        guard started else {
            guard !Task.isCancelled,
                  isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
            activeFrameSource = nil
            phase = .failed(String(localized: "카메라 영상을 준비하지 못했습니다. 다시 시도해 주세요."))
            return
        }
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
        phase = .detecting(String(localized: "얼굴이 화면에 보이도록 비춰 주세요."))
    }

    func stopDetection(
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        guard activeLifecycleSessionID == lifecycleSessionID else { return }
        detectionGeneration &+= 1
        let generation = detectionGeneration
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
        guard isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
        activeLifecycleSessionID = nil
        clearFaces()
        phase = .idle
    }

    func handleWebRTCFrameSourceUnavailable(
        lifecycleSessionID: UUID,
        videoUplink: WebRTCVideoUplink
    ) {
        guard activeLifecycleSessionID == lifecycleSessionID,
              activeFrameSource == .webRTC,
              isUsingWebRTCFrames else { return }
        detectionGeneration &+= 1
        activeFrameSource = nil
        isUsingWebRTCFrames = false
        videoUplink.stopFaceFrameDelivery()
        clearFaces()
        phase = .failed(String(localized: "서버 카메라 연결이 변경되었습니다. 다시 시도해 주세요."))
    }

    private func handle(_ frame: FaceSelectionFrame, lifecycleSessionID: UUID, generation: UInt) {
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
              case .detecting = phase else { return }
        latestFrame = frame
        faces = frame.faces
        frameSize = frame.pixelSize
        if frame.faces.contains(where: \.isSelectable) {
            phase = .detecting(String(localized: "등록할 얼굴을 탭해 주세요."))
        } else if frame.faces.isEmpty {
            phase = .detecting(String(localized: "얼굴이 화면에 보이도록 비춰 주세요."))
        } else {
            phase = .detecting(String(localized: "얼굴이 조금 더 크게 보이도록 가까이 와 주세요."))
        }
    }

    private func clearFaces() {
        latestFrame = nil
        faces = []
    }

    private func stopActiveDetection(
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        let frameSource = activeFrameSource
        activeFrameSource = nil
        isUsingWebRTCFrames = false

        switch frameSource {
        case .cameraManager:
            await cameraManager.stopFaceFrameDelivery()
        case .webRTC:
            videoUplink.stopFaceFrameDelivery()
        case nil:
            break
        }
    }

    private func isCurrent(lifecycleSessionID: UUID, generation: UInt) -> Bool {
        activeLifecycleSessionID == lifecycleSessionID && detectionGeneration == generation
    }
}
