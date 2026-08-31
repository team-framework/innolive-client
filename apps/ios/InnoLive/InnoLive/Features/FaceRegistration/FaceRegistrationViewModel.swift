import AVFoundation
import Combine
import Foundation
import UIKit

enum FaceRegistrationPhase: Equatable {
    case idle
    case preparing
    case detecting(String)
    case registering
    case success
    case failed(String)
}

private enum FaceRegistrationFrameSource: Equatable {
    case cameraManager
    case webRTC
}

@MainActor
final class FaceRegistrationViewModel: ObservableObject {
    @Published private(set) var status: ReferenceFaceStatus?
    @Published private(set) var isLoadingStatus = false
    @Published private(set) var isDeleting = false
    @Published private(set) var statusErrorMessage: String?
    @Published private(set) var phase: FaceRegistrationPhase = .idle
    @Published private(set) var isUsingWebRTCFrames = false

    private let api: ReferenceFaceAPI
    private let detector = FaceDetectionService()
    private var stableDetectionCount = 0
    private var activeFrameSource: FaceRegistrationFrameSource?
    private var detectionGeneration: UInt = 0

    init(authentication: AuthSession) {
        api = ReferenceFaceAPI(authentication: authentication)
    }

    func loadStatus() async {
        guard !isLoadingStatus else { return }
        isLoadingStatus = true
        statusErrorMessage = nil
        defer { isLoadingStatus = false }
        do {
            status = try await api.status()
        } catch {
            statusErrorMessage = message(for: error)
        }
    }

    func beginDetection(
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        detectionGeneration &+= 1
        let generation = detectionGeneration
        stableDetectionCount = 0
        statusErrorMessage = nil
        detector.reset()
        phase = .preparing

        guard cameraManager.authorizationStatus == .authorized else {
            phase = .failed("카메라 권한을 허용한 뒤 다시 시도해 주세요.")
            return
        }

        let detector = detector
        let orientation = UIDevice.current.orientation

        if videoUplink.canProvideFaceRegistrationFrames {
            activeFrameSource = .webRTC
            isUsingWebRTCFrames = true
            let started = videoUplink.startFaceFrameDelivery { [weak self, detector] pixelBuffer, cameraPosition in
                guard let outcome = detector.analyze(
                    pixelBuffer: pixelBuffer,
                    cameraPosition: cameraPosition,
                    deviceOrientation: orientation
                ) else { return }
                Task { @MainActor [weak self] in
                    self?.handle(
                        outcome,
                        generation: generation,
                        cameraManager: cameraManager,
                        videoUplink: videoUplink
                    )
                }
            }
            guard started else {
                activeFrameSource = nil
                isUsingWebRTCFrames = false
                phase = .failed("서버에 연결된 카메라 영상을 가져오지 못했습니다. 다시 시도해 주세요.")
                return
            }
            phase = .detecting("얼굴을 가운데 영역에 맞춰 주세요.")
            return
        }

        guard !videoUplink.isActive,
              !videoUplink.isCapturingCamera,
              !videoUplink.isSwitchingCamera else {
            activeFrameSource = nil
            isUsingWebRTCFrames = false
            phase = .failed("서버 카메라 연결이 완료된 뒤 다시 시도해 주세요.")
            return
        }

        isUsingWebRTCFrames = false
        await cameraManager.startDefaultCamera()
        activeFrameSource = .cameraManager
        let started = await cameraManager.startFaceFrameDelivery { [weak self, detector] sampleBuffer, cameraPosition in
            guard let outcome = detector.analyze(
                sampleBuffer: sampleBuffer,
                cameraPosition: cameraPosition,
                deviceOrientation: orientation
            ) else { return }
            Task { @MainActor [weak self] in
                self?.handle(
                    outcome,
                    generation: generation,
                    cameraManager: cameraManager,
                    videoUplink: videoUplink
                )
            }
        }

        guard started else {
            activeFrameSource = nil
            phase = .failed("카메라 영상을 준비하지 못했습니다. 다시 시도해 주세요.")
            return
        }
        phase = .detecting("얼굴을 가운데 영역에 맞춰 주세요.")
    }

    func stopDetection(
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        detectionGeneration &+= 1
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
        stableDetectionCount = 0
        if phase != .success {
            phase = .idle
        }
    }

    func retryDetection(
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
        await beginDetection(using: cameraManager, videoUplink: videoUplink)
    }

    func handleWebRTCFrameSourceUnavailable(videoUplink: WebRTCVideoUplink) {
        guard activeFrameSource == .webRTC else { return }
        detectionGeneration &+= 1
        videoUplink.stopFaceFrameDelivery()
        activeFrameSource = nil
        isUsingWebRTCFrames = false
        stableDetectionCount = 0
        phase = .failed("서버 카메라 연결이 변경되었습니다. 다시 시도해 주세요.")
    }

    func delete(faceID: String) async {
        guard !isDeleting else { return }
        isDeleting = true
        statusErrorMessage = nil
        defer { isDeleting = false }
        do {
            try await api.delete(faceID: faceID)
            status = try await api.status()
        } catch {
            statusErrorMessage = message(for: error)
        }
    }

    func deleteAll() async {
        guard !isDeleting else { return }
        isDeleting = true
        statusErrorMessage = nil
        defer { isDeleting = false }
        do {
            try await api.deleteAll()
            status = try await api.status()
        } catch {
            statusErrorMessage = message(for: error)
        }
    }

    private func handle(
        _ outcome: FaceDetectionOutcome,
        generation: UInt,
        cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) {
        guard generation == detectionGeneration,
              case .detecting = phase else { return }
        switch outcome {
        case .noFace:
            stableDetectionCount = 0
            phase = .detecting("얼굴을 가운데 영역에 보여 주세요.")
        case .multipleFaces:
            stableDetectionCount = 0
            phase = .detecting("한 명의 얼굴만 화면에 보여 주세요.")
        case .moveCloser:
            stableDetectionCount = 0
            phase = .detecting("얼굴이 조금 더 크게 보이도록 가까이 와 주세요.")
        case .centerFace:
            stableDetectionCount = 0
            phase = .detecting("얼굴을 가운데 영역에 맞춰 주세요.")
        case let .ready(jpegData):
            stableDetectionCount += 1
            guard stableDetectionCount >= 3 else {
                phase = .detecting("좋아요. 잠시 그대로 있어 주세요.")
                return
            }
            phase = .registering
            Task {
                await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
                do {
                    status = try await api.register(jpegData: jpegData)
                    phase = .success
                } catch {
                    phase = .failed(message(for: error))
                }
            }
        case .failed:
            stableDetectionCount = 0
            phase = .failed("얼굴을 분석하지 못했습니다. 다시 시도해 주세요.")
            Task {
                await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
            }
        }
    }

    private func stopActiveDetection(
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        switch activeFrameSource {
        case .cameraManager:
            await cameraManager.stopFaceFrameDelivery()
        case .webRTC:
            videoUplink.stopFaceFrameDelivery()
        case nil:
            break
        }
        activeFrameSource = nil
    }

    private func message(for error: Error) -> String {
        (error as? ReferenceFaceAPIError)?.userMessage
            ?? "얼굴 관리 요청을 처리하지 못했습니다. 다시 시도해 주세요."
    }
}
