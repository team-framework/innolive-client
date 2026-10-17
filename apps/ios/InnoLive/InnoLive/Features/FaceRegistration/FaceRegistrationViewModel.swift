import AVFoundation
import Combine
import Foundation
import UIKit

enum FaceRegistrationPhase: Equatable {
    case idle
    case preparing
    case detecting(String)
    case naming
    case registering
    case success
    case failed(String)
}

/// The face the user tapped, frozen together with the frame it came from.
struct FaceRegistrationSelection {
    let frame: FaceSelectionFrame
    let faceIndex: Int
    let previewImage: CGImage

    var faceRect: CGRect { frame.faces[faceIndex].rect }
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
    var canConfirmName: Bool { ReferenceFaceName.isValid(name) }
    @Published private(set) var status: ReferenceFaceStatus?
    @Published private(set) var isLoadingStatus = false
    @Published private(set) var isDeleting = false
    @Published private(set) var statusErrorMessage: String?
    @Published private(set) var phase: FaceRegistrationPhase = .idle
    @Published private(set) var isUsingWebRTCFrames = false
    @Published private(set) var faces: [FaceSelectionCandidate] = []
    @Published private(set) var frameSize: CGSize = .zero
    @Published private(set) var selection: FaceRegistrationSelection?
    @Published private(set) var selectionErrorMessage: String?

    private let api: ReferenceFaceAPI
    private let detector = FaceDetectionService()
    private var latestFrame: FaceSelectionFrame?
    private var isFreezingSelection = false
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

    func beginDetection(
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        activeLifecycleSessionID = lifecycleSessionID
        invalidateDetection()
        let generation = detectionGeneration
        clearSelection()
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
        invalidateDetection()
        let generation = detectionGeneration
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
        guard isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
        activeLifecycleSessionID = nil
        clearSelection()
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

    /// Freezes the frame the user tapped so the face cannot move away while they type a name.
    func selectFace(
        at index: Int,
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        guard activeLifecycleSessionID == lifecycleSessionID,
              case .detecting = phase,
              !isFreezingSelection,
              let frame = latestFrame,
              frame.faces.indices.contains(index),
              frame.faces[index].isSelectable else { return }
        isFreezingSelection = true
        defer { isFreezingSelection = false }
        invalidateDetection()
        let generation = detectionGeneration
        await stopActiveDetection(using: cameraManager, videoUplink: videoUplink)
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }

        let detector = detector
        let previewImage = await Task.detached(priority: .userInitiated) {
            detector.previewImage(from: frame)
        }.value
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) else { return }
        guard let previewImage else {
            phase = .failed(String(localized: "선택한 얼굴을 준비하지 못했습니다. 다시 시도해 주세요."))
            return
        }
        name = ""
        selectionErrorMessage = nil
        selection = FaceRegistrationSelection(frame: frame, faceIndex: index, previewImage: previewImage)
        phase = .naming
    }

    /// One-tap registration: freezes the tapped face and registers it under a generated name.
    func registerFace(
        at index: Int,
        name: String,
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        await selectFace(
            at: index,
            lifecycleSessionID: lifecycleSessionID,
            using: cameraManager,
            videoUplink: videoUplink
        )
        guard activeLifecycleSessionID == lifecycleSessionID, phase == .naming else { return }
        self.name = name
        confirmSelection(lifecycleSessionID: lifecycleSessionID)
    }

    func setPreviewAspectRatio(_ ratio: CGFloat) {
        detector.setPreviewAspectRatio(ratio)
    }

    func reselectFace(
        lifecycleSessionID: UUID,
        using cameraManager: CameraManager,
        videoUplink: WebRTCVideoUplink
    ) async {
        guard activeLifecycleSessionID == lifecycleSessionID, phase == .naming else { return }
        await beginDetection(
            lifecycleSessionID: lifecycleSessionID,
            using: cameraManager,
            videoUplink: videoUplink
        )
    }

    func confirmSelection(lifecycleSessionID: UUID) {
        guard activeLifecycleSessionID == lifecycleSessionID,
              phase == .naming,
              registrationTask == nil,
              canConfirmName,
              let selection else { return }
        let generation = detectionGeneration
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let frame = selection.frame
        let faceIndex = selection.faceIndex
        let detector = detector
        selectionErrorMessage = nil
        phase = .registering

        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation) {
                    self.registrationTask = nil
                }
            }

            let jpegData = await Task.detached(priority: .userInitiated) {
                detector.registrationJPEG(from: frame, faceIndex: faceIndex)
            }.value
            guard !Task.isCancelled,
                  self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                  case .registering = self.phase else { return }
            guard let jpegData else {
                self.selectionErrorMessage = String(localized: "선택한 얼굴을 확인하지 못했습니다. 다시 선택해 주세요.")
                self.phase = .naming
                return
            }

            do {
                let registeredStatus = try await self.mode == .server
                    ? self.api.register(jpegData: jpegData, name: name)
                    : LocalFaceRegistrationStore.shared.register(jpegData: jpegData, name: name)
                guard !Task.isCancelled,
                      self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                      case .registering = self.phase else { return }
                self.status = registeredStatus
                self.name = ""
                self.phase = .success
            } catch {
                guard !Task.isCancelled,
                      self.isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
                      case .registering = self.phase else { return }
                // Keep the frozen face so the user can retry or pick another face.
                self.selectionErrorMessage = self.message(for: error)
                self.phase = .naming
            }
        }
        registrationTask = task
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
        clearSelection()
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

    private func handle(_ frame: FaceSelectionFrame, lifecycleSessionID: UUID, generation: UInt) {
        guard !Task.isCancelled,
              isCurrent(lifecycleSessionID: lifecycleSessionID, generation: generation),
              !isFreezingSelection,
              selection == nil,
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

    private func clearSelection() {
        latestFrame = nil
        faces = []
        selection = nil
        selectionErrorMessage = nil
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
