import AVFoundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func prepareNativeMediaSource(
        preferredCameraID: String?,
        preferredVideoQuality: CameraQualityPreset,
        operationGeneration: UInt
    ) async throws {
        let sourceSelection = MediaSourceDebugConfiguration.selection()
        let source = peerConnectionFactory.videoSource()
        adapt(source, to: preferredVideoQuality)
        let consumer = WebRTCVideoFrameConsumer(target: source)

        let mediaSource: MediaSource
        switch sourceSelection {
        case .camera:
            let cameraSource: CameraSource
            do {
                cameraSource = try CameraSource(
                    preferredCameraID: preferredCameraID,
                    quality: preferredVideoQuality,
                    consumer: consumer
                )
            } catch let error as MediaSourceError {
                if error == .cameraPermissionRequired {
                    markMediaPermissionRequired()
                }
                throw error
            }
            mediaSource = cameraSource
            self.cameraSource = cameraSource
            fileSource = nil
            activeCameraID = cameraSource.currentCameraID
            setUsingFrontCamera(cameraSource.currentCameraPosition == .front)
        case let .file(url):
            let fileSource = FileSource(
                url: url,
                consumer: consumer,
                errorHandler: { [weak self] error in
                    Task { @MainActor [weak self] in
                        guard let self,
                              self.cameraOperationGeneration == operationGeneration else { return }
                        self.fail(
                            (error as? LocalizedError)?.errorDescription
                                ?? "파일 영상을 읽지 못했습니다."
                        )
                    }
                }
            )
            mediaSource = fileSource
            cameraSource = nil
            self.fileSource = fileSource
            activeCameraID = nil
            setUsingFrontCamera(false)
        }

        videoSource = source
        videoFrameConsumer = consumer
        self.mediaSource = mediaSource
        activeMediaSource = mediaSource.kind
        activeVideoQuality = preferredVideoQuality

        let track = peerConnectionFactory.videoTrack(with: source, trackId: "innolive-camera")
        track.isEnabled = true
        localVideoTrack = track
        if let localRenderer {
            track.add(localRenderer)
        }
        if let faceRegistrationRenderer {
            track.add(faceRegistrationRenderer)
        }

        do {
            try await mediaSource.start()
            try ensureCurrentMediaOperation(operationGeneration)
        } catch {
            await mediaSource.stop()
            throw error
        }
    }
}
