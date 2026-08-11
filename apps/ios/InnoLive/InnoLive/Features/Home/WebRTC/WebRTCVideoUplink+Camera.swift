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
        let capturer = LKRTCCameraVideoCapturer(delegate: source)
        let track = peerConnectionFactory.videoTrack(with: source, trackId: "innolive-camera")
        track.isEnabled = true
        videoSource = source
        cameraCapturer = capturer
        localVideoTrack = track
        if let localRenderer {
            track.add(localRenderer)
        }

        try await startCapture(capturer, device: selectedDevice, setting: captureSetting)
        guard isCurrentCameraOperation(operationGeneration, capturer: capturer) else {
            throw WebRTCVideoUplinkError.cancelled
        }
        activeCameraID = selectedDevice.uniqueID
        activeVideoQuality = preferredVideoQuality
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
