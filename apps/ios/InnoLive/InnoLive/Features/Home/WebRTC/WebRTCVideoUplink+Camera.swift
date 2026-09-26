import AVFoundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func switchCamera(to cameraID: String, resetZoom: Bool = true) async throws {
        guard !isStopping,
              !isSwitchingCamera,
              let capturer = cameraCapturer,
              let previousCameraID = activeCameraID,
              let previousDevice = AVCaptureDevice(uniqueID: previousCameraID),
              let activeVideoQuality,
              let previousSetting = captureSetting(for: previousDevice, quality: activeVideoQuality) else {
            throw WebRTCVideoUplinkError.failed(String(localized: "비식별화를 시작한 뒤 카메라를 전환해 주세요."))
        }
        guard cameraID != previousCameraID else { return }
        guard let newDevice = AVCaptureDevice(uniqueID: cameraID),
              let newSetting = captureSetting(for: newDevice, quality: activeVideoQuality) else {
            throw WebRTCVideoUplinkError.failed(String(localized: "선택한 카메라를 사용할 수 없습니다."))
        }

        cameraOperationGeneration &+= 1
        let operationGeneration = cameraOperationGeneration
        let previousZoom = targetZoomFactor
        setCameraSwitching(true)
        defer {
            if cameraOperationGeneration == operationGeneration {
                setCameraSwitching(false)
            }
        }

        await stopCapture(capturer)
        try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)

        do {
            cameraFrameRelay?.updateCameraPosition(newDevice.position)
            try await startCapture(capturer, device: newDevice, setting: newSetting)
            try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
            activeCameraID = newDevice.uniqueID
            setUsingFrontCamera(newDevice.position == .front)
            if resetZoom {
                resetZoomToDefault()
            } else {
                reapplyTargetZoom()
            }
        } catch {
            guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
                throw WebRTCVideoUplinkError.cancelled
            }
            await stopCapture(capturer)
            try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
            do {
                cameraFrameRelay?.updateCameraPosition(previousDevice.position)
                try await startCapture(capturer, device: previousDevice, setting: previousSetting)
                try ensureCurrentCameraOperation(operationGeneration, capturer: capturer)
                applyZoom(previousZoom, cameraID: previousCameraID, waitUntilApplied: true)
            } catch {
                guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
                    throw WebRTCVideoUplinkError.cancelled
                }
                fail(String(localized: "카메라 연결을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요."))
                throw WebRTCVideoUplinkError.failed(String(localized: "카메라 연결을 복구하지 못했습니다."))
            }
            activeCameraID = previousDevice.uniqueID
            setUsingFrontCamera(previousDevice.position == .front)
            throw WebRTCVideoUplinkError.failed(String(localized: "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다."))
        }
    }

    func prepareNativeCamera(
        preferredCameraID: String?,
        preferredVideoQuality: CameraQualityPreset,
        operationGeneration: UInt
    ) async throws {
        #if DEBUG
        #if targetEnvironment(simulator)
        if SimulatorVideoInput.isEnabled {
            guard SimulatorVideoInput.bundledURL != nil else {
                throw WebRTCVideoUplinkError.failed(
                    String(localized: "번들 시뮬레이터 영상을 찾지 못했습니다.")
                )
            }

            setUsingFrontCamera(false)
            let source = peerConnectionFactory.videoSource()
            adapt(source, to: preferredVideoQuality)
            let frameRelay = makeFrameRelay(target: source, cameraPosition: .back)
            let capturer = LKRTCFileVideoCapturer(delegate: frameRelay)
            let track = peerConnectionFactory.videoTrack(with: source, trackId: "innolive-camera")
            track.isEnabled = true
            videoSource = source
            fileVideoCapturer = capturer
            cameraCapturer = nil
            cameraFrameRelay = frameRelay
            localVideoTrack = track
            if let localRenderer {
                renderingLocalTrack?.add(localRenderer)
            }
            if let faceRegistrationRenderer {
                renderingLocalTrack?.add(faceRegistrationRenderer)
            }

            capturer.startCapturing(fromFileNamed: SimulatorVideoInput.fileName) { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self,
                          self.cameraOperationGeneration == operationGeneration,
                          !self.isStopping else { return }
                    self.fail(
                        String(localized: "시뮬레이터 영상을 읽지 못했습니다: \(error.localizedDescription)")
                    )
                }
            }
            guard !isStopping,
                  cameraOperationGeneration == operationGeneration,
                  fileVideoCapturer === capturer else {
                capturer.stopCapture()
                throw WebRTCVideoUplinkError.cancelled
            }
            activeVideoQuality = preferredVideoQuality
            return
        }
        #endif
        #endif

        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
            markMediaPermissionRequired()
            throw WebRTCVideoUplinkError.failed(String(localized: "카메라 권한이 필요합니다."))
        }

        let availableDevices = LKRTCCameraVideoCapturer.captureDevices()
        let selectedDevice = preferredCameraID.flatMap(CameraDeviceCatalog.resolvedDevice(for:))
            ?? availableDevices.first { $0.position == .front }
            ?? CameraDeviceCatalog.devices.first { $0.position == .front }
            ?? availableDevices.first
            ?? CameraDeviceCatalog.devices.first

        guard let selectedDevice else {
            throw WebRTCVideoUplinkError.failed(String(localized: "사용할 수 있는 카메라를 찾지 못했습니다."))
        }
        guard let captureSetting = captureSetting(
            for: selectedDevice,
            quality: preferredVideoQuality
        ) else {
            throw WebRTCVideoUplinkError.failed(String(localized: "선택한 카메라의 영상 형식을 준비하지 못했습니다."))
        }

        setUsingFrontCamera(selectedDevice.position == .front)
        let source = peerConnectionFactory.videoSource()
        adapt(source, to: preferredVideoQuality)
        let frameRelay = makeFrameRelay(target: source, cameraPosition: selectedDevice.position)
        if let lockedBroadcastOrientation {
            frameRelay.setLockedInterfaceOrientation(lockedBroadcastOrientation)
        }
        let capturer = LKRTCCameraVideoCapturer(delegate: frameRelay)
        let track = peerConnectionFactory.videoTrack(with: source, trackId: "innolive-camera")
        track.isEnabled = true
        videoSource = source
        cameraCapturer = capturer
        cameraFrameRelay = frameRelay
        localVideoTrack = track
        if let localRenderer {
            renderingLocalTrack?.add(localRenderer)
        }
        if let faceRegistrationRenderer {
            renderingLocalTrack?.add(faceRegistrationRenderer)
        }

        try await startCapture(capturer, device: selectedDevice, setting: captureSetting)
        guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
            throw WebRTCVideoUplinkError.cancelled
        }
        activeCameraID = selectedDevice.uniqueID
        activeVideoQuality = preferredVideoQuality
        reapplyTargetZoom()
    }

    private func makeFrameRelay(target: LKRTCVideoSource, cameraPosition: AVCaptureDevice.Position) -> WebRTCCameraFrameRelay {
        let relayID = UUID()
        frameRelayID = relayID
        let local = credentials?.processingMode == .onDevice
        let preview = peerConnectionFactory.videoSource()
        rawPreviewSource = preview
        rawPreviewTrack = peerConnectionFactory.videoTrack(with: preview, trackId: "innolive-local-preview-only")
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: cameraPosition,
                                          processingMode: credentials?.processingMode ?? .server,
                                          previewTarget: preview) { [weak self] message in
            Task { @MainActor [weak self] in
                guard let self, !self.isStopping, self.frameRelayID == relayID,
                      self.credentials?.processingMode == .onDevice else { return }
                self.fail(message)
            }
        }
        if local { relay.setLocalAnonymizationEnabled(credentials?.localAnonymizationEnabled ?? true) }
        relay.setColor(
            warmth: videoQualitySettings.warmth,
            saturation: videoQualitySettings.saturation
        )
        relay.setUnprocessedPreviewHandler(unprocessedPreviewHandler)
        return relay
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
            throw WebRTCVideoUplinkError.failed(String(localized: "비식별화를 시작한 뒤 화질을 변경해 주세요."))
        }
        guard quality != previousQuality else { return }
        guard let newSetting = captureSetting(for: device, quality: quality) else {
            throw WebRTCVideoUplinkError.failed(String(localized: "현재 카메라가 선택한 화질을 지원하지 않습니다."))
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
            reapplyTargetZoom()
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
                reapplyTargetZoom()
            } catch {
                guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
                    throw WebRTCVideoUplinkError.cancelled
                }
                fail(String(localized: "카메라 화질을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요."))
                throw WebRTCVideoUplinkError.failed(String(localized: "카메라 화질을 복구하지 못했습니다."))
            }
            throw WebRTCVideoUplinkError.failed(String(localized: "화질을 변경하지 못해 기존 화질을 계속 사용합니다."))
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
        cameraFrameRelay?.updateCameraPosition(device.position)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            capturer.startCapture(with: device, format: setting.format, fps: setting.fps) { error in
                if let error {
                    continuation.resume(
                        throwing: WebRTCVideoUplinkError.failed(
                            String(localized: "선택한 카메라를 열지 못했습니다: \(error.localizedDescription)")
                        )
                    )
                } else {
                    continuation.resume()
                }
            }
        }
        applyCaptureAdjustments(capturer: capturer, device: device)
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

    private func applyCaptureAdjustments(
        capturer: LKRTCCameraVideoCapturer,
        device: AVCaptureDevice
    ) {
        applyExposureToCamera(device.uniqueID)
        applyStabilization(to: capturer, device: device)
    }

    func applyStabilization(to capturer: LKRTCCameraVideoCapturer, device: AVCaptureDevice) {
        stabilizationObservation = nil
        let session = capturer.captureSession
        let connection = videoCaptureConnection(in: session)
        let requested = requestedStabilizationMode(for: device, connection: connection)
        if let connection, connection.isVideoStabilizationSupported {
            session.beginConfiguration()
            connection.preferredVideoStabilizationMode = requested ?? .off
            session.commitConfiguration()
        }
        publishStabilizationStatus(for: device, connection: connection)
        guard let connection else { return }
        stabilizationObservation = connection.observe(\.activeVideoStabilizationMode, options: [.new]) {
            [weak self] connection, _ in
            Task { @MainActor [weak self] in
                guard let self, self.cameraCapturer != nil else { return }
                self.publishStabilizationStatus(for: device, connection: connection)
            }
        }
    }

    private func requestedStabilizationMode(
        for device: AVCaptureDevice,
        connection: AVCaptureConnection?
    ) -> AVCaptureVideoStabilizationMode? {
        guard connection?.isVideoStabilizationSupported == true else { return nil }
        let format = device.activeFormat
        let supportsLowLatency: Bool
        if #available(iOS 26, *) {
            supportsLowLatency = format.isVideoStabilizationModeSupported(.lowLatency)
        } else {
            supportsLowLatency = false
        }
        return VideoQualityCapturePolicy.stabilizationMode(
            enabled: videoQualitySettings.stabilizationEnabled,
            supportsLowLatency: supportsLowLatency,
            supportsStandard: format.isVideoStabilizationModeSupported(.standard)
        )
    }

    private func publishStabilizationStatus(
        for device: AVCaptureDevice,
        connection: AVCaptureConnection?
    ) {
        updateStabilizationStatus(VideoQualityCapturePolicy.stabilizationStatus(
            enabled: videoQualitySettings.stabilizationEnabled,
            requestedMode: requestedStabilizationMode(for: device, connection: connection),
            activeMode: connection?.activeVideoStabilizationMode ?? .off
        ))
    }

    private func videoCaptureConnection(in session: AVCaptureSession) -> AVCaptureConnection? {
        session.outputs.compactMap { $0.connection(with: .video) }.first
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
    private let previewTarget: LKRTCVideoCapturerDelegate?
    private let processor: PrivacyUplinkProcessor
    private let route: PrivacyUplinkRoute
    private let analysisQueue = DispatchQueue(label: "com.innolive.webrtc.face-detection")
    private let lock = NSLock()
    private let previewColorProcessor = VideoColorFrameProcessor()
    private let uplinkColorProcessor = VideoColorFrameProcessor()
    private var faceFrameHandler: FaceFrameHandler?
    private var cameraPosition: AVCaptureDevice.Position
    private var lockedInterfaceOrientation: BroadcastInterfaceOrientation?
    private var warmth: Float = 0
    private var saturation: Float = 1
    private var isAnalysisPending = false
    private var lastDeliveryTime: TimeInterval = 0
    private var unprocessedPreviewHandler: (@Sendable (CVPixelBuffer, Int) -> Void)?

    init(target: LKRTCVideoCapturerDelegate, cameraPosition: AVCaptureDevice.Position,
         processingMode: AIProcessingMode = .server, previewTarget: LKRTCVideoCapturerDelegate? = nil,
         onError: @escaping @Sendable (String) -> Void = { _ in }) {
        self.target = target
        self.previewTarget = previewTarget
        processor = PrivacyUplinkProcessor(onError: onError)
        route = PrivacyUplinkRoute(mode: processingMode)
        self.cameraPosition = cameraPosition
        super.init()
    }

    func setFaceFrameHandler(
        _ handler: FaceFrameHandler?,
        cameraPosition: AVCaptureDevice.Position
    ) {
        lock.lock()
        faceFrameHandler = handler
        if handler != nil { self.cameraPosition = cameraPosition }
        lastDeliveryTime = 0
        lock.unlock()
    }

    func setColor(warmth: Float, saturation: Float) {
        let normalizedWarmth = min(max(warmth.isFinite ? warmth : 0, -1), 1)
        let normalizedSaturation = min(max(saturation.isFinite ? saturation : 1, 0), 2)
        lock.lock()
        self.warmth = normalizedWarmth
        self.saturation = normalizedSaturation
        lock.unlock()
    }

    func setUnprocessedPreviewHandler(_ handler: (@Sendable (CVPixelBuffer, Int) -> Void)?) {
        lock.lock()
        unprocessedPreviewHandler = handler
        lock.unlock()
    }

    func updateCameraPosition(_ cameraPosition: AVCaptureDevice.Position) {
        processor.reset()
        lock.lock()
        self.cameraPosition = cameraPosition
        lock.unlock()
    }

    func setLockedInterfaceOrientation(_ orientation: BroadcastInterfaceOrientation?) {
        processor.reset()
        lock.lock()
        lockedInterfaceOrientation = orientation
        lock.unlock()
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {
        lock.lock()
        let lockedOrientation = lockedInterfaceOrientation
        let currentCameraPosition = cameraPosition
        let warmth = warmth
        let saturation = saturation
        lock.unlock()

        let outgoing = outgoingFrame(
            from: frame,
            lockedOrientation: lockedOrientation,
            cameraPosition: currentCameraPosition
        )
        deliverUnprocessedPreview(outgoing)
        let coloredPreview: LKRTCVideoFrame
        do {
            coloredPreview = try previewColorProcessor.process(
                outgoing,
                warmth: warmth,
                saturation: saturation
            )
        } catch {
            return
        }
        previewTarget?.capturer(capturer, didCapture: coloredPreview)
        if let ticket = route.ticket() {
            if ticket.mode == .onDevice {
                let colorProcessor = uplinkColorProcessor
                processor.submit(outgoing) { [target, route, colorProcessor] result in
                    let coloredUplink: LKRTCVideoFrame
                    do {
                        coloredUplink = try colorProcessor.process(
                            result,
                            warmth: warmth,
                            saturation: saturation
                        )
                    } catch {
                        return
                    }
                    route.deliver(ticket) { target.capturer(capturer, didCapture: coloredUplink) }
                }
            } else {
                route.deliver(ticket) { target.capturer(capturer, didCapture: coloredPreview) }
            }
        }

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

    func setLocalAnonymizationEnabled(_ enabled: Bool) { processor.setEnabled(enabled) }
    func prepareLocalProcessing() async throws { try await processor.prepare() }
    func setProcessingMode(_ mode: AIProcessingMode) {
        // Start protected before publishing the new route. Invalidate previous local work first.
        processor.setEnabled(true)
        route.change(to: mode)
    }
    func stopProcessing() { route.stop(); processor.stop() }

    private func outgoingFrame(
        from frame: LKRTCVideoFrame,
        lockedOrientation: BroadcastInterfaceOrientation?,
        cameraPosition: AVCaptureDevice.Position
    ) -> LKRTCVideoFrame {
        guard let lockedOrientation else { return frame }
        let rotation = BroadcastOrientationPolicy.videoRotation(
            interfaceOrientation: lockedOrientation,
            cameraPosition: cameraPosition
        )
        let liveKitRotation = LKRTCVideoRotation(rawValue: rotation.rawValue) ?? frame.rotation
        guard frame.rotation != liveKitRotation else { return frame }
        let outgoing = LKRTCVideoFrame(
            buffer: frame.buffer,
            rotation: liveKitRotation,
            timeStampNs: frame.timeStampNs
        )
        outgoing.timeStamp = frame.timeStamp
        return outgoing
    }

    private func deliverUnprocessedPreview(_ frame: LKRTCVideoFrame) {
        lock.lock()
        let handler = unprocessedPreviewHandler
        lock.unlock()
        guard let handler else { return }
        guard let buffer = (frame.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer else { return }
        handler(buffer, frame.rotation.rawValue)
    }
}

nonisolated private struct WebRTCFaceFramePayload: @unchecked Sendable {
    let pixelBuffer: CVPixelBuffer
}
