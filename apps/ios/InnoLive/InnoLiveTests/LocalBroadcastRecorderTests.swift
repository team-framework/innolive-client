import AVFoundation
import XCTest
@testable import InnoLive

@MainActor final class LocalBroadcastRecorderTests: XCTestCase {
    func testWritesAudibleMP4WithSharedTimelineAndPreservesGap() async throws {
        let recorder = LocalBroadcastRecorder()
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        // 처음 오디오 블록 3개는 영상보다 먼저 도착한다. 이 블록도 남아 있어야 한다.
        for index in 0..<60 {
            let seconds = Double(index) / 100 + (index >= 30 ? 1 : 0)
            let time = CMTimeAdd(epoch, CMTime(seconds: seconds, preferredTimescale: 1_000_000_000))
            if index >= 3, index % 2 == 1 {
                recorder.appendVideo(pixelBuffer: try pixel(), rotation: 90,
                    timestampNs: CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value)
            }
            recorder.appendAudio(try tone(index: index), when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
            // recorder는 대기 영상이 4개를 넘으면 버린다. 첫 프레임의 writer 생성이 느려도 프레임이 버려지지 않도록 기록 큐를 비운 뒤 진행한다.
            await recorder.validationDrainWriterQueue()
            try await Task.sleep(for: .milliseconds(10))
        }
        let result = try await recorder.stop()
        XCTAssertEqual(result.audioFrames, 28_800)
        XCTAssertGreaterThan(result.videoFrames, 5, "dropped \(result.droppedVideoFrames)")
        XCTAssertEqual(result.duration, 1.6, accuracy: 0.002)
        let asset = AVURLAsset(url: url)
        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        XCTAssertEqual(videoTracks.count, 1); XCTAssertEqual(audioTracks.count, 1)
        let size = try await XCTUnwrap(videoTracks.first).load(.naturalSize)
        XCTAssertEqual(size, CGSize(width: 48, height: 64))
        let duration = try await asset.load(.duration)
        XCTAssertEqual(CMTimeGetSeconds(duration), 1.6, accuracy: 0.03)
        let decoded = try decodeAudio(asset: asset, track: XCTUnwrap(audioTracks.first))
        XCTAssertGreaterThan(decoded.peak, 0.1)
        XCTAssertLessThan(decoded.peak, 0.5)
        XCTAssertLessThan(decoded.gapPeak, 0.01)
        XCTAssertGreaterThan(decoded.resumedPeak, 0.1)
        XCTAssertLessThan(decoded.firstTime, 0.04)
        // 출력을 마무리한 뒤 늦게 온 콜백은 디스크 내용에 영향을 주면 안 된다.
        let before = try Data(contentsOf: url)
        recorder.appendVideo(pixelBuffer: try pixel(), rotation: 0, timestampNs: Int64.max)
        recorder.appendAudio(try tone(index: 0), when: AVAudioTime(hostTime: mach_absolute_time()))
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(try Data(contentsOf: url), before)
    }

    func testDelayedFirstVideoDrainsBufferedAudioBeforeStop() async throws {
        let recorder = LocalBroadcastRecorder()
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        for index in 0..<120 {
            let time = CMTimeAdd(epoch, CMTime(value: Int64(index * 480), timescale: 48_000))
            recorder.appendAudio(try tone(index: index), when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
        }
        recorder.appendVideo(pixelBuffer: try pixel(), rotation: 0,
            timestampNs: CMTimeConvertScale(epoch, timescale: 1_000_000_000, method: .default).value)
        let result = try await recorder.stop()
        XCTAssertEqual(result.audioFrames, 57_600)
        XCTAssertEqual(result.duration, 1.2, accuracy: 0.005)
    }

    func testThirtySecondCaptureGapDrainsSilenceWithoutImmediateFailure() async throws {
        let recorder = LocalBroadcastRecorder()
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        for seconds in [0.0, 30.01] {
            let time = CMTimeAdd(epoch, CMTime(seconds: seconds, preferredTimescale: 1_000_000_000))
            recorder.appendVideo(pixelBuffer: try pixel(), rotation: 0,
                timestampNs: CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value)
            recorder.appendAudio(try tone(index: 0), when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
            try await Task.sleep(for: .milliseconds(30))
        }
        let result = try await recorder.stop()
        XCTAssertEqual(result.audioFrames, 960)
        XCTAssertEqual(result.duration, 30.02, accuracy: 0.005)
        let asset = AVURLAsset(url: url)
        let audioTrack = try await asset.loadTracks(withMediaType: .audio).first
        let range = try await XCTUnwrap(audioTrack).load(.timeRange)
        XCTAssertEqual(CMTimeGetSeconds(range.duration), 30.02, accuracy: 0.05)
    }

    func testSmallCallbackTimestampJitterDoesNotAccumulateDrift() async throws {
        let recorder = LocalBroadcastRecorder()
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        for index in 0..<100 {
            let jitter = index == 0 ? 0.0 : (index % 2 == 0 ? 0.001 : -0.001)
            let time = CMTimeAdd(epoch, CMTime(seconds: Double(index) / 100 + jitter, preferredTimescale: 1_000_000_000))
            if index % 10 == 0 {
                recorder.appendVideo(pixelBuffer: try pixel(), rotation: 0,
                    timestampNs: CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value)
            }
            recorder.appendAudio(try tone(index: index), when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
            try await Task.sleep(for: .milliseconds(10))
        }
        let result = try await recorder.stop()
        XCTAssertEqual(result.audioFrames, 48_000)
        XCTAssertEqual(result.duration, 1, accuracy: 0.001)
    }

    func testOverlappingAudioTimestampsKeepOnlyNewAudioAndSave() async throws {
        let recorder = LocalBroadcastRecorder()
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        let starts = (0..<50).map { Double($0) / 100 } + [0.49, 0.495] + (0..<49).map { 0.505 + Double($0) / 100 }
        for (index, seconds) in starts.enumerated() {
            let time = CMTimeAdd(epoch, CMTime(seconds: seconds, preferredTimescale: 1_000_000_000))
            if index % 10 == 0 {
                recorder.appendVideo(pixelBuffer: try pixel(), rotation: 0,
                    timestampNs: CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value)
            }
            recorder.appendAudio(try tone(index: index), when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
            try await Task.sleep(for: .milliseconds(10))
        }
        let result = try await recorder.stop()
        XCTAssertEqual(result.audioFrames, 50 * 480 + 240 + 49 * 480, "re-delivered audio dropped, partial overlap trimmed")
        XCTAssertEqual(result.duration, 0.995, accuracy: 0.001)
        let audioTracks = try await AVURLAsset(url: url).loadTracks(withMediaType: .audio)
        XCTAssertEqual(audioTracks.count, 1)
    }

    func testAudioMemoryBudgetFailsBoundedlyBeforeVideoArrives() async throws {
        let observer = RecordingFailureObserver()
        let recorder = LocalBroadcastRecorder(onFailure: { observer.record($0) })
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        for index in 0..<220 {
            let time = CMTimeAdd(epoch, CMTime(value: Int64(index * 480), timescale: 48_000))
            recorder.appendAudio(try tone(index: index), when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
        }
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(observer.snapshot.count, 1)
        do { _ = try await recorder.stop(); XCTFail("Expected bounded overflow") }
        catch let error as LocalBroadcastRecordingError {
            guard case .audioOverflow = error else { return XCTFail("Unexpected: \(error)") }
        }
        XCTAssertEqual(observer.snapshot.count, 1)
    }

    func testInvalidFirstAudioReportsFailureOnceOffCaptureThreadAndStopStillThrows() async throws {
        let observer = RecordingFailureObserver()
        let recorder = LocalBroadcastRecorder(onFailure: { observer.record($0) })
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        try recorder.start(url: url)
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false))
        let invalid = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
        invalid.frameLength = 480
        for _ in 0..<20 { recorder.appendAudio(invalid, when: AVAudioTime(hostTime: mach_absolute_time())) }
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(observer.snapshot.count, 1, "The first invalid input must notify without waiting for stop or a pump timer")
        XCTAssertFalse(observer.snapshot.onMainThread)
        do { _ = try await recorder.stop(); XCTFail("Invalid input must still fail finalization") }
        catch let error as LocalBroadcastRecordingError {
            guard case .invalidAudio = error else { return XCTFail("Unexpected: \(error)") }
        }
        XCTAssertEqual(observer.snapshot.count, 1)
    }

    func testNoFramesFailsWithoutProducingSuccess() async throws {
        let recorder = LocalBroadcastRecorder()
        try recorder.start(url: temporaryURL())
        do { _ = try await recorder.stop(); XCTFail("Must reject empty capture") }
        catch let error as LocalBroadcastRecordingError {
            guard case .noVideo = error else { return XCTFail("Unexpected: \(error)") }
        }
    }

    func testMissingAudioFailsInsteadOfClaimingCombinedMP4() async throws {
        let recorder = LocalBroadcastRecorder()
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        recorder.appendVideo(pixelBuffer: try pixel(), rotation: 0,
            timestampNs: CMTimeConvertScale(CMTimeAdd(epoch, CMTime(value: 1, timescale: 30)), timescale: 1_000_000_000, method: .default).value)
        do { _ = try await recorder.stop(); XCTFail("Must reject missing audio") }
        catch let error as LocalBroadcastRecordingError {
            guard case .noAudio = error else { return XCTFail("Unexpected: \(error)") }
        }
    }

    private func temporaryURL() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("recorder-test-\(UUID().uuidString).mp4") }
    private func pixel() throws -> CVPixelBuffer {
        var pixel: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 64, 48, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pixel), kCVReturnSuccess)
        let buffer = try XCTUnwrap(pixel)
        CVPixelBufferLockBaseAddress(buffer, [])
        memset(CVPixelBufferGetBaseAddress(buffer), 80, CVPixelBufferGetDataSize(buffer))
        CVPixelBufferUnlockBaseAddress(buffer, [])
        return buffer
    }
    private func tone(index: Int) throws -> AVAudioPCMBuffer {
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
        buffer.frameLength = 480
        let channels = try XCTUnwrap(buffer.floatChannelData)
        for sample in 0..<480 {
            let value = Float(sin(Double(index * 480 + sample) * 2 * .pi * 440 / 48_000) * 0.25)
            channels[0][sample] = value; channels[1][sample] = value
        }
        return buffer
    }
    private func decodeAudio(asset: AVAsset, track: AVAssetTrack) throws -> (peak: Float, gapPeak: Float, resumedPeak: Float, firstTime: Double) {
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMIsFloatKey: true, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsNonInterleaved: false])
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var peak: Float = 0, gapPeak: Float = 0, resumedPeak: Float = 0
        var firstTime: Double?
        while let sample = output.copyNextSampleBuffer(), let block = CMSampleBufferGetDataBuffer(sample) {
            let start = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            if firstTime == nil { firstTime = start }
            var length = 0
            var pointer: UnsafeMutablePointer<Int8>?
            XCTAssertEqual(CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &pointer), noErr)
            if let pointer {
                pointer.withMemoryRebound(to: Float.self, capacity: length / 4) { values in
                    for index in 0..<length / 4 {
                        let magnitude = abs(values[index])
                        peak = max(peak, magnitude)
                        let time = start + Double(index / 2) / 48_000
                        if time > 0.6, time < 1.1 { gapPeak = max(gapPeak, magnitude) }
                        if time > 1.4, time < 1.55 { resumedPeak = max(resumedPeak, magnitude) }
                    }
                }
            }
        }
        XCTAssertEqual(reader.status, .completed)
        return (peak, gapPeak, resumedPeak, firstTime ?? .infinity)
    }
}

nonisolated private final class RecordingFailureObserver: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var onMainThread = false
    func record(_ message: String) {
        lock.withLock { count += 1; onMainThread = onMainThread || Thread.isMainThread }
    }
    var snapshot: (count: Int, onMainThread: Bool) { lock.withLock { (count, onMainThread) } }
}
