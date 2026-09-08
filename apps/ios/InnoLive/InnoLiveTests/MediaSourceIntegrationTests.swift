import AVFoundation
@preconcurrency import LiveKitWebRTC
import XCTest

@testable import InnoLive

@MainActor
final class MediaSourceIntegrationTests: XCTestCase {
    private var temporaryURLs: [URL] = []

    override func tearDown() {
        for url in temporaryURLs {
            try? FileManager.default.removeItem(at: url)
        }
        temporaryURLs.removeAll()
        super.tearDown()
    }

    func testFilePlaysAtNativeTimingAndCompletesWithoutLoop() async throws {
        let url = try await makeVideo()
        let recorder = MediaFrameRecorder()
        let finished = expectation(description: "EOF")
        let consumer = makeConsumer(recorder)
        let source = FileSource(url: url, consumer: consumer, loop: false,
                                completionHandler: { finished.fulfill() })
        let started = ProcessInfo.processInfo.systemUptime
        try await source.start()
        await fulfillment(of: [finished], timeout: 3)
        let elapsed = ProcessInfo.processInfo.systemUptime - started
        await source.stop()

        XCTAssertGreaterThan(elapsed, 0.85, "Source must pace frames instead of decoding as fast as possible")
        XCTAssertLessThan(elapsed, 2.5, "Frame delays must use one fixed host-time origin")
        XCTAssertEqual(source.metrics.deliveredFrames + source.metrics.droppedFrames, 12)
        XCTAssertGreaterThanOrEqual(recorder.snapshots.count, 10)
        XCTAssertTrue(recorder.snapshots.allSatisfy { $0.width == 160 && $0.height == 96 })
        assertIncreasingTimestamps(recorder.snapshots)
    }

    func testLoopKeepsMonotonicTimestampsAtNativeCadence() async throws {
        let url = try await makeVideo()
        let recorder = MediaFrameRecorder()
        let source = FileSource(url: url, consumer: makeConsumer(recorder))
        try await source.start()
        try await Task.sleep(for: .milliseconds(2700))
        await source.stop()

        let frames = recorder.snapshots
        XCTAssertGreaterThanOrEqual(frames.count, 23, "Loop must retain native cadence")
        assertIncreasingTimestamps(frames)
        for (previous, next) in zip(frames, frames.dropFirst()) {
            XCTAssertLessThan(next.timestamp - previous.timestamp, 500_000_000)
        }
    }

    func testLeadingPresentationGapIsPreserved() async throws {
        // AVAssetWriter inserts an initial hold at PTS 0 before the first input
        // at PTS 2. This is a real gap within the movie, not a loop offset.
        let url = try await makeVideo(startPTS: 2)
        let recorder = MediaFrameRecorder()
        let source = FileSource(url: url, consumer: makeConsumer(recorder), loop: false)
        try await source.start()
        try await Task.sleep(for: .milliseconds(2450))
        await source.stop()
        let frames = recorder.snapshots
        XCTAssertGreaterThanOrEqual(frames.count, 4)
        XCTAssertLessThan(frames.count, 10, "Source must preserve the movie's initial hold")
        if frames.count >= 2 {
            XCTAssertEqual(frames[1].timestamp - frames[0].timestamp, 2_000_000_000)
        }
    }

    func testStopFreezesDeliveryAndRestartStartsPromptlyWithMonotonicClock() async throws {
        let url = try await makeVideo()
        let recorder = MediaFrameRecorder()
        let source = FileSource(url: url, consumer: makeConsumer(recorder))
        try await source.start()
        try await Task.sleep(for: .milliseconds(350))
        await source.stop()
        let stoppedFrames = recorder.snapshots
        XCTAssertFalse(stoppedFrames.isEmpty)
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertEqual(recorder.snapshots.count, stoppedFrames.count)

        try await source.start()
        try await Task.sleep(for: .milliseconds(350))
        await source.stop()
        XCTAssertGreaterThanOrEqual(recorder.snapshots.count, stoppedFrames.count + 3)
        assertIncreasingTimestamps(recorder.snapshots)
    }

    func testContainerRotationReachesSharedConsumer() async throws {
        let url = try await makeVideo(rotation: .pi / 2)
        let recorder = MediaFrameRecorder()
        let source = FileSource(url: url, consumer: makeConsumer(recorder), loop: false)
        try await source.start()
        try await Task.sleep(for: .milliseconds(250))
        await source.stop()
        XCTAssertFalse(recorder.snapshots.isEmpty)
        XCTAssertTrue(recorder.snapshots.allSatisfy { $0.rotation == 90 })
    }

    func testMissingAndNonVideoFilesFailStart() async throws {
        let directory = FileManager.default.temporaryDirectory
        let missing = directory.appendingPathComponent(UUID().uuidString + ".mp4")
        let invalid = directory.appendingPathComponent(UUID().uuidString + ".mp4")
        temporaryURLs.append(invalid)
        try Data("not a video".utf8).write(to: invalid)
        for url in [missing, invalid] {
            let source = FileSource(url: url, consumer: makeConsumer(MediaFrameRecorder()))
            do {
                try await source.start()
                XCTFail("Invalid input must fail instead of silently starting")
            } catch {
                XCTAssertTrue(error is MediaSourceError)
            }
            await source.stop()
        }
    }

    func testSlowConsumerDropsLateFramesAndStopStillCompletes() async throws {
        let url = try await makeVideo()
        let recorder = MediaFrameRecorder(delay: 0.22)
        let source = FileSource(url: url, consumer: makeConsumer(recorder))
        try await source.start()
        try await Task.sleep(for: .milliseconds(850))
        await source.stop()
        XCTAssertGreaterThan(source.metrics.deliveredFrames, 0)
        XCTAssertGreaterThan(source.metrics.droppedFrames, 0)
        let count = recorder.snapshots.count
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(recorder.snapshots.count, count)
    }

    private func makeConsumer(_ recorder: MediaFrameRecorder) -> WebRTCVideoFrameConsumer {
        _ = LKRTCInitializeSSL()
        let factory = LKRTCPeerConnectionFactory(
            encoderFactory: LKRTCDefaultVideoEncoderFactory(),
            decoderFactory: LKRTCDefaultVideoDecoderFactory()
        )
        return WebRTCVideoFrameConsumer(target: factory.videoSource(), frameObserver: { frame in
            recorder.record(frame)
        })
    }

    private func assertIncreasingTimestamps(_ frames: [MediaFrameRecorder.Snapshot],
                                            file: StaticString = #filePath, line: UInt = #line) {
        for (previous, next) in zip(frames, frames.dropFirst()) {
            XCTAssertGreaterThan(next.timestamp, previous.timestamp, file: file, line: line)
        }
    }

    private func makeVideo(rotation: CGFloat = 0, startPTS: Int64 = 0) async throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        temporaryURLs.append(url)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: 160,
            AVVideoHeightKey: 96,
        ])
        input.transform = CGAffineTransform(rotationAngle: rotation)
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 160,
            kCVPixelBufferHeightKey as String: 96,
        ])
        writer.add(input)
        XCTAssertTrue(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        for index in 0..<12 {
            let timeout = ProcessInfo.processInfo.systemUptime + 5
            while !input.isReadyForMoreMediaData {
                guard writer.status == .writing, ProcessInfo.processInfo.systemUptime < timeout else {
                    throw writer.error ?? NSError(domain: "MediaFixture", code: 1)
                }
                try await Task.sleep(for: .milliseconds(5))
            }
            var pixelBuffer: CVPixelBuffer?
            let result = CVPixelBufferCreate(kCFAllocatorDefault, 160, 96,
                                            kCVPixelFormatType_32BGRA, nil, &pixelBuffer)
            XCTAssertEqual(result, kCVReturnSuccess)
            let buffer = try XCTUnwrap(pixelBuffer)
            CVPixelBufferLockBaseAddress(buffer, [])
            if let base = CVPixelBufferGetBaseAddress(buffer) {
                memset(base, Int32(20 + index * 15), CVPixelBufferGetBytesPerRow(buffer) * 96)
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            XCTAssertTrue(adaptor.append(buffer, withPresentationTime: CMTime(value: startPTS * 10 + Int64(index), timescale: 10)))
        }
        writer.endSession(atSourceTime: CMTime(value: startPTS * 10 + 12, timescale: 10))
        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else {
            throw writer.error ?? NSError(domain: "MediaFixture", code: 2)
        }
        return url
    }
}

nonisolated private final class MediaFrameRecorder: @unchecked Sendable {
    struct Snapshot {
        let timestamp: Int64
        let rotation: Int
        let width: Int
        let height: Int
    }

    private let lock = NSLock()
    private var frames: [Snapshot] = []
    private let delay: TimeInterval

    init(delay: TimeInterval = 0) { self.delay = delay }

    var snapshots: [Snapshot] {
        lock.lock()
        defer { lock.unlock() }
        return frames
    }

    func record(_ frame: VideoFrame) {
        lock.lock()
        frames.append(Snapshot(timestamp: frame.timestampNs,
                               rotation: Int(frame.rtcFrame.rotation.rawValue),
                               width: Int(frame.rtcFrame.width), height: Int(frame.rtcFrame.height)))
        lock.unlock()
        if delay > 0 { Thread.sleep(forTimeInterval: delay) }
    }
}
