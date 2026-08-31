import AVFoundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func switchCamera(to cameraID: String) async throws {
        guard !isStopping,
              !isSwitchingCamera,
              let capturer = cameraCapturer,
              let previousCameraID = activeCameraID,
              let previousDevice = AVCaptureDevice(uniqueID: previousCameraID),
              let activeVideoQuality,
              let previousSetting = captureSetting(for: previousDevice, quality: activeVideoQuality) else {
            throw WebRTCVideoUplinkError.failed("비식별화를 시작한 뒤 카메라를 전환해 주세요.")
        }
        guard cameraID != previousCameraID else { return }
        guard let newDevice = AVCaptureDevice(uniqueID: cameraID),
              let newSetting = captureSetting(for: newDevice, quality: activeVideoQuality) else {
            throw WebRTCVideoUplinkError.failed("선택한 카메라를 사용할 수 없습니다.")
        }

        cameraOperationGeneration &+= 1
        let operationGeneration = cameraOperationGeneration
        setCameraSwitching(true)
        defer {
            if cameraOperationGeneration == operationGeneration {
                setCameraSwitching(false)
            }
        }

        await stopCapture(capturer)
        try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)

        do {
            try await startCapture(capturer, device: newDevice, setting: newSetting)
            try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
            activeCameraID = newDevice.uniqueID
            setUsingFrontCamera(newDevice.position == .front)
        } catch {
            guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
                throw WebRTCVideoUplinkError.cancelled
            }
            await stopCapture(capturer)
            try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
            do {
                try await startCapture(capturer, device: previousDevice, setting: previousSetting)
                try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
            } catch {
                guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
                    throw WebRTCVideoUplinkError.cancelled
                }
                fail("카메라 연결을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요.")
                throw WebRTCVideoUplinkError.failed("카메라 연결을 복구하지 못했습니다.")
            }
            activeCameraID = previousDevice.uniqueID
            setUsingFrontCamera(previousDevice.position == .front)
            throw WebRTCVideoUplinkError.failed("카메라를 전환하지 못해 기존 카메라를 계속 사용합니다.")
        }
    }

    func prepareNativeCamera(
        preferredCameraID: String?,
        preferredVideoQuality: CameraQualityPreset,
        operationGeneration: UInt
    ) async throws {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
            markMediaPermissionRequired()
            throw WebRTCVideoUplinkError.failed("카메라 권한이 필요합니다.")
        }

        let availableDevices = LKRTCCameraVideoCapturer.captureDevices()
        let selectedDevice = preferredCameraID
            .flatMap { cameraID in availableDevices.first { $0.uniqueID == cameraID } }
            ?? availableDevices.first { $0.position == .front }
            ?? availableDevices.first

        guard let selectedDevice else {
            throw WebRTCVideoUplinkError.failed("사용할 수 있는 카메라를 찾지 못했습니다.")
        }
        guard let captureSetting = captureSetting(
            for: selectedDevice,
            quality: preferredVideoQuality
        ) else {
            throw WebRTCVideoUplinkError.failed("선택한 카메라의 영상 형식을 준비하지 못했습니다.")
        }

        setUsingFrontCamera(selectedDevice.position == .front)
        let source = peerConnectionFactory.videoSource()
        adapt(source, to: preferredVideoQuality)
        let frameRelay = WebRTCCameraFrameRelay(
            target: source,
            cameraPosition: selectedDevice.position
        )
        let capturer = LKRTCCameraVideoCapturer(delegate: frameRelay)
        let track = peerConnectionFactory.videoTrack(with: source, trackId: "innolive-camera")
        track.isEnabled = true
        videoSource = source
        cameraCapturer = capturer
        cameraFrameRelay = frameRelay
        localVideoTrack = track
        if let localRenderer {
            track.add(localRenderer)
        }
        if let faceRegistrationRenderer {
            track.add(faceRegistrationRenderer)
        }

        try await startCapture(capturer, device: selectedDevice, setting: captureSetting)
        guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
            throw WebRTCVideoUplinkError.cancelled
        }
        activeCameraID = selectedDevice.uniqueID
        activeVideoQuality = preferredVideoQuality
    }

    @discardableResult
    func startFaceFrameDelivery(
        handler: @escaping WebRTCCameraFrameRelay.FaceFrameHandler
    ) -> Bool {
        guard canProvideFaceRegistrationFrames,
              let cameraFrameRelay,
              let activeCameraID,
              let camera = AVCaptureDevice(uniqueID: activeCameraID) else {
            return false
        }
        cameraFrameRelay.setFaceFrameHandler(handler, cameraPosition: camera.position)
        return true
    }

    func stopFaceFrameDelivery() {
        cameraFrameRelay?.setFaceFrameHandler(nil, cameraPosition: .unspecified)
    }

    func switchVideoQuality(to quality: CameraQualityPreset) async throws {
        guard !isStopping,
              !isSwitchingCamera,
              let capturer = cameraCapturer,
              let cameraID = activeCameraID,
              let device = AVCaptureDevice(uniqueID: cameraID),
              let previousQuality = activeVideoQuality,
              let previousSetting = captureSetting(for: device, quality: previousQuality),
              let source = videoSource else {
            throw WebRTCVideoUplinkError.failed("비식별화를 시작한 뒤 화질을 변경해 주세요.")
        }
        guard quality != previousQuality else { return }
        guard let newSetting = captureSetting(for: device, quality: quality) else {
            throw WebRTCVideoUplinkError.failed("현재 카메라가 선택한 화질을 지원하지 않습니다.")
        }

        cameraOperationGeneration &+= 1
        let operationGeneration = cameraOperationGeneration
        setCameraSwitching(true)
        defer {
            if cameraOperationGeneration == operationGeneration {
                setCameraSwitching(false)
            }
        }

        await stopCapture(capturer)
        try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)

        do {
            adapt(source, to: quality)
            try await startCapture(capturer, device: device, setting: newSetting)
            try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
            activeVideoQuality = quality
        } catch {
            guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
                throw WebRTCVideoUplinkError.cancelled
            }
            await stopCapture(capturer)
            try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
            do {
                adapt(source, to: previousQuality)
                try await startCapture(capturer, device: device, setting: previousSetting)
                try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
                activeVideoQuality = previousQuality
            } catch {
                guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
                    throw WebRTCVideoUplinkError.cancelled
                }
                fail("카메라 화질을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요.")
                throw WebRTCVideoUplinkError.failed("카메라 화질을 복구하지 못했습니다.")
            }
            throw WebRTCVideoUplinkError.failed("화질을 변경하지 못해 기존 화질을 계속 사용합니다.")
        }
    }

    private func isCurrentCameraOperation(
        _ generation: UInt,
        capturer: LKRTCCameraVideoCapturer? = nil
    ) -> Bool {
        guard !isStopping, cameraOperationGeneration == generation else { return false }
        guard let capturer else { return true }
        return cameraCapturer === capturer
    }

    func ensureCurrentCameraOperation(
        _ generation: UInt,
        capturer: LKRTCCameraVideoCapturer? = nil
    ) throws {
        guard isCurrentCameraOperation(generation, capturer: capturer) else {
            throw WebRTCVideoUplinkError.cancelled
        }
    }

    private func startCapture(
        _ capturer: LKRTCCameraVideoCapturer,
        device: AVCaptureDevice,
        setting: CameraCaptureSetting
    ) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            capturer.startCapture(with: device, format: setting.format, fps: setting.fps) { error in
                if let error {
                    continuation.resume(
                        throwing: WebRTCVideoUplinkError.failed(
                            "선택한 카메라를 열지 못했습니다: \(error.localizedDescription)"
                        )
                    )
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func stopCapture(_ capturer: LKRTCCameraVideoCapturer) async {
        await withCheckedContinuation { continuation in
            capturer.stopCapture {
                continuation.resume()
            }
        }
    }

    private func captureSetting(
        for device: AVCaptureDevice,
        quality: CameraQualityPreset
    ) -> CameraCaptureSetting? {
        let candidates = LKRTCCameraVideoCapturer.supportedFormats(for: device).compactMap { format -> CameraCaptureSetting? in
            guard format.videoSupportedFrameRateRanges.contains(where: { range in
                range.minFrameRate <= Double(quality.framesPerSecond)
                    && range.maxFrameRate >= Double(quality.framesPerSecond)
            }) else { return nil }
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            return CameraCaptureSetting(
                format: format,
                width: dimensions.width,
                height: dimensions.height,
                fps: quality.framesPerSecond
            )
        }

        return candidates.min { lhs, rhs in
            captureScore(lhs, quality: quality) < captureScore(rhs, quality: quality)
        }
    }

    private func captureScore(
        _ setting: CameraCaptureSetting,
        quality: CameraQualityPreset
    ) -> Int64 {
        let widthDifference = Int64(abs(setting.width - quality.width))
        let heightDifference = Int64(abs(setting.height - quality.height))
        return widthDifference + heightDifference
    }

    private func adapt(_ source: LKRTCVideoSource, to quality: CameraQualityPreset) {
        source.adaptOutputFormat(
            toWidth: quality.width,
            height: quality.height,
            fps: Int32(quality.framesPerSecond)
        )
    }

}

private struct CameraCaptureSetting {
    let format: AVCaptureDevice.Format
    let width: Int32
    let height: Int32
    let fps: Int
}

nonisolated final class WebRTCCameraFrameRelay: NSObject, LKRTCVideoCapturerDelegate, @unchecked Sendable {
    typealias FaceFrameHandler = @Sendable (CVPixelBuffer, AVCaptureDevice.Position) -> Void

    private static let deliveryInterval: TimeInterval = 0.1

    private let target: LKRTCVideoCapturerDelegate
    private let analysisQueue = DispatchQueue(label: "com.innolive.webrtc.face-detection")
    private let lock = NSLock()
    private var faceFrameHandler: FaceFrameHandler?
    private var cameraPosition: AVCaptureDevice.Position
    private var isAnalysisPending = false
    private var lastDeliveryTime: TimeInterval = 0

    init(target: LKRTCVideoCapturerDelegate, cameraPosition: AVCaptureDevice.Position) {
        self.target = target
        self.cameraPosition = cameraPosition
        super.init()
    }

    func setFaceFrameHandler(
        _ handler: FaceFrameHandler?,
        cameraPosition: AVCaptureDevice.Position
    ) {
        lock.lock()
        faceFrameHandler = handler
        self.cameraPosition = cameraPosition
        lastDeliveryTime = 0
        lock.unlock()
    }

    func updateCameraPosition(_ cameraPosition: AVCaptureDevice.Position) {
        lock.lock()
        self.cameraPosition = cameraPosition
        lock.unlock()
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {
        target.capturer(capturer, didCapture: frame)

        guard let frameBuffer = frame.buffer as? LKRTCCVPixelBuffer else { return }

        lock.lock()
        let now = ProcessInfo.processInfo.systemUptime
        guard let handler = faceFrameHandler,
              !isAnalysisPending,
              now - lastDeliveryTime >= Self.deliveryInterval else {
            lock.unlock()
            return
        }
        isAnalysisPending = true
        lastDeliveryTime = now
        let currentCameraPosition = cameraPosition
        lock.unlock()

        let payload = WebRTCFaceFramePayload(pixelBuffer: frameBuffer.pixelBuffer)
        analysisQueue.async { [weak self] in
            handler(payload.pixelBuffer, currentCameraPosition)
            guard let self else { return }
            self.lock.lock()
            self.isAnalysisPending = false
            self.lock.unlock()
        }
    }
}

nonisolated private struct WebRTCFaceFramePayload: @unchecked Sendable {
    let pixelBuffer: CVPixelBuffer
}
