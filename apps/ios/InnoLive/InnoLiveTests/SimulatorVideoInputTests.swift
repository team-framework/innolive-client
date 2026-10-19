import XCTest
@preconcurrency import LiveKitWebRTC

@testable import InnoLive

@MainActor
final class SimulatorVideoInputTests: XCTestCase {
    func testBundledVideoProducesFramesAcrossLoopAndStops() async throws {
        _ = LKRTCInitializeSSL()
        XCTAssertNotNil(Bundle.main.url(forResource: "SimulatorFixture", withExtension: "mp4"))
        let loopExpectation = expectation(description: "Bundled video repeats")
        let frames = CapturedFrames(
            minimumFrameCount: 31,
            expectation: loopExpectation
        )
        let capturer = LKRTCFileVideoCapturer(delegate: frames)
        capturer.startCapturing(fromFileNamed: "SimulatorFixture.mp4") { error in
            XCTFail("Bundled video failed: \(error.localizedDescription)")
        }
        await fulfillment(of: [loopExpectation], timeout: 10)
        capturer.stopCapture()
        // The SDK may already have queued the last delegate callback.
        try await Task.sleep(for: .milliseconds(200))
        let count = frames.count
        XCTAssertGreaterThan(count, 30, "The two-second, 30-frame bundle must repeat")
        XCTAssertEqual(frames.dimensions, "320x180")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(frames.count, count)
    }

    func testOptionalPerformanceVideoLoopsAtFullHDWithMonotonicTimestamps() async throws {
        guard Bundle.main.url(forResource: "PrivacyPerformanceFixture", withExtension: "mp4") != nil else {
            throw XCTSkip("Generate the optional performance video first")
        }
        let repeated = expectation(description: "Ten-second performance fixture repeats")
        let frames = CapturedFrames(minimumFrameCount: 330, expectation: repeated)
        let capturer = LKRTCFileVideoCapturer(delegate: frames)
        capturer.startCapturing(fromFileNamed: "PrivacyPerformanceFixture.mp4") { error in
            XCTFail("Performance fixture failed: \(error.localizedDescription)")
        }
        await fulfillment(of: [repeated], timeout: 18)
        capturer.stopCapture()
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(frames.dimensions, "1920x1080")
        XCTAssertEqual(frames.timestampRegressions, 0)
        let count = frames.count
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(frames.count, count)
    }

    func testMissingBundledVideoReportsError() async {
        let errorReceived = expectation(description: "Missing video error")
        let capturer = LKRTCFileVideoCapturer(delegate: CapturedFrames())
        capturer.startCapturing(fromFileNamed: "MissingSimulatorFixture.mp4") { _ in
            errorReceived.fulfill()
        }
        await fulfillment(of: [errorReceived], timeout: 3)
        capturer.stopCapture()
    }
}

nonisolated private final class CapturedFrames: NSObject, LKRTCVideoCapturerDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let minimumFrameCount: Int
    private let expectation: XCTestExpectation?
    private var didFulfillExpectation = false
    private var frameCount = 0
    private var frameDimensions = ""
    private var lastTimestamp: Int64?
    private var regressions = 0

    var timestampRegressions: Int { lock.lock(); defer { lock.unlock() }; return regressions }

    init(minimumFrameCount: Int = 31, expectation: XCTestExpectation? = nil) {
        self.minimumFrameCount = minimumFrameCount
        self.expectation = expectation
        super.init()
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return frameCount
    }

    var dimensions: String {
        lock.lock()
        defer { lock.unlock() }
        return frameDimensions
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {
        lock.lock()
        if let lastTimestamp, frame.timeStampNs <= lastTimestamp { regressions += 1 }
        lastTimestamp = frame.timeStampNs
        frameCount += 1
        frameDimensions = "\(frame.width)x\(frame.height)"
        let shouldFulfillExpectation = expectation != nil
            && frameCount >= minimumFrameCount
            && !didFulfillExpectation
        if shouldFulfillExpectation {
            didFulfillExpectation = true
        }
        lock.unlock()

        if shouldFulfillExpectation {
            expectation?.fulfill()
        }
    }
}
