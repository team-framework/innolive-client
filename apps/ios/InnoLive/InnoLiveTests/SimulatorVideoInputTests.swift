import XCTest
@preconcurrency import LiveKitWebRTC

@testable import InnoLive

@MainActor
final class SimulatorVideoInputTests: XCTestCase {
    func testBundledVideoProducesFramesAcrossLoopAndStops() async throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "SimulatorFixture", withExtension: "mp4"))
        let loopExpectation = expectation(description: "Bundled video repeats")
        let frames = CapturedFrames(
            minimumFrameCount: 31,
            expectation: loopExpectation
        )
        let capturer = BundledVideoCapturer(delegate: frames)
        try await capturer.start(url: url) { error in
            XCTFail("Bundled video failed: \(error.localizedDescription)")
        }
        await fulfillment(of: [loopExpectation], timeout: 10)
        capturer.stopCapture()
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
        let capturer = BundledVideoCapturer(delegate: frames)
        try await capturer.start(url: try XCTUnwrap(Bundle.main.url(forResource: "PrivacyPerformanceFixture", withExtension: "mp4"))) { error in
            XCTFail("Performance fixture failed: \(error.localizedDescription)")
        }
        await fulfillment(of: [repeated], timeout: 18)
        capturer.stopCapture()
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(frames.dimensions, "1920x1080")
        XCTAssertEqual(frames.timestampRegressions, 0)
        XCTAssertGreaterThanOrEqual(frames.measuredFPS, 29.9)
        XCTAssertLessThanOrEqual(frames.measuredFPS, 30.1)
        let count = frames.count
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(frames.count, count)
    }

    func testMissingBundledVideoReportsError() async {
        let frames = CapturedFrames()
        let capturer = BundledVideoCapturer(delegate: frames)
        let missing = Bundle.main.bundleURL.appendingPathComponent("MissingSimulatorFixture.mp4")
        do {
            try await capturer.start(url: missing) { _ in }
            XCTFail("A missing bundled video must fail to start")
        } catch {}
        capturer.stopCapture()
        XCTAssertEqual(frames.count, 0)
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
    private var firstTimestamp: Int64?
    var measuredFPS: Double {
        lock.lock(); defer { lock.unlock() }
        guard let firstTimestamp, let lastTimestamp, lastTimestamp > firstTimestamp else { return 0 }
        return Double(frameCount - 1) * 1_000_000_000 / Double(lastTimestamp - firstTimestamp)
    }
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
        if firstTimestamp == nil { firstTimestamp = frame.timeStampNs }
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
