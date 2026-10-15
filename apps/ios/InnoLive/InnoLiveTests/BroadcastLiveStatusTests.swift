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

    func testTargetToneSeparatesLiveReadyAttentionAndEnded() throws {
        let cases: [(status: String, phase: String, tone: BroadcastTargetTone)] = [
            ("streaming", "live", .live),
            ("idle", "prepared", .ready),
            ("idle", "preparing", .progress),
            ("streaming", "going_live", .progress),
            ("reconfiguring", "live", .progress),
            ("reconnecting", "live", .attention),
            ("paused", "live", .attention),
            ("paused_reconnecting", "live", .attention),
            ("stopped", "live", .ended),
            ("future_status", "future_phase", .progress)
        ]

        for item in cases {
            let policy = YouTubeBroadcastStatePolicy(
                stream: try decodeStream(status: item.status, broadcastPhase: item.phase),
                isChangingStreamState: false
            )
            XCTAssertEqual(policy.targetTone, item.tone, "\(item.status)/\(item.phase)")
        }
    }

    func testTargetLabelDoesNotRepeatThePlatformName() throws {
        let streaming = YouTubeBroadcastStatePolicy(
            stream: try decodeStream(status: "streaming", broadcastPhase: "live"),
            isChangingStreamState: false
        )
        let reconnecting = YouTubeBroadcastStatePolicy(
            stream: try decodeStream(status: "reconnecting", broadcastPhase: "live"),
            isChangingStreamState: false
        )

        XCTAssertEqual(streaming.targetLabel, String(localized: "송출 중", table: "BroadcastGuide"))
        XCTAssertEqual(reconnecting.targetLabel, String(localized: "다시 연결하는 중", table: "BroadcastGuide"))
        XCTAssertFalse(streaming.targetLabel.contains("YouTube"))
    }

    func testPanelRowsFallBackToTheSingleStreamWhenTargetsAreMissing() throws {
        let stream = try decodeStream(status: "streaming", broadcastPhase: "live")

        let rows = BroadcastLiveStatusPanel.rows(targets: [], fallbackStream: stream, isBroadcastActive: true)

        XCTAssertEqual(rows.map(\.provider), ["youtube"])
        XCTAssertEqual(rows.first?.stream, stream)
    }

    func testPanelRowsPreferServerTargetsAndHideInactiveFallback() throws {
        let stream = try decodeStream(status: "streaming", broadcastPhase: "live")
        let chzzk = BroadcastTargetState(provider: "chzzk", stream: stream)

        XCTAssertEqual(
            BroadcastLiveStatusPanel.rows(targets: [chzzk], fallbackStream: stream, isBroadcastActive: true).map(\.provider),
            ["chzzk"]
        )
        XCTAssertTrue(BroadcastLiveStatusPanel.rows(targets: [], fallbackStream: stream, isBroadcastActive: false).isEmpty)
    }

    private func sample(
        reason: String = "none",
        width: Int = 1280,
        height: Int = 720,
        fps: Double? = 30
    ) -> UplinkVideoOutboundStats {
        UplinkVideoOutboundStats(reason: reason, width: width, height: height, framesPerSecond: fps)
    }

    private func decodeStream(status: String, broadcastPhase: String) throws -> YouTubeStreamState {
        let data = Data(
            """
            {
              "status": "\(status)",
              "started_at": "2026-10-09T12:00:00Z",
              "stopped_at": null,
              "publisher_active": true,
              "last_error": null,
              "reconnect_attempts": 0,
              "stop_reason": null,
              "paused_at": null,
              "broadcast_phase": "\(broadcastPhase)"
            }
            """.utf8
        )
        return try JSONDecoder().decode(YouTubeStreamState.self, from: data)
    }
}
