//
//  CameraManager.swift
//  InnoLive
//
//  Created by chaeyn on 7/29/26.
//

@preconcurrency import AVFoundation
import Observation

private enum CameraSettingKey {
    static let selectedCameraID = "selectedCameraID"
}

@Observable
final class CameraManager {
    let session = AVCaptureSession()
    // private(set): 읽기는 어디서나 가능, 갑 변경은 이 class 내부에서만 가능
    private(set) var authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    private var videoInput: AVCaptureDeviceInput?
    private var faceVideoOutput: AVCaptureVideoDataOutput?
    private let faceFrameRelay = CameraFrameRelay()
    private(set) var currentCameraID: String?
    private(set) var currentCameraName: String?

    // AVCaptureSession을 만지는 작업은 이 큐에서만 실행
    private let sessionQueue = DispatchQueue(label: "com.innolive.camera.session")

    func requestCameraAccess() {
        switch authorizationStatus {
        case .authorized:
            return

        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    self?.authorizationStatus = granted ? .authorized : .denied
                }
            }

        case .denied, .restricted:
            return

        @unknown default:
            return
        }
    }

    // 설정 앱에서 돌아온 뒤 시스템의 최신 권한 상태를 다시 반영함
    func refreshAuthorizationStatus() {
        authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    }

    // 설정 화면에서 선택한 id의 카메라를 찾음
    private func cameraDevice(for cameraID: String) -> AVCaptureDevice? {
        AVCaptureDevice(uniqueID: cameraID)
    }

    func addCameraInput(for cameraID: String) {
        sessionQueue.async { [weak self] in
            self?.addCameraInputOnSessionQueue(for: cameraID)
        }
    }

    // sessionQueue에서 실행
    @discardableResult // 반환값이 없어도 됨
    private func addCameraInputOnSessionQueue(for cameraID: String) -> Bool {
        guard videoInput == nil else {
            return true
        }

        guard let device = cameraDevice(for: cameraID) else {
            return false
        }

        do {
            // AVCaptureDeviceInput: 카메라와 session을 연결하는 객체
            let input = try AVCaptureDeviceInput(device: device)

            guard session.canAddInput(input) else {
                return false
            }

            session.addInput(input)
            videoInput = input
            updateCurrentCamera(device)
            return true
        } catch {
            print("카메라를 연결하지 못했습니다: \(error.localizedDescription)")
            return false
        }
    }

    // 연결된 카메라로 실시간 영상 시작
    func startSession() {
        sessionQueue.async { [weak self] in
            self?.startSessionOnSessionQueue()
        }
    }

    // 네이티브 WebRTC capturer가 같은 카메라를 열기 전에 프리뷰 session의
    // 장치 점유를 완전히 해제한다. 선택 상태는 유지하고 입력 객체만 제거한다.
    func stopSession() async {
        await withCheckedContinuation { continuation in
            sessionQueue.async { [weak self] in
                guard let self else {
                    continuation.resume()
                    return
                }
                if self.session.isRunning {
                    self.session.stopRunning()
                }
                if self.videoInput != nil || self.faceVideoOutput != nil {
                    self.session.beginConfiguration()
                    if let faceVideoOutput = self.faceVideoOutput {
                        self.session.removeOutput(faceVideoOutput)
                        self.faceVideoOutput = nil
                    }
                    if let videoInput = self.videoInput {
                        self.session.removeInput(videoInput)
                    }
                    self.session.commitConfiguration()
                    self.videoInput = nil
                }
                self.faceFrameRelay.update(handler: nil, cameraPosition: .unspecified)
                continuation.resume()
            }
        }
    }

    @discardableResult
    func startFaceFrameDelivery(
        handler: @escaping @Sendable (CMSampleBuffer, AVCaptureDevice.Position) -> Void
    ) async -> Bool {
        await withCheckedContinuation { continuation in
            sessionQueue.async { [weak self] in
                guard let self, let videoInput else {
                    continuation.resume(returning: false)
                    return
                }

                faceFrameRelay.update(
                    handler: handler,
                    cameraPosition: videoInput.device.position
                )
                if faceVideoOutput != nil {
                    continuation.resume(returning: true)
                    return
                }

                let output = AVCaptureVideoDataOutput()
                output.alwaysDiscardsLateVideoFrames = true
                output.videoSettings = [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
                ]
                output.setSampleBufferDelegate(
                    faceFrameRelay,
                    queue: DispatchQueue(label: "com.innolive.camera.face-detection")
                )

                session.beginConfiguration()
                defer { session.commitConfiguration() }
                guard session.canAddOutput(output) else {
                    faceFrameRelay.update(handler: nil, cameraPosition: .unspecified)
                    continuation.resume(returning: false)
                    return
                }
                session.addOutput(output)
                faceVideoOutput = output
                continuation.resume(returning: true)
            }
        }
    }

    func stopFaceFrameDelivery() async {
        await withCheckedContinuation { continuation in
            sessionQueue.async { [weak self] in
                guard let self else {
                    continuation.resume()
                    return
                }
                faceFrameRelay.update(handler: nil, cameraPosition: .unspecified)
                if let faceVideoOutput {
                    session.beginConfiguration()
                    session.removeOutput(faceVideoOutput)
                    session.commitConfiguration()
                    self.faceVideoOutput = nil
                }
                continuation.resume()
            }
        }
    }

    // sessionQueue에서 실행
    private func startSessionOnSessionQueue() {
        guard !session.isRunning else {
            return
        }

        session.startRunning()
    }

    @discardableResult
    @MainActor
    func switchCamera(to cameraID: String) async -> Bool {
        let switchedDevice: AVCaptureDevice? = await withCheckedContinuation { continuation in
            sessionQueue.async { [weak self] in
                guard let self,
                      self.switchCameraOnSessionQueue(to: cameraID),
                      let device = self.cameraDevice(for: cameraID) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: device)
            }
        }
        guard let switchedDevice else { return false }
        currentCameraID = switchedDevice.uniqueID
        currentCameraName = switchedDevice.localizedName
        UserDefaults.standard.set(switchedDevice.uniqueID, forKey: CameraSettingKey.selectedCameraID)
        return true
    }

    // sessionQueue에서 실행
    private func switchCameraOnSessionQueue(to cameraID: String) -> Bool {
        guard let device = cameraDevice(for: cameraID) else {
            return false
        }

        // 방송 중에는 네이티브 WebRTC capturer가 카메라를 점유
        // 이때 설정에서 선택을 바꿔도 별도의 AVCaptureDeviceInput을 열지 않고
        // 다음 방송에 쓸 선택값만 갱신해 현재 WebRTC 영상이 멈추는 것을 막음
        guard videoInput != nil || session.isRunning else {
            return true
        }

        // 이미 같은 카메라를 쓰고 있으면 다시 연결하지 않음
        guard videoInput?.device.uniqueID != cameraID else { return true }

        do {
            let newInput = try AVCaptureDeviceInput(device: device)
            let previousInput = videoInput

            // 입력을 교체하는 동안 session의 중간 상태가 화면에 보이지 않게 함
            session.beginConfiguration()
            defer { session.commitConfiguration() }

            if let previousInput {
                session.removeInput(previousInput)
            }

            guard session.canAddInput(newInput) else {
                // 새 카메라를 추가할 수 없으면 이전 카메라를 다시 연결함
                if let previousInput, session.canAddInput(previousInput) {
                    session.addInput(previousInput)
                }
                return false
            }

            session.addInput(newInput)
            videoInput = newInput
            return true
        } catch {
            print("카메라를 변경하지 못했습니다: \(error.localizedDescription)")
            return false
        }
    }

    // 앱을 처음 열었을 때 전면 카메라를 연결하고 시작한다.
    // 반환 시점에는 입력 연결과 session 실행이 끝나 있어 프리뷰를 안전하게 표시할 수 있다.
    func startDefaultCamera() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            sessionQueue.async { [weak self] in
                defer { continuation.resume() }
                guard let self else {
                    return
                }

                let savedCamera = UserDefaults.standard
                    .string(forKey: CameraSettingKey.selectedCameraID)
                    .flatMap(self.cameraDevice(for:))
                let frontCamera = AVCaptureDevice.default(
                    .builtInWideAngleCamera,
                    for: .video,
                    position: .front
                )

                guard let camera = savedCamera ?? frontCamera
                else {
                    return
                }

                // 카메라 정보가 없으면 전면 카메라를 기본으로 사용
                guard self.addCameraInputOnSessionQueue(for: camera.uniqueID) else {
                    guard let frontCamera,
                          frontCamera.uniqueID != camera.uniqueID,
                          self.addCameraInputOnSessionQueue(for: frontCamera.uniqueID)
                    else {
                        return
                    }

                    self.startSessionOnSessionQueue()
                    return
                }
                self.startSessionOnSessionQueue()
            }
        }
    }

    // sessionQueue에서 처리한 실제 카메라 상태를 메인 스레드에 반영
    private func updateCurrentCamera(_ device: AVCaptureDevice) {
        DispatchQueue.main.async { [weak self] in
            self?.currentCameraID = device.uniqueID
            self?.currentCameraName = device.localizedName
        }
    }
}

nonisolated private final class CameraFrameRelay: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    typealias Handler = @Sendable (CMSampleBuffer, AVCaptureDevice.Position) -> Void

    private let lock = NSLock()
    private var handler: Handler?
    private var cameraPosition: AVCaptureDevice.Position = .unspecified

    func update(handler: Handler?, cameraPosition: AVCaptureDevice.Position) {
        lock.lock()
        self.handler = handler
        self.cameraPosition = cameraPosition
        lock.unlock()
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        lock.lock()
        let handler = handler
        let cameraPosition = cameraPosition
        lock.unlock()
        handler?(sampleBuffer, cameraPosition)
    }
}
