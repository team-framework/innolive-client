import XCTest
@preconcurrency import LiveKitWebRTC

@testable import InnoLive

@MainActor
final class SimulatorVideoInputTests: XCTestCase {
    func testBundledVideoProducesFramesAcrossLoopAndStops() async throws {
        _ = LKRTCInitializeSSL()
        XCTAssertNotNil(Bundle.main.url(forResource: "SimulatorFixture", withExtension: "mp4"))
        let loopExpectation = XCTestExpectation(description: "Bundled video repeats")
        let frames = CapturedFrames(
            minimumFrameCount: 31,
            expectation: loopExpectation
        )
        let capturer = LKRTCFileVideoCapturer(delegate: frames)
        capturer.startCapturing(fromFileNamed: "SimulatorFixture.mp4") { error in
            XCTFail("Bundled video failed: \(error.localizedDescription)")
        }
        let waitResult = await XCTWaiter.fulfillment(of: [loopExpectation], timeout: 10)
        XCTAssertEqual(waitResult, .completed, "Bundled video repeats: \(frames.arrivalSummary(clipFrameCount: 30))")
        capturer.stopCapture()
        // SDK가 마지막 delegate 콜백을 이미 큐에 넣었을 수 있다.
        try await Task.sleep(for: .milliseconds(200))
        let count = frames.count
        XCTAssertGreaterThan(count, 30, "The two-second, 30-frame bundle must repeat: \(frames.arrivalSummary(clipFrameCount: 30))")
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
        let capturer = DebugPerformanceVideoCapturer(delegate: frames)
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
    private var firstTimestamp: Int64?
    var measuredFPS: Double {
        lock.lock(); defer { lock.unlock() }
        guard let firstTimestamp, let lastTimestamp, lastTimestamp > firstTimestamp else { return 0 }
        return Double(frameCount - 1) * 1_000_000_000 / Double(lastTimestamp - firstTimestamp)
    }
    private var regressions = 0
    private let createdAt = DispatchTime.now().uptimeNanoseconds
    private var firstArrival: UInt64?
    private var lastArrival: UInt64?
    private var longestGap: UInt64 = 0
    private var longestGapEndFrame = 0

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

    func arrivalSummary(clipFrameCount: Int) -> String {
        lock.lock(); defer { lock.unlock() }
        let now = DispatchTime.now().uptimeNanoseconds
        func milliseconds(_ nanoseconds: UInt64) -> UInt64 { nanoseconds / 1_000_000 }
        let firstFrame = firstArrival.map { "\(milliseconds($0 - createdAt))ms" } ?? "none"
        let lastFrameAge = lastArrival.map { "\(milliseconds(now - $0))ms" } ?? "none"
        return "frames=\(frameCount) loops=\(frameCount / clipFrameCount) elapsed=\(milliseconds(now - createdAt))ms "
            + "firstFrameAfter=\(firstFrame) lastFrameAgo=\(lastFrameAge) "
            + "longestGap=\(milliseconds(longestGap))ms beforeFrame=\(longestGapEndFrame)"
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {
        lock.lock()
        let arrival = DispatchTime.now().uptimeNanoseconds
        if let lastArrival, arrival - lastArrival > longestGap {
            longestGap = arrival - lastArrival
            longestGapEndFrame = frameCount + 1
        }
        if firstArrival == nil { firstArrival = arrival }
        lastArrival = arrival
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
