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

@MainActor
final class FaceRegistrationViewModel: ObservableObject {
    @Published private(set) var status: ReferenceFaceStatus?
    @Published private(set) var isLoadingStatus = false
    @Published private(set) var isDeleting = false
    @Published private(set) var statusErrorMessage: String?
    @Published private(set) var phase: FaceRegistrationPhase = .idle

    private let api: ReferenceFaceAPI
    private let detector = FaceDetectionService()
    private var stableDetectionCount = 0

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

    func beginDetection(using cameraManager: CameraManager) async {
        stableDetectionCount = 0
        statusErrorMessage = nil
        detector.reset()
        phase = .preparing

        guard cameraManager.authorizationStatus == .authorized else {
            phase = .failed("카메라 권한을 허용한 뒤 다시 시도해 주세요.")
            return
        }

        await cameraManager.startDefaultCamera()
        let detector = detector
        let orientation = UIDevice.current.orientation
        let started = await cameraManager.startFaceFrameDelivery { [weak self, detector] sampleBuffer, cameraPosition in
            guard let outcome = detector.analyze(
                sampleBuffer: sampleBuffer,
                cameraPosition: cameraPosition,
                deviceOrientation: orientation
            ) else { return }
            Task { @MainActor [weak self] in
                self?.handle(outcome, cameraManager: cameraManager)
            }
        }

        guard started else {
            phase = .failed("카메라 영상을 준비하지 못했습니다. 다시 시도해 주세요.")
            return
        }
        phase = .detecting("얼굴을 안내 영역 가운데에 맞춰 주세요.")
    }

    func stopDetection(using cameraManager: CameraManager) async {
        await cameraManager.stopFaceFrameDelivery()
        stableDetectionCount = 0
        if phase != .success {
            phase = .idle
        }
    }

    func retryDetection(using cameraManager: CameraManager) async {
        await cameraManager.stopFaceFrameDelivery()
        await beginDetection(using: cameraManager)
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

    private func handle(_ outcome: FaceDetectionOutcome, cameraManager: CameraManager) {
        guard case .detecting = phase else { return }
        switch outcome {
        case .noFace:
            stableDetectionCount = 0
            phase = .detecting("얼굴을 안내 영역 안에 보여 주세요.")
        case .multipleFaces:
            stableDetectionCount = 0
            phase = .detecting("한 명의 얼굴만 화면에 보여 주세요.")
        case .moveCloser:
            stableDetectionCount = 0
            phase = .detecting("얼굴이 조금 더 크게 보이도록 가까이 와 주세요.")
        case .centerFace:
            stableDetectionCount = 0
            phase = .detecting("얼굴을 안내 영역 가운데에 맞춰 주세요.")
        case let .ready(jpegData):
            stableDetectionCount += 1
            guard stableDetectionCount >= 3 else {
                phase = .detecting("좋아요. 잠시 그대로 있어 주세요.")
                return
            }
            phase = .registering
            Task {
                await cameraManager.stopFaceFrameDelivery()
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
            Task { await cameraManager.stopFaceFrameDelivery() }
        }
    }

    private func message(for error: Error) -> String {
        (error as? ReferenceFaceAPIError)?.userMessage
            ?? "얼굴 관리 요청을 처리하지 못했습니다. 다시 시도해 주세요."
    }
}
