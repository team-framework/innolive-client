import AVFoundation
import UIKit
import XCTest
@preconcurrency import LiveKitWebRTC
@testable import InnoLive

/// Explicit physical-device suite: actual microphone -> WebRTC APM -> production tap -> AAC MP4.
/// Video is synthetic to isolate the audio bridge. No synthetic audio is supplied anywhere.
@MainActor
final class WebRTCRecordingDeviceTests: XCTestCase {
    func testProductionTapRecordsRealMicrophoneAndDetachesBetweenRecorders() async throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Run this microphone integration test on the assigned physical iPhone")
        #endif
        guard await AVAudioApplication.requestRecordPermission() else {
            return XCTFail("Microphone permission is required; no silent success is accepted")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("recording-mic-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        try await RecordingMicrophoneLoopback.withConnectedPeers { tap, receiver in
            let firstURL = directory.appendingPathComponent("microphone-eight-seconds.mp4")
            let first = try await self.capture(tap: tap, url: firstURL, seconds: 8)
            XCTAssertGreaterThan(first.videoFrames, 150)
            XCTAssertGreaterThan(first.audioFrames, 350_000, "Production PCM tap must capture nearly eight seconds")
            let firstReport = try await self.inspect(firstURL, expectedDuration: 8)
            let firstBytes = try Data(contentsOf: firstURL)
            self.attachFile(firstURL, name: "physical-microphone-eight-seconds.mp4")

            // A detached sink must not append into the old file while RTP capture continues.
            try await Task.sleep(for: .milliseconds(500))
            XCTAssertEqual(try Data(contentsOf: firstURL), firstBytes)

            // Same production tap, fresh recorder and epoch: old callbacks cannot target the new recording.
            let secondURL = directory.appendingPathComponent("microphone-new-generation.mp4")
            let second = try await self.capture(tap: tap, url: secondURL, seconds: 1.5)
            XCTAssertGreaterThan(second.audioFrames, 60_000)
            let secondReport = try await self.inspect(secondURL, expectedDuration: 1.5)
            XCTAssertEqual(try Data(contentsOf: firstURL), firstBytes,
                           "Replacing the recording sink must never mutate finalized output")
            XCTAssertGreaterThan(receiver.renderer.snapshot.frames, 350_000,
                                 "The same real microphone must also reach the loopback peer")
            self.attachFile(secondURL, name: "physical-microphone-new-generation.mp4")
            let report = "first={\(firstReport)} newRecorder={\(secondReport)} remoteFrames=\(receiver.renderer.snapshot.frames)"
            print("PRODUCTION_RECORDING_MIC \(report)")
            let attachment = XCTAttachment(string: report)
            attachment.name = "Production WebRTC microphone recording metrics"
            attachment.lifetime = .keepAlways
            self.add(attachment)
        }
    }

    /// Production tap mute flag only. Sender track remains enabled; this does not exercise UI mute.
    /// Speak continuously throughout all three stages; silent outer stages must fail.
    func testProductionTapMuteWritesSilenceAndUnmuteRestoresRealMicrophone() async throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Requires the assigned physical iPhone microphone")
        #endif
        guard await AVAudioApplication.requestRecordPermission() else {
            return XCTFail("Microphone permission is required")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("recording-mute-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try await RecordingMicrophoneLoopback.withConnectedPeers { tap, receiver in
            let url = directory.appendingPathComponent("tap-mute-unmute.mp4")
            let recorder = LocalBroadcastRecorder()
            let epoch = CMClockGetTime(CMClockGetHostTimeClock())
            try recorder.start(url: url, epoch: epoch)
            tap.setMuted(false)
            tap.setRecordingSink(recorder)
            defer { tap.setRecordingSink(nil); tap.setMuted(false) }
            var frameIndex = 0
            var boundaries: [Double] = []
            var remoteFrames: [Int] = []
            do {
                for muted in [false, true, false] {
                    tap.setMuted(muted)
                    let stageStart = CMClockGetTime(CMClockGetHostTimeClock())
                    boundaries.append(CMTimeGetSeconds(CMTimeSubtract(stageStart, epoch)))
                    let remoteBefore = receiver.renderer.snapshot.frames
                    while CMTimeGetSeconds(CMTimeSubtract(CMClockGetTime(CMClockGetHostTimeClock()), stageStart)) < 2 {
                        let timestamp = CMClockGetTime(CMClockGetHostTimeClock())
                        recorder.appendVideo(pixelBuffer: try self.pixel(frameIndex), rotation: 0,
                            timestampNs: CMTimeConvertScale(timestamp, timescale: 1_000_000_000, method: .default).value)
                        frameIndex += 1
                        try await Task.sleep(for: .milliseconds(33))
                    }
                    remoteFrames.append(receiver.renderer.snapshot.frames - remoteBefore)
                }
                boundaries.append(CMTimeGetSeconds(CMTimeSubtract(CMClockGetTime(CMClockGetHostTimeClock()), epoch)))
                tap.setRecordingSink(nil)
                let result = try await recorder.stop()
                XCTAssertGreaterThan(result.audioFrames, 270_000, "Mute must retain continuous PCM admission")
            } catch {
                tap.setRecordingSink(nil)
                _ = try? await recorder.stop()
                throw error
            }
            let fullReport = try await self.inspect(url, expectedDuration: boundaries[3])
            // Exclude 350 ms on both sides of transitions for callback timestamp phase and
            // AAC overlap/priming. The remaining >1.2 seconds must contain actual decoded PCM.
            var segments: [MuteSegmentMetrics] = []
            for index in 0..<3 {
                let start = boundaries[index] + 0.35
                let end = boundaries[index + 1] - 0.35
                let segment = try await self.decodeMuteSegment(url, start: start, end: end)
                XCTAssertGreaterThan(segment.frames, Int((end - start - 0.03) * 48_000),
                                     "A missing middle segment cannot count as recorded silence")
                XCTAssertTrue(segment.allFinite)
                XCTAssertGreaterThan(remoteFrames[index], 70_000,
                                     "Sender capture must remain active through the tap-only mute interval")
                segments.append(segment)
            }
            XCTAssertGreaterThan(segments[0].peak, 0.0001, "First stage needs real audible microphone input")
            XCTAssertGreaterThan(segments[0].rms, 0.00001)
            XCTAssertLessThan(segments[1].peak, 0.0001, "Muted tap PCM must decode as silence")
            XCTAssertLessThan(segments[1].rms, 0.00001)
            XCTAssertGreaterThan(segments[2].peak, 0.0001, "Unmute must restore real microphone input")
            XCTAssertGreaterThan(segments[2].rms, 0.00001)
            XCTAssertLessThan(segments[1].rms, min(segments[0].rms, segments[2].rms) * 0.01,
                              "Mute must attenuate both audible stages by at least 40 dB")
            self.attachFile(url, name: "physical-microphone-tap-mute-unmute.mp4")
            let report = "scope=productionTapFlagOnly,senderTrackEnabled=true,uiMute=notTested,boundaries=\(boundaries),segmentFrames=\(segments.map(\.frames)),segmentPeaks=\(segments.map(\.peak)),segmentRMS=\(segments.map(\.rms)),remoteStageFrames=\(remoteFrames),\(fullReport)"
            print("PRODUCTION_RECORDING_TAP_MUTE \(report)")
            let attachment = XCTAttachment(string: report)
            attachment.name = "Production tap mute and unmute metrics"
            attachment.lifetime = .keepAlways
            self.add(attachment)
        }
    }

    private struct MuteSegmentMetrics {
        let frames: Int
        let peak: Float
        let rms: Double
        let allFinite: Bool
    }

    private func decodeMuteSegment(_ url: URL, start: Double, end: Double) async throws -> MuteSegmentMetrics {
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        let track = try XCTUnwrap(tracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMIsFloatKey: true,
            AVLinearPCMBitDepthKey: 32, AVLinearPCMIsNonInterleaved: false,
        ])
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var frames = 0
        var peak: Float = 0
        var squares = 0.0
        var allFinite = true
        while let sample = output.copyNextSampleBuffer() {
            let time = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            let sampleFrames = CMSampleBufferGetNumSamples(sample)
            let description = try XCTUnwrap(CMSampleBufferGetFormatDescription(sample))
            let asbd = try XCTUnwrap(CMAudioFormatDescriptionGetStreamBasicDescription(description))
            XCTAssertEqual(asbd.pointee.mSampleRate, 48_000)
            XCTAssertEqual(asbd.pointee.mChannelsPerFrame, 2)
            let block = try XCTUnwrap(CMSampleBufferGetDataBuffer(sample))
            var values = [Float](repeating: 0, count: sampleFrames * 2)
            let bytes = values.count * MemoryLayout<Float>.size
            XCTAssertEqual(CMBlockBufferGetDataLength(block), bytes)
            let status = values.withUnsafeMutableBytes { buffer in
                CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: bytes, destination: buffer.baseAddress!)
            }
            XCTAssertEqual(status, noErr)
            guard status == noErr else { throw NSError(domain: "MuteSegmentDecode", code: Int(status)) }
            for frame in 0..<sampleFrames {
                let frameTime = time + Double(frame) / 48_000
                guard frameTime >= start, frameTime < end else { continue }
                frames += 1
                for channel in 0..<2 {
                    let value = values[frame * 2 + channel]
                    allFinite = allFinite && value.isFinite
                    guard value.isFinite else { continue }
                    peak = max(peak, abs(value))
                    squares += Double(value) * Double(value)
                }
            }
        }
        XCTAssertEqual(reader.status, .completed, reader.error?.localizedDescription ?? "AAC segment decode failed")
        return MuteSegmentMetrics(frames: frames, peak: peak,
                                  rms: sqrt(squares / Double(max(1, frames * 2))), allFinite: allFinite)
    }

    private func capture(tap: WebRTCRecordingAudioTap, url: URL, seconds: Double) async throws -> LocalBroadcastRecordingResult {
        let recorder = LocalBroadcastRecorder()
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        tap.setRecordingSink(recorder)
        defer { tap.setRecordingSink(nil) }
        let start = ProcessInfo.processInfo.systemUptime
        var index = 0
        while ProcessInfo.processInfo.systemUptime - start < seconds {
            let timestamp = CMClockGetTime(CMClockGetHostTimeClock())
            recorder.appendVideo(pixelBuffer: try pixel(index), rotation: 0,
                timestampNs: CMTimeConvertScale(timestamp, timescale: 1_000_000_000, method: .default).value)
            index += 1
            try await Task.sleep(for: .milliseconds(33))
        }
        tap.setRecordingSink(nil)
        return try await recorder.stop()
    }

    private func pixel(_ index: Int) throws -> CVPixelBuffer {
        var pixel: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 64, 48, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pixel), kCVReturnSuccess)
        let buffer = try XCTUnwrap(pixel)
        CVPixelBufferLockBaseAddress(buffer, [])
        memset(CVPixelBufferGetBaseAddress(buffer), Int32(40 + index % 150), CVPixelBufferGetDataSize(buffer))
        CVPixelBufferUnlockBaseAddress(buffer, [])
        return buffer
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


@MainActor
private enum RecordingMicrophoneLoopback {
    static func withConnectedPeers(
        _ body: (WebRTCRecordingAudioTap, RecordingMicrophonePeerDelegate) async throws -> Void
    ) async throws {
        _ = LKRTCInitializeSSL()
        let tap = WebRTCRecordingAudioTap()
        let module = LKRTCDefaultAudioProcessingModule(config: nil, capturePostProcessingDelegate: tap, renderPreProcessingDelegate: nil)
        // Upstream adapter initWithDelegate currently ignores its argument; setter binds it.
        module.capturePostProcessingDelegate = tap
        let senderFactory = LKRTCPeerConnectionFactory(audioDeviceModuleType: .platformDefault, bypassVoiceProcessing: false,
            encoderFactory: LKRTCDefaultVideoEncoderFactory(), decoderFactory: LKRTCDefaultVideoDecoderFactory(), audioProcessingModule: module)
        let receiverFactory = senderFactory // One native audio device for both loopback peers.
        let session = LKRTCAudioSession.sharedInstance()
        let priorManual = session.useManualAudio
        let priorEnabled = session.isAudioEnabled
        // Match the existing production platform-default automatic audio session.
        session.useManualAudio = false
        session.isAudioEnabled = priorEnabled
        defer {
            session.isAudioEnabled = priorEnabled
            session.useManualAudio = priorManual
        }
        session.lockForConfiguration()
        do {
            let config = LKRTCAudioSessionConfiguration.webRTC()
            config.category = AVAudioSession.Category.playAndRecord.rawValue
            config.mode = AVAudioSession.Mode.videoChat.rawValue
            config.categoryOptions = [.allowBluetoothHFP, .defaultToSpeaker]
            try session.setConfiguration(config, active: true)
        } catch { session.unlockForConfiguration(); throw error }
        session.unlockForConfiguration()
        let config = LKRTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceServers = []
        let constraints = LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let senderDelegate = RecordingMicrophonePeerDelegate()
        let receiverDelegate = RecordingMicrophonePeerDelegate()
        guard let sender = senderFactory.peerConnection(with: config, constraints: constraints, delegate: senderDelegate),
              let receiver = receiverFactory.peerConnection(with: config, constraints: constraints, delegate: receiverDelegate) else {
            throw NSError(domain: "RecordingMicrophoneLoopback", code: 2)
        }
        let track = senderFactory.audioTrack(with: senderFactory.audioSource(with: constraints), trackId: "probe-microphone")
        track.isEnabled = true
        defer {
            sender.close(); receiver.close()
            session.lockForConfiguration()
            try? session.setActive(false)
            session.unlockForConfiguration()
        }
        let transceiverConfig = LKRTCRtpTransceiverInit()
        transceiverConfig.direction = .sendOnly
        transceiverConfig.streamIds = ["probe"]
        _ = sender.addTransceiver(with: track, init: transceiverConfig)
        let offer = try await description(sender, answer: false, constraints: constraints)
        try await setDescription(sender, description: offer, local: true)
        try await setDescription(receiver, description: offer, local: false)
        let answer = try await description(receiver, answer: true, constraints: constraints)
        try await setDescription(receiver, description: answer, local: true)
        try await setDescription(sender, description: answer, local: false)
        senderDelegate.forward(to: receiver)
        receiverDelegate.forward(to: sender)

        defer { tap.setRecordingSink(nil) }
        var connected = false
        for _ in 0..<40 {
            let active: Set<LKRTCIceConnectionState> = [.connected, .completed]
            if active.contains(sender.iceConnectionState), active.contains(receiver.iceConnectionState) {
                connected = true
                break
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        guard connected else { throw NSError(domain: "RecordingMicrophoneLoopback", code: 4,
            userInfo: [NSLocalizedDescriptionKey: "Loopback ICE did not connect"]) }
        XCTAssertTrue(module.capturePostProcessingDelegate === tap)
        try await body(tap, receiverDelegate)
        withExtendedLifetime((module, senderFactory, senderDelegate, receiverDelegate, track)) {}
    }
    private static func description(_ peer: LKRTCPeerConnection, answer: Bool, constraints: LKRTCMediaConstraints) async throws -> LKRTCSessionDescription {
        try await withCheckedThrowingContinuation { continuation in
            let completion: @Sendable (LKRTCSessionDescription?, Error?) -> Void = { description, error in
                if let error { continuation.resume(throwing: error) }
                else if let description { continuation.resume(returning: description) }
                else { continuation.resume(throwing: NSError(domain: "RecordingMicrophoneLoopback", code: 3)) }
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

nonisolated private final class RecordingRemoteRenderer: NSObject, LKRTCAudioRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var callbacks = 0, frames = 0
    func render(pcmBuffer: AVAudioPCMBuffer) {
        lock.withLock { callbacks += 1; frames += Int(pcmBuffer.frameLength) }
    }
    var snapshot: (callbacks: Int, frames: Int) { lock.withLock { (callbacks, frames) } }
}
nonisolated private final class RecordingMicrophonePeerDelegate: NSObject, LKRTCPeerConnectionDelegate, @unchecked Sendable {
    let renderer = RecordingRemoteRenderer()
    private let lock = NSLock()
    private var candidates: [LKRTCIceCandidate] = []
    private var target: LKRTCPeerConnection?
    // RTCAudioTrack deinit removes native sinks: retain remote ObjC wrappers for the test.
    private var remoteAudioTracks: [LKRTCAudioTrack] = []
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
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd stream: LKRTCMediaStream) { stream.audioTracks.forEach { attach($0) } }
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd rtpReceiver: LKRTCRtpReceiver, streams mediaStreams: [LKRTCMediaStream]) { if let track = rtpReceiver.track as? LKRTCAudioTrack { attach(track) } }
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange stateChanged: LKRTCSignalingState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didRemove stream: LKRTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: LKRTCPeerConnection) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange newState: LKRTCIceConnectionState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange newState: LKRTCIceGatheringState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didRemove candidates: [LKRTCIceCandidate]) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didOpen dataChannel: LKRTCDataChannel) {}
}
