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
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    @Published var name = ""
    let mode: AIProcessingMode
    var canRegister: Bool { ReferenceFaceName.isValid(name) }
    /// 다시 등록할 서버 얼굴이다. 있으면 촬영한 사진을 새 얼굴 대신 이 face_id로 올린다.
    @Published private(set) var reregistrationTarget: ReferenceFace?
    /// 다시 등록은 기존 이름을 유지하므로 이름 입력이 필요 없다.
    var canStartCapture: Bool { reregistrationTarget != nil || canRegister }
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
    private var registrationTask: Task<Void, Never>?
    private var activeLifecycleSessionID: UUID?

    init(authentication: AuthSession, mode: AIProcessingMode = .server) {
        self.mode = mode
        api = ReferenceFaceAPI(authentication: authentication)
    }

    func loadStatus() async {
        guard !isLoadingStatus else { return }
        isLoadingStatus = true
        statusErrorMessage = nil
        defer { isLoadingStatus = false }
        do {
            status = try await mode == .server ? api.status() : LocalFaceRegistrationStore.shared.status()
        } catch {
            statusErrorMessage = message(for: error)
        }
    }

    func prepareReregistration(of face: ReferenceFace) {
        guard mode == .server, phase != .registering else { return }
        statusErrorMessage = nil
        phase = .idle
        reregistrationTarget = face
    }

    func clearReregistrationTarget() {
        guard phase != .registering else { return }
        reregistrationTarget = nil
    }

    func beginDetection(
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        guard canStartCapture else { phase = .failed(String(localized: "이름을 1~40자로 입력해 주세요.")); return }
        activeLifecycleSessionID = lifecycleSessionID
        invalidateDetection()
        let generation = detectionGeneration
        stableDetectionCount = 0
        statusErrorMessage = nil
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
                guard let outcome = detector.analyze(
                    pixelBuffer: pixelBuffer,
                    cameraPosition: cameraPosition,
                    deviceOrientation: orientation
                ) else { return }
                Task { @MainActor [weak self] in
                    self?.handle(
                        outcome,
                        lifecycleSessionID: lifecycleSessionID,
                        generation: generation,
                        cameraManager: cameraManager,
                        videoUplink: videoUplink
                    )
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
            phase = .detecting(String(localized: "얼굴을 가운데 영역에 맞춰 주세요."))
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
            guard let outcome = detector.analyze(
                sampleBuffer: sampleBuffer,
                cameraPosition: cameraPosition,
                deviceOrientation: orientation
            ) else { return }
            Task { @MainActor [weak self] in
                self?.handle(
                    outcome,
                    lifecycleSessionID: lifecycleSessionID,
                    generation: generation,
                    cameraManager: cameraManager,
                    videoUplink: videoUplink
                )
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
        phase = .detecting(String(localized: "얼굴을 가운데 영역에 맞춰 주세요."))
    }

    func stopDetection(
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        guard activeLifecycleSessionID == lifecycleSessionID else { return }
        invalidateDetection()
        let generation = detectionGeneration
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
        guard isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
        activeLifecycleSessionID = nil
        stableDetectionCount = 0
        if phase != .success {
            phase = .idle
        }
    }

    func retryDetection(
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        guard activeLifecycleSessionID == lifecycleSessionID else { return }
        invalidateDetection()
        let generation = detectionGeneration
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
        await beginDetection(
            lifecycleSessionID: lifecycleSessionID,
            using: cameraManager,
            videoUplink: videoUplink
        )
    }

    func handleWebRTCFrameSourceUnavailable(
        lifecycleSessionID: UUID,
        videoUplink: WebRTCVideoUplink
    ) {
        guard activeLifecycleSessionID == lifecycleSessionID,
              activeFrameSource == .webRTC,
              isUsingWebRTCFrames else { return }
        invalidateDetection()
        activeFrameSource = nil
        isUsingWebRTCFrames = false
        videoUplink.stopFaceFrameDelivery()
        stableDetectionCount = 0
        phase = .failed(String(localized: "서버 카메라 연결이 변경되었습니다. 다시 시도해 주세요."))
    }

    func delete(faceID: String) async {
        guard !isDeleting else { return }
        isDeleting = true
        statusErrorMessage = nil
        defer { isDeleting = false }
        do {
            if mode == .server { try await api.delete(faceID: faceID) }
            else { try await LocalFaceRegistrationStore.shared.delete(faceID: faceID) }
            status = try await mode == .server ? api.status() : LocalFaceRegistrationStore.shared.status()
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
            if mode == .server { try await api.deleteAll() }
            else { try await LocalFaceRegistrationStore.shared.deleteAll() }
            status = try await mode == .server ? api.status() : LocalFaceRegistrationStore.shared.status()
        } catch {
            statusErrorMessage = message(for: error)
        }
    }

    private func handle(
        _ outcome: FaceDetectionOutcome,
        lifecycleSessionID: UUID,
        generation: UInt,
        cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) {
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
              registrationTask == nil,
              case .detecting = phase else { return }
        switch outcome {
        case .noFace:
            stableDetectionCount = 0
            phase = .detecting(String(localized: "얼굴을 가운데 영역에 보여 주세요."))
        case .multipleFaces:
            stableDetectionCount = 0
            phase = .detecting(String(localized: "한 명의 얼굴만 화면에 보여 주세요."))
        case .moveCloser:
            stableDetectionCount = 0
            phase = .detecting(String(localized: "얼굴이 조금 더 크게 보이도록 가까이 와 주세요."))
        case .centerFace:
            stableDetectionCount = 0
            phase = .detecting(String(localized: "얼굴을 가운데 영역에 맞춰 주세요."))
        case let .ready(jpegData):
            stableDetectionCount += 1
            guard stableDetectionCount >= 3 else {
                phase = .detecting(String(localized: "좋아요. 잠시 그대로 있어 주세요."))
                return
            }
            phase = .registering
            let task = Task { @MainActor [weak self] in
                guard let self else { return }
                defer {
                    if self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) {
                        self.registrationTask = nil
                    }
                }

                guard !Task.isCancelled,
                      self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                      case .registering = self.phase else { return }
                await self.stopActiveDetection(using: cameraManager, videoUplink: videoUplink)

                guard !Task.isCancelled,
                      self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                      case .registering = self.phase else { return }

                let target = self.reregistrationTarget
                do {
                    let registeredStatus: ReferenceFaceStatus
                    if let target {
                        registeredStatus = try await self.api.reregister(faceID: target.id, jpegData: jpegData)
                    } else {
                        let name = self.name.trimmingCharacters(in: .whitespacesAndNewlines)
                        registeredStatus = try await self.mode == .server
                            ? self.api.register(jpegData: jpegData, name: name)
                            : LocalFaceRegistrationStore.shared.register(jpegData: jpegData, name: name)
                    }
                    guard !Task.isCancelled,
                          self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                          case .registering = self.phase else { return }
                    // 응답이 서버의 최신 목록이므로 다시 등록한 얼굴의 표시가 풀린 상태로 바뀐다.
                    self.status = registeredStatus
                    if target == nil { self.name = "" }
                    self.reregistrationTarget = nil
                    self.phase = .success
                } catch ReferenceFaceAPIError.api(code: "not_found", _) where target != nil {
                    // 다른 기기에서 지웠거나 목록이 바뀌었다. 촬영 화면을 닫기 전에 목록을 새로 읽는다.
                    let message = ReferenceFaceAPIError.api(code: "not_found", fallback: "").userMessage
                    await self.loadStatus()
                    guard !Task.isCancelled,
                          self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                          case .registering = self.phase else { return }
                    if self.statusErrorMessage == nil { self.statusErrorMessage = message }
                    self.phase = .idle
                    self.reregistrationTarget = nil
                } catch {
                    guard !Task.isCancelled,
                          self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                          case .registering = self.phase else { return }
                    self.phase = .failed(self.message(for: error))
                }
            }
            registrationTask = task
        case .failed:
            stableDetectionCount = 0
            phase = .failed(String(localized: "얼굴을 분석하지 못했습니다. 다시 시도해 주세요."))
            Task { @MainActor [weak self] in
                guard let self,
                      !Task.isCancelled,
                      self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
                await self.stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
            }
        }
    }

    private func invalidateDetection() {
        detectionGeneration &+= 1
        registrationTask?.cancel()
        registrationTask = nil
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

    private func message(for error: Error) -> String {
        (error as? ReferenceFaceAPIError)?.userMessage
            ?? (error as? LocalizedError)?.errorDescription
            ?? String(localized: "얼굴 관리 요청을 처리하지 못했습니다. 다시 시도해 주세요.")
    }
}
