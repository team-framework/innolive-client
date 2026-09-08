import AVFoundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func switchCamera(to cameraID: String) async throws {
        guard case .camera = activeMediaSource,
              let cameraSource else {
            throw WebRTCVideoUplinkError.failed("파일 영상 사용 중에는 카메라를 전환할 수 없습니다.")
        }
        guard !isStopping, !isSwitchingCamera else {
            throw WebRTCVideoUplinkError.cancelled
        }

        cameraOperationGeneration &+= 1
        let operationGeneration = cameraOperationGeneration
        setCameraSwitching(true)
        defer {
            if cameraOperationGeneration == operationGeneration {
                setCameraSwitching(false)
            }
        }
        do {
            try await cameraSource.switchCamera(to: cameraID)
            try ensureCurrentCameraOperation(operationGeneration, capturer: cameraSource.capturer)
        } catch {
            guard isCurrentCameraOperation(operationGeneration, source: cameraSource) else {
                throw WebRTCVideoUplinkError.cancelled
            }
            let message = (error as? LocalizedError)?.errorDescription
                ?? "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다."
            if let mediaError = error as? MediaSourceError,
               mediaError == .cameraRecoveryFailed {
                fail(message)
            }
            throw error
        }
        activeCameraID = cameraSource.currentCameraID
        setUsingFrontCamera(cameraSource.currentCameraPosition == .front)
    }

    func switchVideoQuality(to quality: CameraQualityPreset) async throws {
        guard case .camera = activeMediaSource,
              let cameraSource else {
            throw WebRTCVideoUplinkError.failed("파일 영상 사용 중에는 카메라 화질을 변경할 수 없습니다.")
        }
        guard !isStopping, !isSwitchingCamera else {
            throw WebRTCVideoUplinkError.cancelled
        }

        cameraOperationGeneration &+= 1
        let operationGeneration = cameraOperationGeneration
        setCameraSwitching(true)
        defer {
            if cameraOperationGeneration == operationGeneration {
                setCameraSwitching(false)
            }
        }
        do {
            try await cameraSource.switchQuality(to: quality)
            try ensureCurrentCameraOperation(operationGeneration, capturer: cameraSource.capturer)
        } catch {
            guard isCurrentCameraOperation(operationGeneration, source: cameraSource) else {
                throw WebRTCVideoUplinkError.cancelled
            }
            let message = (error as? LocalizedError)?.errorDescription
                ?? "화질을 변경하지 못해 기존 화질을 계속 사용합니다."
            if let mediaError = error as? MediaSourceError,
               mediaError == .cameraRecoveryFailed {
                fail(message)
            }
            throw error
        }
        activeVideoQuality = cameraSource.currentQuality
        if let source = videoSource {
            adapt(source, to: quality)
        }
    }

    @discardableResult
    func startFaceFrameDelivery(
        handler: @escaping WebRTCCameraFrameRelay.FaceFrameHandler
    ) -> Bool {
        guard case .camera = activeMediaSource,
              let cameraSource else { return false }
        return cameraSource.startFaceFrameDelivery(handler: handler)
    }

    func stopFaceFrameDelivery() {
        cameraSource?.stopFaceFrameDelivery()
    }

    func ensureCurrentCameraOperation(
        _ generation: UInt,
        capturer: LKRTCCameraVideoCapturer? = nil
    ) throws {
        guard !isStopping, cameraOperationGeneration == generation else {
            throw WebRTCVideoUplinkError.cancelled
        }
        if let capturer, cameraSource?.capturer !== capturer {
            throw WebRTCVideoUplinkError.cancelled
        }
    }

    func isCurrentCameraOperation(_ generation: UInt, source: CameraSource) -> Bool {
        !isStopping
            && cameraOperationGeneration == generation
            && source === self.cameraSource
    }

    func ensureCurrentMediaOperation(_ generation: UInt) throws {
        guard !isStopping, cameraOperationGeneration == generation else {
            throw WebRTCVideoUplinkError.cancelled
        }
    }

    func adapt(_ source: LKRTCVideoSource, to quality: CameraQualityPreset) {
        source.adaptOutputFormat(
            toWidth: quality.width,
            height: quality.height,
            fps: Int32(quality.framesPerSecond)
        )
    }
}
