import AVFoundation
import UIKit
import XCTest
@preconcurrency import LiveKitWebRTC
@testable import InnoLive

/// Physical camera + microphone only. No account, server, STUN, or public broadcast.
/// Keep the phone unlocked; point at a detailed scene and speak/clap during capture.
@MainActor final class CameraBroadcastRecordingDeviceTests: XCTestCase {
    func testRealCameraMicrophoneSwitchRotationAndRelayRecreation() async throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("This suite requires a real camera and microphone on the assigned iPhone")
        #endif
        try await requireForeground()
        guard await AVCaptureDevice.requestAccess(for: .video), await AVAudioApplication.requestRecordPermission() else {
            return XCTFail("Camera and microphone permission are required")
        }
        // Each of three stages defaults to 10 seconds; 100 gives a five-minute run.
        let requestedStageSeconds = Double(ProcessInfo.processInfo.environment["INNOLIVE_RECORDING_STAGE_SECONDS"] ?? "") ?? 10
        let stageSeconds = requestedStageSeconds.isFinite ? min(600, max(10, requestedStageSeconds)) : 10
        print("PHYSICAL_CAMERA_START device=\(UIDevice.current.model),stageSeconds=\(stageSeconds),appState=\(UIApplication.shared.applicationState.rawValue)")
        let uplink = WebRTCVideoUplink()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("physical-camera-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        // Subscribe before AVCaptureSession.startRunning; startup failures can precede
        // prepareNativeCamera's completion and no longer appear in a preflight-only observer.
        let captureDiagnostics = CameraCaptureNotificationDiagnostics(session: nil)
        defer {
            captureDiagnostics.stop()
            print("PHYSICAL_CAMERA_SESSION_EVENTS \(captureDiagnostics.snapshot)")
        }
        let logDirectory = directory.appendingPathComponent("webrtc-camera-logs")
        try FileManager.default.createDirectory(at: logDirectory, withIntermediateDirectories: true)
        let cameraLogger = LKRTCFileLogger(dirPath: logDirectory.path, maxFileSize: 128 * 1024)
        cameraLogger.severity = .warning
        cameraLogger.start()
        defer {
            cameraLogger.stop()
            if let data = cameraLogger.logData(), let text = String(data: data, encoding: .utf8) {
                let lines = text.split(separator: "\n").filter {
                    let lower = $0.lowercased()
                    return lower.contains("camera") || lower.contains("capture") || lower.contains("startrunning")
                }.prefix(80)
                let filtered = lines.joined(separator: "\n")
                print("PHYSICAL_CAMERA_NATIVE_WARNINGS \(filtered)")
                let attachment = XCTAttachment(string: filtered)
                attachment.name = "Native camera warning and error logs"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        let store = RecordingSessionStore(directory: directory.appendingPathComponent("recordings"))
        let lifecycle = BroadcastLifecycle()
        let sessionID = "local-camera-validation-\(UUID().uuidString)"
        var recorder: LocalBroadcastRecorder?
        let pathAudit = CameraRecordingPathAudit()
        let coordinator = BroadcastRecordingCoordinator(store: store, lifecycle: lifecycle) { sink in
            if let sink {
                recorder = sink
                sink.setValidationVideoAdmission { buffer, rotation, time in
                    pathAudit.recording(buffer, rotation: rotation, time: time)
                }
            }
            uplink.setRecordingSink(sink)
        }
        let preparation = lifecycle.beginPreparation()
        var startedAt = 0.0
        var observedVideoFrames = 0
        var receivedAudioFrames = 0
        var receivedVideoFrames = 0
        var elapsed = 0.0
        var recreatedRelay = false
        let frameObserver = CameraRecordingFrameObserver()
        do {
            try await prepareCapture(uplink)
            lifecycle.finishPreparation(generation: preparation)
            XCTAssertEqual(lifecycle.phase, .prepared)
            uplink.cameraFrameRelay?.setValidationVideoDelivery { buffer, rotation, time in
                pathAudit.delivered(buffer, rotation: rotation, time: time)
            }
            try await CameraRecordingLoopback.withConnectedPeers(uplink) { remote in
                uplink.applyBroadcastOrientationLock(.portrait)
                uplink.localVideoTrack?.add(frameObserver)
                defer { uplink.localVideoTrack?.remove(frameObserver) }
                try await self.requireVideoPreflight(uplink: uplink, diagnostics: captureDiagnostics, local: frameObserver, remote: remote, stage: "initial")
                let generation = try XCTUnwrap(lifecycle.beginCountdown())
                lifecycle.advanceCountdown(generation: generation)
                lifecycle.advanceCountdown(generation: generation)
                XCTAssertEqual(lifecycle.phase, .countdown(1))
                XCTAssertTrue(lifecycle.beginStarting(generation: generation))
                startedAt = ProcessInfo.processInfo.systemUptime
                coordinator.arm(sessionID: sessionID)
                XCTAssertNotNil(recorder)
                XCTAssertEqual(lifecycle.recording, .recording)
                // Local authoritative-state fixture; no settings API or public server is involved.
                lifecycle.reconcileTargets(["local-loopback"], generation: generation)
                coordinator.reconcile(targets: ["local-loopback"], sessionID: sessionID)
                XCTAssertEqual(lifecycle.phase, .live)
                try await self.captureStage("portrait", seconds: stageSeconds, local: frameObserver, remote: remote, recorder: try XCTUnwrap(recorder))
                let current = try XCTUnwrap(uplink.activeCameraID)
                let currentDevice = try XCTUnwrap(AVCaptureDevice(uniqueID: current))
                let other = try XCTUnwrap(LKRTCCameraVideoCapturer.captureDevices().first {
                    $0.position != currentDevice.position && $0.position != .unspecified
                })
                try await uplink.switchCamera(to: other.uniqueID)
                XCTAssertEqual(uplink.activeCameraID, other.uniqueID)
                uplink.applyBroadcastOrientationLock(.landscapeLeft)
                try await self.captureStage("switched-landscape", seconds: stageSeconds, local: frameObserver, remote: remote, recorder: try XCTUnwrap(recorder))
                receivedAudioFrames += remote.renderer.snapshot.frames
                receivedVideoFrames += remote.videoRenderer.count
                XCTAssertGreaterThan(remote.videoRenderer.count, Int(stageSeconds * 10),
                                     "The remote peer must decode transmitted physical-camera frames")
                observedVideoFrames = frameObserver.count
            }
            let originalRelay = try XCTUnwrap(uplink.cameraFrameRelay)
            await uplink.stopAndWait()
            XCTAssertTrue(uplink.recordingSink === recorder, "Recording owner must survive media teardown")
            // Exercise the same production camera construction path used after reconnect.
            // This is media recreation evidence; it does not claim real server reconnect coverage.
            // prepareNativeCamera starts capture before returning the new relay. Account for
            // that observable instrumentation window separately from actual input mismatches.
            pathAudit.beginRelayInstallation()
            try await prepareCapture(uplink)
            uplink.cameraFrameRelay?.setValidationVideoDelivery { buffer, rotation, time in
                pathAudit.delivered(buffer, rotation: rotation, time: time)
            }
            recreatedRelay = uplink.cameraFrameRelay !== originalRelay
            XCTAssertTrue(recreatedRelay)
            XCTAssertTrue(uplink.recordingSink === recorder)
            try await CameraRecordingLoopback.withConnectedPeers(uplink) { remote in
                uplink.localVideoTrack?.add(frameObserver)
                defer { uplink.localVideoTrack?.remove(frameObserver) }
                try await self.requireVideoPreflight(uplink: uplink, diagnostics: captureDiagnostics, local: frameObserver, remote: remote, stage: "recreated")
                try await self.captureStage("recreated", seconds: stageSeconds, local: frameObserver, remote: remote, recorder: try XCTUnwrap(recorder))
                receivedAudioFrames += remote.renderer.snapshot.frames
                receivedVideoFrames += remote.videoRenderer.count
                XCTAssertGreaterThan(remote.videoRenderer.count, Int(stageSeconds * 10),
                                     "The remote peer must decode transmitted physical-camera frames")
                // Finish recording while both peer connections still keep microphone capture
                // alive. withConnectedPeers closes them when this body returns.
                elapsed = ProcessInfo.processInfo.systemUptime - startedAt
                XCTAssertTrue(lifecycle.beginEnding())
                lifecycle.finishEnding(remainingTargets: [], generation: lifecycle.generation)
                coordinator.reconcile(targets: [], sessionID: sessionID)
                XCTAssertNil(uplink.recordingSink)
            }
            await uplink.stopAndWait()
            await coordinator.waitForPendingWork()
            let result = try XCTUnwrap(store.recordings.first)
            let url = store.fileURL(for: result)
            XCTAssertEqual(store.state, .saved(result))
            XCTAssertEqual(lifecycle.phase, .result)
            XCTAssertEqual(lifecycle.recording, .saved(url))
            XCTAssertNil(coordinator.notice)
            let reloaded = RecordingSessionStore(directory: directory.appendingPathComponent("recordings"))
            await reloaded.reloadLibrary()
            XCTAssertEqual(reloaded.recordings, [result])
            XCTAssertNil(reloaded.libraryError)
            let recordedVideo = try XCTUnwrap(result.videoFrames)
            let recordedAudio = try XCTUnwrap(result.audioFrames)
            let droppedVideo = try XCTUnwrap(result.droppedVideoFrames)
            let paths = pathAudit.snapshot
            XCTAssertEqual(paths.mismatches, 0, "Recorder must admit the same buffer, rotation, and timestamp delivered to WebRTC")
            XCTAssertGreaterThan(paths.matches, 450)
            XCTAssertGreaterThan(recordedVideo, 450, "Real camera relay must supply most 30 fps frames")
            XCTAssertGreaterThan(recordedAudio, 1_200_000)
            XCTAssertGreaterThan(observedVideoFrames, 400)
            XCTAssertGreaterThan(frameObserver.count - observedVideoFrames, 150,
                                 "The recreated camera source must keep producing frames")
            XCTAssertGreaterThan(receivedAudioFrames, 1_200_000)
            XCTAssertEqual(result.duration, elapsed, accuracy: 0.35)
            let metrics = try await inspect(url, expectedDuration: elapsed)
            let imageMetrics = try await decodeVideo(url)
            XCTAssertGreaterThan(imageMetrics.frames, 450)
            XCTAssertGreaterThan(imageMetrics.distinctSamples, 1,
                                 "Camera output must change across real front/back camera and orientation changes")
            let exportMetrics = try await inspectExports(url, directory: directory)
            attachFile(url, name: "real-camera-microphone-switch-recreate.mp4")
            let report = "device=\(UIDevice.current.model),os=\(UIDevice.current.systemVersion),capture=physicalCamera,elapsed=\(elapsed),recordedVideo=\(recordedVideo),recordedAudio=\(recordedAudio),droppedVideo=\(droppedVideo),sourceFrames=\(frameObserver.count),remoteAudio=\(receivedAudioFrames),remoteVideo=\(receivedVideoFrames),stageSeconds=\(stageSeconds),relayRecreated=\(recreatedRelay),sameInputMatches=\(paths.matches),sameInputMismatches=\(paths.mismatches),initialUnauditedAdmissions=\(paths.initialAdmissions),decodedVideo=\(imageMetrics.frames),distinctCameraSamples=\(imageMetrics.distinctSamples),\(metrics),\(exportMetrics),flow=localLifecycleCoordinatorStoreLibraryExport,contentAVSync=manualClapReviewRequired,serverReconnect=notTested,publicBroadcast=notTested"
            print("PHYSICAL_CAMERA_RECORDING \(report)")
            let attachment = XCTAttachment(string: report)
            attachment.name = "Real camera recording metrics"
            attachment.lifetime = .keepAlways
            add(attachment)
        } catch {
            uplink.setRecordingSink(nil)
            await uplink.stopAndWait()
            coordinator.endLocally()
            await coordinator.waitForPendingWork()
            throw error
        }
    }

    private func requireForeground() async throws {
        print("PHYSICAL_CAMERA_WAIT_FOREGROUND state=\(UIApplication.shared.applicationState.rawValue),timeoutSeconds=30")
        let deadline = ProcessInfo.processInfo.systemUptime + 30
        while UIApplication.shared.applicationState != .active,
              ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(250))
        }
        guard UIApplication.shared.applicationState == .active else {
            let message = "Physical camera test requires an unlocked foreground app; timed out after 30 seconds"
            XCTFail(message)
            throw NSError(domain: "CameraRecordingPreflight", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: message])
        }
        print("PHYSICAL_CAMERA_FOREGROUND_READY")
    }

    private func requireVideoPreflight(uplink: WebRTCVideoUplink, diagnostics: CameraCaptureNotificationDiagnostics, local: CameraRecordingFrameObserver,
                                       remote: CameraRecordingPeerDelegate, stage: String) async throws {
        let rawObserver = CameraRecordingFrameObserver()
        let rawTrack = uplink.rawPreviewTrack
        rawTrack?.add(rawObserver)
        defer { rawTrack?.remove(rawObserver) }
        printCaptureDiagnostics(uplink, stage: stage, rawFrames: rawObserver.count, events: diagnostics.snapshot)
        let localStart = local.count
        let remoteStart = remote.videoRenderer.count
        let deadline = ProcessInfo.processInfo.systemUptime + 5
        print("PHYSICAL_CAMERA_PREFLIGHT stage=\(stage),local=\(localStart),remote=\(remoteStart),timeoutSeconds=5")
        while ProcessInfo.processInfo.systemUptime < deadline {
            let localDelta = local.count - localStart
            let remoteDelta = remote.videoRenderer.count - remoteStart
            if UIApplication.shared.applicationState == .active, localDelta >= 3, remoteDelta >= 3 {
                printCaptureDiagnostics(uplink, stage: stage, rawFrames: rawObserver.count, events: diagnostics.snapshot)
                print("PHYSICAL_CAMERA_PREFLIGHT_READY stage=\(stage),localDelta=\(localDelta),remoteDelta=\(remoteDelta)")
                return
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        printCaptureDiagnostics(uplink, stage: stage, rawFrames: rawObserver.count, events: diagnostics.snapshot)
        let message = "Video preflight failed: stage=\(stage),appState=\(UIApplication.shared.applicationState.rawValue),localDelta=\(local.count - localStart),remoteDelta=\(remote.videoRenderer.count - remoteStart)"
        XCTFail(message)
        throw NSError(domain: "CameraRecordingPreflight", code: 2,
                      userInfo: [NSLocalizedDescriptionKey: message])
    }

    private func printCaptureDiagnostics(_ uplink: WebRTCVideoUplink, stage: String, rawFrames: Int, events: String) {
        let session = uplink.cameraCapturer?.captureSession
        let device = uplink.activeCameraID.flatMap { AVCaptureDevice(uniqueID: $0) }
        let format = device.map { CMVideoFormatDescriptionGetDimensions($0.activeFormat.formatDescription) }
        let minDuration = device.map { CMTimeGetSeconds($0.activeVideoMinFrameDuration) } ?? 0
        let maxDuration = device.map { CMTimeGetSeconds($0.activeVideoMaxFrameDuration) } ?? 0
        let dimensions = format.map { "\($0.width)x\($0.height)" } ?? "none"
        let sessionType = session.map { String(describing: type(of: $0)) } ?? "none"
        let formatMultiCam = device?.activeFormat.isMultiCamSupported ?? false
        let subtype = device.map { CMFormatDescriptionGetMediaSubType($0.activeFormat.formatDescription) } ?? 0
        let ranges = device?.activeFormat.videoSupportedFrameRateRanges.map { "\($0.minFrameRate)...\($0.maxFrameRate)" }.joined(separator: ";") ?? "none"
        print("PHYSICAL_CAMERA_DIAGNOSTICS stage=\(stage),sessionType=\(sessionType),deviceMultiCamSupported=\(AVCaptureMultiCamSession.isMultiCamSupported),formatMultiCamSupported=\(formatMultiCam),formatSubtype=\(subtype),running=\(session?.isRunning ?? false),interrupted=\(session?.isInterrupted ?? false),inputs=\(session?.inputs.count ?? 0),outputs=\(session?.outputs.count ?? 0),position=\(device?.position.rawValue ?? -1),format=\(dimensions),frameDuration=\(minDuration)...\(maxDuration),supportedFPS=\(ranges),rawPreview=\(rawFrames),localEnabled=\(uplink.localVideoTrack?.isEnabled ?? false),rawEnabled=\(uplink.rawPreviewTrack?.isEnabled ?? false),relay={\(uplink.cameraFrameRelay?.validationCaptureDiagnostics ?? "none")},events={\(events)}")
    }

    private func captureStage(_ stage: String, seconds: Double, local: CameraRecordingFrameObserver,
                              remote: CameraRecordingPeerDelegate, recorder: LocalBroadcastRecorder) async throws {
        let start = ProcessInfo.processInfo.systemUptime
        let localStart = local.count
        let remoteStart = remote.videoRenderer.count
        let droppedStart = recorder.validationDroppedVideoFrames
        print("PHYSICAL_CAMERA_STAGE_START stage=\(stage),seconds=\(seconds),local=\(localStart),remote=\(remoteStart),droppedTotal=\(droppedStart)")
        while ProcessInfo.processInfo.systemUptime - start < seconds {
            let remaining = seconds - (ProcessInfo.processInfo.systemUptime - start)
            if remaining <= 0 { break }
            try await Task.sleep(for: .seconds(min(10, remaining)))
            print("PHYSICAL_CAMERA_STAGE_PROGRESS stage=\(stage),elapsed=\(ProcessInfo.processInfo.systemUptime - start),localDelta=\(local.count - localStart),remoteDelta=\(remote.videoRenderer.count - remoteStart),remoteAudio=\(remote.renderer.snapshot.frames),droppedDelta=\(recorder.validationDroppedVideoFrames - droppedStart),droppedTotal=\(recorder.validationDroppedVideoFrames),appState=\(UIApplication.shared.applicationState.rawValue)")
        }
    }

    private func inspectExports(_ url: URL, directory: URL) async throws -> String {
        let exporter = BroadcastRecordingExporter(root: directory.appendingPathComponent("exports"))
        let original = try await exporter.original(url)
        XCTAssertEqual(original.urls, [url], "Original export must reuse the verified library file")
        defer { exporter.release(original) }
        let split = try await exporter.split(url)
        defer { exporter.release(split) }
        XCTAssertEqual(split.urls.count, 2)
        let videoURL = try XCTUnwrap(split.urls.first { $0.pathExtension == "mp4" })
        let audioURL = try XCTUnwrap(split.urls.first { $0.pathExtension == "m4a" })
        let source = AVURLAsset(url: url)
        let sourceVideos = try await source.loadTracks(withMediaType: .video)
        let sourceAudios = try await source.loadTracks(withMediaType: .audio)
        let sourceVideo = try XCTUnwrap(sourceVideos.first)
        let sourceAudio = try XCTUnwrap(sourceAudios.first)
        let videoEnd = CMTimeGetSeconds(try await sourceVideo.load(.timeRange).end)
        let audioEnd = CMTimeGetSeconds(try await sourceAudio.load(.timeRange).end)
        let video = AVURLAsset(url: videoURL)
        let audio = AVURLAsset(url: audioURL)
        let videoTracks = try await video.loadTracks(withMediaType: .video)
        let videoAudioTracks = try await video.loadTracks(withMediaType: .audio)
        let audioTracks = try await audio.loadTracks(withMediaType: .audio)
        let audioVideoTracks = try await audio.loadTracks(withMediaType: .video)
        XCTAssertEqual(videoTracks.count, 1); XCTAssertTrue(videoAudioTracks.isEmpty)
        XCTAssertEqual(audioTracks.count, 1); XCTAssertTrue(audioVideoTracks.isEmpty)
        let videoDuration = CMTimeGetSeconds(try await video.load(.duration))
        let audioDuration = CMTimeGetSeconds(try await audio.load(.duration))
        XCTAssertEqual(videoDuration, videoEnd, accuracy: 0.15)
        XCTAssertEqual(audioDuration, audioEnd, accuracy: 0.15)
        let videoTrack = try XCTUnwrap(videoTracks.first)
        let audioTrack = try XCTUnwrap(audioTracks.first)
        let videoFormats = try await videoTrack.load(.formatDescriptions)
        let audioFormats = try await audioTrack.load(.formatDescriptions)
        XCTAssertEqual(CMFormatDescriptionGetMediaSubType(try XCTUnwrap(videoFormats.first)), kCMVideoCodecType_H264)
        XCTAssertEqual(CMFormatDescriptionGetMediaSubType(try XCTUnwrap(audioFormats.first)), kAudioFormatMPEG4AAC)
        let decodedVideo = try await decodeVideo(videoURL)
        let decodedAudio = try decodeAudio(asset: audio, track: audioTrack)
        XCTAssertGreaterThan(decodedVideo.frames, 450)
        XCTAssertGreaterThan(decodedAudio.frames, 1_200_000)
        XCTAssertTrue(decodedAudio.allFinite)
        XCTAssertGreaterThan(decodedAudio.peak, 0.0001)
        attachFile(videoURL, name: "real-camera-video-only.mp4")
        attachFile(audioURL, name: "real-microphone-audio-only.m4a")
        return "originalURLUnchanged=true,splitVideoDuration=\(videoDuration),sourceVideoEnd=\(videoEnd),splitAudioDuration=\(audioDuration),sourceAudioEnd=\(audioEnd),splitVideoFrames=\(decodedVideo.frames),splitAudioFrames=\(decodedAudio.frames),splitVideoCodec=H264,splitAudioCodec=AAC"
    }

    private func prepareCapture(_ uplink: WebRTCVideoUplink) async throws {
        uplink.isStopping = false
        let generation = uplink.cameraOperationGeneration
        try await uplink.prepareNativeMicrophone(preferredAudioID: nil, operationGeneration: generation)
        try await uplink.prepareNativeCamera(preferredCameraID: nil, preferredVideoQuality: .hd30,
                                             operationGeneration: generation)
        XCTAssertNotNil(uplink.cameraCapturer, "A physical camera capturer is mandatory")
        XCTAssertFalse(uplink.cameraCapturer?.captureSession is AVCaptureMultiCamSession,
                       "A single-camera broadcast must not inherit the SDK shared MultiCam session")
        XCTAssertNil(uplink.fileVideoCapturer, "Fixture/file video is prohibited in this physical-camera test")
    }

    private func decodeVideo(_ url: URL) async throws -> (frames: Int, distinctSamples: Int) {
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var count = 0
        var hashes: Set<UInt64> = []
        var previous = CMTime.invalid
        while let sample = output.copyNextSampleBuffer() {
            let time = CMSampleBufferGetPresentationTimeStamp(sample)
            if previous.isValid { XCTAssertGreaterThan(time, previous) }
            previous = time
            if count % 30 == 0, let pixel = CMSampleBufferGetImageBuffer(sample) {
                CVPixelBufferLockBaseAddress(pixel, .readOnly)
                if let base = CVPixelBufferGetBaseAddress(pixel)?.assumingMemoryBound(to: UInt8.self) {
                    let bytes = CVPixelBufferGetDataSize(pixel)
                    var hash: UInt64 = 1469598103934665603
                    for index in stride(from: 0, to: bytes, by: max(1, bytes / 200)) {
                        hash = (hash ^ UInt64(base[index])) &* 1099511628211
                    }
                    hashes.insert(hash)
                }
                CVPixelBufferUnlockBaseAddress(pixel, .readOnly)
            }
            count += 1
        }
        XCTAssertEqual(reader.status, .completed)
        return (count, hashes.count)
    }
    private func inspect(_ url: URL, expectedDuration: Double) async throws -> String {
        let asset = AVURLAsset(url: url)
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        XCTAssertEqual(audioTracks.count, 1)
        XCTAssertEqual(videoTracks.count, 1)
        let audio = try XCTUnwrap(audioTracks.first)
        let video = try XCTUnwrap(videoTracks.first)
        let duration = CMTimeGetSeconds(try await asset.load(.duration))
        XCTAssertEqual(duration, expectedDuration, accuracy: 0.25)
        let audioRange = try await audio.load(.timeRange)
        let videoRange = try await video.load(.timeRange)
        XCTAssertGreaterThanOrEqual(CMTimeGetSeconds(audioRange.start), 0)
        XCTAssertLessThan(CMTimeGetSeconds(audioRange.start), 0.12)
        XCTAssertLessThan(CMTimeGetSeconds(videoRange.start), 0.12)
        XCTAssertEqual(CMTimeGetSeconds(audioRange.end), CMTimeGetSeconds(videoRange.end), accuracy: 0.15,
                       "Real microphone and host-clock video must finish on the same timeline")
        let descriptions = try await audio.load(.formatDescriptions)
        let description = try XCTUnwrap(descriptions.first)
        let asbd = try XCTUnwrap(CMAudioFormatDescriptionGetStreamBasicDescription(description))
        XCTAssertEqual(asbd.pointee.mFormatID, kAudioFormatMPEG4AAC)
        XCTAssertEqual(asbd.pointee.mSampleRate, 48_000)
        XCTAssertEqual(asbd.pointee.mChannelsPerFrame, 2)

        let decoded = try decodeAudio(asset: asset, track: audio)
        XCTAssertGreaterThan(decoded.frames, Int((expectedDuration - 0.25) * 48_000))
        XCTAssertTrue(decoded.allFinite)
        XCTAssertGreaterThan(decoded.peak, 0.0001, "Silent microphone input is a failed validation, not success")
        XCTAssertGreaterThan(decoded.rms, 0.00001, "Decoded AAC must contain real nonzero microphone samples")
        XCTAssertLessThan(decoded.peak, 1.2, "FloatS16 must be divided by32768 before AAC encoding")
        XCTAssertLessThan(decoded.clippedRatio, 0.02, "Unnormalized FloatS16 would hard-clip most samples")
        XCTAssertLessThan(decoded.maxGap, 0.05, "Continuous callback samples must not acquire scheduling gaps")
        return "duration=\(duration),audioStart=\(CMTimeGetSeconds(audioRange.start)),audioEnd=\(CMTimeGetSeconds(audioRange.end)),videoEnd=\(CMTimeGetSeconds(videoRange.end)),frames=\(decoded.frames),peak=\(decoded.peak),rms=\(decoded.rms),clipped=\(decoded.clippedRatio),maxGap=\(decoded.maxGap)"
    }

    private func decodeAudio(asset: AVAsset, track: AVAssetTrack) throws
        -> (frames: Int, peak: Float, rms: Double, clippedRatio: Double, maxGap: Double, allFinite: Bool) {
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMIsFloatKey: true,
            AVLinearPCMBitDepthKey: 32, AVLinearPCMIsNonInterleaved: false,
        ])
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var peak: Float = 0
        var squares: Double = 0, maxGap: Double = 0
        var count = 0, clipped = 0, frames = 0
        var previousEnd: Double?
        var allFinite = true
        while let sample = output.copyNextSampleBuffer(), let block = CMSampleBufferGetDataBuffer(sample) {
            let time = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            if let previousEnd {
                XCTAssertGreaterThanOrEqual(time + 1.0 / 48_000, previousEnd, "AAC timestamps cannot overlap")
                maxGap = max(maxGap, time - previousEnd)
            }
            let sampleFrames = CMSampleBufferGetNumSamples(sample)
            frames += sampleFrames
            previousEnd = time + Double(sampleFrames) / 48_000
            var length = 0
            var pointer: UnsafeMutablePointer<Int8>?
            XCTAssertEqual(CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                totalLengthOut: &length, dataPointerOut: &pointer), noErr)
            if let pointer {
                pointer.withMemoryRebound(to: Float.self, capacity: length / 4) { floats in
                    for index in 0..<(length / 4) {
                        let value = floats[index]
                        allFinite = allFinite && value.isFinite
                        if !value.isFinite { continue }
                        peak = max(peak, abs(value))
                        squares += Double(value) * Double(value)
                        if abs(value) >= 0.99 { clipped += 1 }
                        count += 1
                    }
                }
            }
        }
        XCTAssertEqual(reader.status, .completed, reader.error?.localizedDescription ?? "AAC decode failed")
        return (frames, peak, sqrt(squares / Double(max(1, count))),
                Double(clipped) / Double(max(1, count)), maxGap, allFinite)
    }

    private func attachFile(_ url: URL, name: String) {
        let attachment = XCTAttachment(contentsOfFile: url)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

@MainActor private enum CameraRecordingLoopback {
    static func withConnectedPeers(_ uplink: WebRTCVideoUplink,
        body: (CameraRecordingPeerDelegate) async throws -> Void) async throws {
        let factory = uplink.peerConnectionFactory
        let config = LKRTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceServers = []
        let constraints = LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let outgoing = CameraRecordingPeerDelegate()
        let incoming = CameraRecordingPeerDelegate()
        let sender = try XCTUnwrap(factory.peerConnection(with: config, constraints: constraints, delegate: outgoing))
        let receiver = try XCTUnwrap(factory.peerConnection(with: config, constraints: constraints, delegate: incoming))
        defer { sender.close(); receiver.close() }
        let settings = LKRTCRtpTransceiverInit()
        settings.direction = .sendOnly
        settings.streamIds = ["physical-camera-validation"]
        _ = sender.addTransceiver(with: try XCTUnwrap(uplink.localAudioTrack), init: settings)
        _ = sender.addTransceiver(with: try XCTUnwrap(uplink.localVideoTrack), init: settings)
        let offer = try await description(sender, answer: false, constraints: constraints)
        try await setDescription(sender, description: offer, local: true)
        try await setDescription(receiver, description: offer, local: false)
        let answer = try await description(receiver, answer: true, constraints: constraints)
        try await setDescription(receiver, description: answer, local: true)
        try await setDescription(sender, description: answer, local: false)
        outgoing.forward(to: receiver)
        incoming.forward(to: sender)
        var connected = false
        for _ in 0..<50 {
            if [.connected, .completed].contains(sender.iceConnectionState),
               [.connected, .completed].contains(receiver.iceConnectionState) { connected = true; break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(connected, "Local loopback ICE must connect before recording")
        guard connected else { throw NSError(domain: "CameraRecordingLoopback", code: 1) }
        try await body(incoming)
        withExtendedLifetime((factory, outgoing, incoming)) {}
    }
    private static func description(_ peer: LKRTCPeerConnection, answer: Bool, constraints: LKRTCMediaConstraints) async throws -> LKRTCSessionDescription {
        try await withCheckedThrowingContinuation { continuation in
            let completion: @Sendable (LKRTCSessionDescription?, Error?) -> Void = { description, error in
                if let error { continuation.resume(throwing: error) }
                else if let description { continuation.resume(returning: description) }
                else { continuation.resume(throwing: NSError(domain: "CameraRecordingLoopback", code: 3)) }
            }
            if answer { peer.answer(for: constraints, completionHandler: completion) }
            else { peer.offer(for: constraints, completionHandler: completion) }
        }
    }
    private static func setDescription(_ peer: LKRTCPeerConnection, description: LKRTCSessionDescription, local: Bool) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let completion: @Sendable (Error?) -> Void = { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
            if local { peer.setLocalDescription(description, completionHandler: completion) }
            else { peer.setRemoteDescription(description, completionHandler: completion) }
        }
    }
}

nonisolated private final class CameraRecordingRemoteAudioRenderer: NSObject, LKRTCAudioRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var callbacks = 0, frames = 0
    func render(pcmBuffer: AVAudioPCMBuffer) {
        lock.withLock { callbacks += 1; frames += Int(pcmBuffer.frameLength) }
    }
    var snapshot: (callbacks: Int, frames: Int) { lock.withLock { (callbacks, frames) } }
}
nonisolated private final class CameraRecordingPeerDelegate: NSObject, LKRTCPeerConnectionDelegate, @unchecked Sendable {
    let renderer = CameraRecordingRemoteAudioRenderer()
    let videoRenderer = CameraRecordingFrameObserver()
    private let lock = NSLock()
    private var candidates: [LKRTCIceCandidate] = []
    private var target: LKRTCPeerConnection?
    // RTCAudioTrack deinit removes native sinks: retain remote ObjC wrappers for the test.
    private var remoteAudioTracks: [LKRTCAudioTrack] = []
    private var remoteVideoTracks: [LKRTCVideoTrack] = []
    private func attach(_ track: LKRTCVideoTrack) {
        let isNew = lock.withLock {
            guard !remoteVideoTracks.contains(where: { $0.trackId == track.trackId }) else { return false }
            remoteVideoTracks.append(track)
            return true
        }
        if isNew { track.add(videoRenderer) }
    }
    private func attach(_ track: LKRTCAudioTrack) {
        let isNew = lock.withLock {
            guard !remoteAudioTracks.contains(where: { $0.trackId == track.trackId }) else { return false }
            remoteAudioTracks.append(track)
            return true
        }
        if isNew { track.add(renderer) }
    }
    func forward(to target: LKRTCPeerConnection) {
        let pending = lock.withLock { self.target = target; let values = candidates; candidates = []; return values }
        pending.forEach { target.add($0) { _ in } }
    }
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didGenerate candidate: LKRTCIceCandidate) {
        let target = lock.withLock { if self.target == nil { candidates.append(candidate) }; return self.target }
        target?.add(candidate) { _ in }
    }
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd stream: LKRTCMediaStream) {
        stream.audioTracks.forEach { attach($0) }
        stream.videoTracks.forEach { attach($0) }
    }
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd rtpReceiver: LKRTCRtpReceiver, streams mediaStreams: [LKRTCMediaStream]) {
        let track = rtpReceiver.track
        if let audio = track as? LKRTCAudioTrack { attach(audio) }
        if let video = track as? LKRTCVideoTrack { attach(video) }
    }
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange stateChanged: LKRTCSignalingState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didRemove stream: LKRTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: LKRTCPeerConnection) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange newState: LKRTCIceConnectionState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange newState: LKRTCIceGatheringState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didRemove candidates: [LKRTCIceCandidate]) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didOpen dataChannel: LKRTCDataChannel) {}
}

nonisolated private final class CameraRecordingFrameObserver: NSObject, LKRTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var frames = 0
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: LKRTCVideoFrame?) {
        guard frame != nil else { return }
        lock.withLock { frames += 1 }
    }
    var count: Int { lock.withLock { frames } }
}

/// Bounded metadata-only evidence; retains neither frame buffers nor image content.
nonisolated private final class CameraRecordingPathAudit: @unchecked Sendable {
    private struct Input: Equatable { let identity: ObjectIdentifier; let rotation: Int }
    private let lock = NSLock()
    private var recent: [Int64: Input] = [:]
    private var order: [Int64] = []
    private var matches = 0
    private var mismatches = 0
    private var initialAdmissions = 0
    private var installingRelay = false
    private var firstObservedTime: Int64?
    func beginRelayInstallation() {
        lock.withLock {
            installingRelay = true
            firstObservedTime = nil
        }
    }
    func delivered(_ buffer: CVPixelBuffer, rotation: Int, time: Int64) {
        lock.withLock {
            if installingRelay, firstObservedTime == nil { firstObservedTime = time }
            recent[time] = Input(identity: ObjectIdentifier(buffer), rotation: rotation)
            order.append(time)
            if order.count > 256 { recent.removeValue(forKey: order.removeFirst()) }
        }
    }
    func recording(_ buffer: CVPixelBuffer, rotation: Int, time: Int64) {
        lock.withLock {
            if recent[time] == Input(identity: ObjectIdentifier(buffer), rotation: rotation) { matches += 1 }
            else if installingRelay, firstObservedTime.map({ time < $0 }) ?? true {
                // Only frames captured before this relay's first instrumented delivery.
                initialAdmissions += 1
            } else { mismatches += 1 }
        }
    }
    var snapshot: (matches: Int, mismatches: Int, initialAdmissions: Int) {
        lock.withLock { (matches, mismatches, initialAdmissions) }
    }
}

/// At most eight metadata-only AVCapture events; no captured image or audio content.
nonisolated private final class CameraCaptureNotificationDiagnostics: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [String] = []
    private var tokens: [NSObjectProtocol] = []
    init(session: AVCaptureSession?) {
        // A nil session observes all AVCapture sessions, including startup before the
        // newly constructed production capturer becomes available to this test.
        for name in [AVCaptureSession.runtimeErrorNotification, AVCaptureSession.wasInterruptedNotification,
                     AVCaptureSession.interruptionEndedNotification, AVCaptureSession.didStartRunningNotification,
                     AVCaptureSession.didStopRunningNotification] {
            tokens.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) { [weak self] note in
                let error = note.userInfo?[AVCaptureSessionErrorKey] as? NSError
                let reason = note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber
                let value = "\(note.name.rawValue):error=\(error?.domain ?? "none")/\(error?.code ?? 0),reason=\(reason?.intValue ?? -1)"
                self?.lock.withLock {
                    guard let self, self.events.count < 8 else { return }
                    self.events.append(value)
                }
            })
        }
    }
    var snapshot: String { lock.withLock { events.joined(separator: ";") } }
    func stop() {
        tokens.forEach { NotificationCenter.default.removeObserver($0) }
        tokens.removeAll()
    }
}
