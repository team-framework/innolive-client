import AVFoundation
import Combine
import Foundation
@preconcurrency import LiveKitWebRTC
import UIKit

@MainActor
final class WebRTCVideoUplink: NSObject, ObservableObject {
    @Published private(set) var state: WebRTCVideoUplinkState = .idle
    @Published private(set) var statusText = "영상 업링크 대기"
    @Published private(set) var errorMessage: String?
    @Published private(set) var hasRemoteVideo = false
    @Published private(set) var isUsingFrontCamera = false
    @Published private(set) var isSwitchingCamera = false
    @Published private(set) var isReleasingCamera = false
    @Published private(set) var requiresMediaPermissionSettings = false

    private static let sslInitialized = LKRTCInitializeSSL()

    let encoder = JSONEncoder()
    let decoder = JSONDecoder()
    let peerConnectionFactory: LKRTCPeerConnectionFactory
    var peerConnection: LKRTCPeerConnection?
    var cameraCapturer: LKRTCCameraVideoCapturer?
    var videoSource: LKRTCVideoSource?
    var localVideoTrack: LKRTCVideoTrack?
    var remoteVideoTrack: LKRTCVideoTrack?
    var videoSender: LKRTCRtpSender?
    var audioSource: LKRTCAudioSource?
    var localAudioTrack: LKRTCAudioTrack?
    var audioSender: LKRTCRtpSender?
    var activeCameraID: String?
    var activeAudioInputID: String?
    var activeVideoQuality: CameraQualityPreset?
    var isAudioSessionActivated = false
    weak var localRenderer: LKRTCMTLVideoView?
    weak var remoteRenderer: LKRTCMTLVideoView?

    var webSocketTask: URLSessionWebSocketTask?
    var credentials: WebRTCSessionCredentials?
    var accessToken: String?
    var pendingRemoteCandidates: [WebRTCServerSignal] = []
    private var startContinuation: CheckedContinuation<Void, Error>?
    private var startTimeoutTask: Task<Void, Never>?
    var outboundVerificationTask: Task<Void, Never>?
    private var pendingCameraStopTask: Task<Void, Never>?
    var cameraOperationGeneration: UInt = 0
    var isStopping = false

    override init() {
        _ = Self.sslInitialized
        let encoderFactory = LKRTCDefaultVideoEncoderFactory()
        let decoderFactory = LKRTCDefaultVideoDecoderFactory()
        peerConnectionFactory = LKRTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )
        super.init()
    }

    var isActive: Bool {
        state == .preparing || state == .connecting || state == .connected
    }

    var isConnecting: Bool {
        state == .preparing || state == .connecting
    }

    var isCapturingCamera: Bool {
        cameraCapturer != nil || isReleasingCamera
    }

    var currentCameraID: String? {
        activeCameraID
    }

    var currentAudioInputID: String? {
        activeAudioInputID
    }

    var currentVideoQuality: CameraQualityPreset? {
        activeVideoQuality
    }

    func start(
        session: WebRTCSessionCredentials,
        accessToken: String,
        serverURL: URL,
        iceServers: [WebRTCIceServer],
        preferredCameraID: String?,
        preferredAudioID: String?,
        preferredVideoQuality: CameraQualityPreset
    ) async throws {
        await stopAndWait()
        isStopping = false
        cameraOperationGeneration &+= 1
        let operationGeneration = cameraOperationGeneration
        credentials = session
        self.accessToken = accessToken
        errorMessage = nil
        requiresMediaPermissionSettings = false
        hasRemoteVideo = false
        pendingRemoteCandidates = []
        updateState(.preparing, "카메라와 마이크를 준비 중…")

        do {
            try await prepareNativeMicrophone(
                preferredAudioID: preferredAudioID,
                operationGeneration: operationGeneration
            )
            try await prepareNativeCamera(
                preferredCameraID: preferredCameraID,
                preferredVideoQuality: preferredVideoQuality,
                operationGeneration: operationGeneration
            )
            try ensureCurrentCameraOperation(operationGeneration)

            try preparePeerConnection(iceServers: iceServers)
            connectSignaling(to: signalingURL(from: serverURL))

            try await withCheckedThrowingContinuation { continuation in
                startContinuation = continuation
                startTimeoutTask = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(25))
                    guard let self, self.startContinuation != nil else { return }
                    self.fail("카메라 영상의 서버 전송을 확인하지 못했습니다. 방송을 다시 시작해 주세요.")
                }
                createAndSendOffer()
            }
        } catch {
            if !isStopping, state != .failed {
                fail((error as? LocalizedError)?.errorDescription ?? "카메라 영상을 연결하지 못했습니다.")
            }
            throw error
        }
    }

    func stop() {
        let capturer = beginTeardown()
        scheduleCameraStop(capturer)
    }

    func stopAndWait() async {
        let pendingStop = pendingCameraStopTask
        let capturer = beginTeardown()
        if let pendingStop {
            await pendingStop.value
            pendingCameraStopTask = nil
        }
        if let capturer {
            await stopCapture(capturer)
        }
        isReleasingCamera = false
    }

    private func beginTeardown(
        resetState: Bool = true,
        continuationError: WebRTCVideoUplinkError = .cancelled
    ) -> LKRTCCameraVideoCapturer? {
        isStopping = true
        cameraOperationGeneration &+= 1
        startTimeoutTask?.cancel()
        startTimeoutTask = nil
        outboundVerificationTask?.cancel()
        outboundVerificationTask = nil

        if let startContinuation {
            self.startContinuation = nil
            startContinuation.resume(throwing: continuationError)
        }

        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        peerConnection?.close()
        peerConnection = nil
        let capturer = cameraCapturer
        cameraCapturer = nil
        if capturer != nil {
            isReleasingCamera = true
        }

        detachTracksFromRenderers()
        localAudioTrack?.isEnabled = false
        localVideoTrack?.isEnabled = false
        audioSource = nil
        localAudioTrack = nil
        audioSender = nil
        videoSource = nil
        localVideoTrack = nil
        remoteVideoTrack = nil
        videoSender = nil
        activeCameraID = nil
        activeAudioInputID = nil
        activeVideoQuality = nil
        credentials = nil
        accessToken = nil
        pendingRemoteCandidates = []
        hasRemoteVideo = false
        isUsingFrontCamera = false
        isSwitchingCamera = false
        deactivateAudioSessionIfNeeded()
        if resetState {
            updateState(.idle, "영상 업링크 대기")
        }
        return capturer
    }

    private func scheduleCameraStop(_ capturer: LKRTCCameraVideoCapturer?) {
        guard let capturer else { return }
        let operationGeneration = cameraOperationGeneration
        let stopTask = Task {
            await withCheckedContinuation { continuation in
                capturer.stopCapture {
                    continuation.resume()
                }
            }
        }
        pendingCameraStopTask = stopTask
        Task { [weak self] in
            await stopTask.value
            guard let self,
                  self.cameraOperationGeneration == operationGeneration else { return }
            self.pendingCameraStopTask = nil
            self.isReleasingCamera = false
        }
    }

    func markPublisherReady() {
        guard state == .connected else { return }
        updateState(.connected, "카메라 영상이 서버에 연결되었습니다.")
    }

    func dismissError() {
        errorMessage = nil
    }

    func setRemoteVideoAvailable(_ isAvailable: Bool) {
        hasRemoteVideo = isAvailable
    }

    func setUsingFrontCamera(_ isFrontCamera: Bool) {
        isUsingFrontCamera = isFrontCamera
    }

    func setCameraSwitching(_ isSwitching: Bool) {
        isSwitchingCamera = isSwitching
    }

    func markMediaPermissionRequired() {
        requiresMediaPermissionSettings = true
    }

    func completeStartIfNeeded() {
        guard let startContinuation else { return }
        self.startContinuation = nil
        startTimeoutTask?.cancel()
        startTimeoutTask = nil
        startContinuation.resume()
    }

    func fail(_ message: String) {
        guard !isStopping else { return }
        errorMessage = message
        let capturer = beginTeardown(
            resetState: false,
            continuationError: .failed(message)
        )
        scheduleCameraStop(capturer)
        updateState(.failed, message)
    }

    func updateState(_ state: WebRTCVideoUplinkState, _ status: String) {
        self.state = state
        statusText = status
    }

}
