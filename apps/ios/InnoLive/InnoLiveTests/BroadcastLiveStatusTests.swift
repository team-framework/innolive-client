import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class BroadcastLiveStatusTests: XCTestCase {
    func testUplinkQualityIsMeasuringUntilTheFirstSample() {
        let tracker = BroadcastUplinkQualityTracker()

        XCTAssertEqual(tracker.quality, .measuring)
        XCTAssertNil(tracker.quality.summary)
    }

    func testUplinkSummaryUsesShortEdgeAndRoundedFrameRate() {
        var tracker = BroadcastUplinkQualityTracker()

        XCTAssertEqual(tracker.record(sample(width: 720, height: 1280, fps: 29.6)).summary, "720p · 30fps")
        XCTAssertEqual(tracker.record(sample(width: 1920, height: 1080, fps: 24.2)).summary, "1080p · 24fps")
        XCTAssertEqual(tracker.record(sample(width: 1280, height: 720, fps: nil)).summary, "720p")
    }

    func testNetworkLimitWarnsOnlyAfterThreeConsecutiveSamples() {
        var tracker = BroadcastUplinkQualityTracker()

        XCTAssertNil(tracker.record(sample(reason: "bandwidth")).limitation)
        XCTAssertNil(tracker.record(sample(reason: "bandwidth")).limitation)
        XCTAssertEqual(tracker.record(sample(reason: "bandwidth")).limitation, .network)
    }

    func testShortLimitBurstAtStartDoesNotWarn() {
        var tracker = BroadcastUplinkQualityTracker()

        for reason in ["bandwidth", "bandwidth", "none", "bandwidth", "bandwidth"] {
            XCTAssertNil(tracker.record(sample(reason: reason)).limitation, reason)
        }
    }

    func testCPULimitWarnsAsDeviceLoad() {
        var tracker = BroadcastUplinkQualityTracker()

        _ = tracker.record(sample(reason: "cpu"))
        _ = tracker.record(sample(reason: "cpu"))
        XCTAssertEqual(tracker.record(sample(reason: "cpu")).limitation, .device)
    }

    func testShownWarningFollowsTheLatestLimitReason() {
        var tracker = BroadcastUplinkQualityTracker()
        for _ in 0..<3 { _ = tracker.record(sample(reason: "bandwidth")) }

        XCTAssertEqual(tracker.record(sample(reason: "cpu")).limitation, .device)
    }

    func testWarningClearsAfterTwoUnlimitedSamples() {
        var tracker = BroadcastUplinkQualityTracker()
        for _ in 0..<3 { _ = tracker.record(sample(reason: "bandwidth")) }

        XCTAssertEqual(tracker.record(sample(reason: "none")).limitation, .network)
        XCTAssertNil(tracker.record(sample(reason: "none")).limitation)
    }

    func testUnknownLimitReasonIsNotShownAsWarning() {
        var tracker = BroadcastUplinkQualityTracker()

        for _ in 0..<4 {
            XCTAssertNil(tracker.record(sample(reason: "other")).limitation)
        }
    }

    func testResetReturnsToMeasuringAndForgetsTheStreak() {
        var tracker = BroadcastUplinkQualityTracker()
        _ = tracker.record(sample(reason: "bandwidth"))
        _ = tracker.record(sample(reason: "bandwidth"))

        tracker.reset()

        XCTAssertEqual(tracker.quality, .measuring)
        XCTAssertNil(tracker.record(sample(reason: "bandwidth")).limitation)
    }

    func testDiagnosticsReturnsTheParsedOutboundSample() {
        let monitor = UplinkDiagnosticsMonitor()

        let parsed = monitor.noteStats([
            (type: "outbound-rtp", values: [
                "kind": "video",
                "qualityLimitationReason": "bandwidth",
                "frameWidth": 1280,
                "frameHeight": 720,
                "framesPerSecond": 30.0
            ])
        ])

        XCTAssertEqual(parsed, UplinkVideoOutboundStats(reason: "bandwidth", width: 1280, height: 720, framesPerSecond: 30))
        XCTAssertNil(monitor.noteStats([(type: "outbound-rtp", values: ["kind": "audio"])]))
    }

    private func sample(
        reason: String = "none",
        width: Int = 1280,
        height: Int = 720,
        fps: Double? = 30
    ) -> UplinkVideoOutboundStats {
        UplinkVideoOutboundStats(reason: reason, width: width, height: height, framesPerSecond: fps)
    }
}
