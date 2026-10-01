import XCTest

@testable import InnoLive

final class UplinkDiagnosticsTests: XCTestCase {
    func testParserReadsVideoOutboundReasonAndSize() {
        let stats = UplinkVideoStatsParser.videoOutbound(statistics: [
            (type: "outbound-rtp", values: ["kind": "audio", "packetsSent": 3]),
            (type: "outbound-rtp", values: [
                "mediaType": "video",
                "qualityLimitationReason": "cpu",
                "frameWidth": 1280,
                "frameHeight": 720,
                "framesPerSecond": 24.5
            ])
        ])
        XCTAssertEqual(stats?.reason, "cpu")
        XCTAssertEqual(stats?.width, 1280)
        XCTAssertEqual(stats?.height, 720)
        XCTAssertEqual(stats?.framesPerSecond ?? 0, 24.5, accuracy: 0.001)
    }

    func testParserIgnoresMissingVideoAndDefaultsReasonToNone() {
        XCTAssertNil(UplinkVideoStatsParser.videoOutbound(statistics: [
            (type: "outbound-rtp", values: ["kind": "audio"])
        ]))
        let stats = UplinkVideoStatsParser.videoOutbound(statistics: [
            (type: "outbound-rtp", values: ["kind": "video", "frameWidth": NSNumber(value: 640)])
        ])
        XCTAssertEqual(stats?.reason, "none")
        XCTAssertEqual(stats?.width, 640)
    }

    func testQualityLogsWhenReasonBecomesCPUOrBandwidth() {
        var tracker = UplinkQualityTracker()
        let start = Date(timeIntervalSince1970: 1_000)
        XCTAssertNil(tracker.record(sample("none", 1920, 1080), at: start))
        let cpu = tracker.record(sample("cpu", 1920, 1080), at: start.addingTimeInterval(2))
        XCTAssertEqual(cpu?.kind, .limitation)
        XCTAssertEqual(cpu?.reason, "cpu")
        XCTAssertEqual(cpu?.previousReason, "none")
        let bandwidth = tracker.record(sample("bandwidth", 1280, 720), at: start.addingTimeInterval(4))
        XCTAssertEqual(bandwidth?.kind, .limitation)
        XCTAssertEqual(bandwidth?.reason, "bandwidth")
        XCTAssertEqual(bandwidth?.previousSizeLine, "1920x1080")
        XCTAssertEqual(bandwidth?.sizeLine, "1280x720")
    }

    func testResolutionDropWithNoneReasonIsLoggedOnce() {
        var tracker = UplinkQualityTracker()
        let start = Date(timeIntervalSince1970: 1_000)
        XCTAssertNil(tracker.record(sample("none", 1920, 1080), at: start))
        let drop = tracker.record(sample("none", 960, 540), at: start.addingTimeInterval(2))
        XCTAssertEqual(drop?.kind, .resolution)
        XCTAssertEqual(drop?.reason, "none")
        XCTAssertNil(tracker.record(sample("none", 960, 540), at: start.addingTimeInterval(4)))
    }

    func testSameOrientationPixelCountIsNotADropAndHeartbeatRepeatsWhileLimited() {
        var tracker = UplinkQualityTracker()
        let start = Date(timeIntervalSince1970: 1_000)
        _ = tracker.record(sample("cpu", 1920, 1080), at: start)
        XCTAssertNil(tracker.record(sample("cpu", 1080, 1920), at: start.addingTimeInterval(2)))
        let beat = tracker.record(sample("cpu", 1080, 1920), at: start.addingTimeInterval(30))
        XCTAssertEqual(beat?.kind, .heartbeat)
        XCTAssertEqual(beat?.reason, "cpu")
        XCTAssertNil(tracker.record(sample("cpu", 1080, 1920), at: start.addingTimeInterval(31)))
    }

    func testRecoveryToNoneIsLogged() {
        var tracker = UplinkQualityTracker()
        let start = Date(timeIntervalSince1970: 1_000)
        _ = tracker.record(sample("bandwidth", 1280, 720), at: start)
        let recovered = tracker.record(sample("none", 1280, 720), at: start.addingTimeInterval(2))
        XCTAssertEqual(recovered?.kind, .limitation)
        XCTAssertEqual(recovered?.reason, "none")
        XCTAssertEqual(recovered?.previousReason, "bandwidth")
    }

    func testDisconnectSnapshotCorrelatesBackgroundLockAndNetworkInsideWindow() {
        var context = UplinkDisconnectContext(inBackground: false, deviceLocked: false)
        let drop = Date(timeIntervalSince1970: 10_000)
        context.notePath("satisfied:wifi", at: drop.addingTimeInterval(-40))
        context.notePath("satisfied:cellular", at: drop.addingTimeInterval(-8))
        context.record(.enteredBackground, at: drop.addingTimeInterval(-3))
        context.record(.deviceLocked, at: drop.addingTimeInterval(-2))
        context.noteAlive(at: drop.addingTimeInterval(-1))
        let log = context.disconnect(trigger: "ice", at: drop)
        XCTAssertEqual(log?.background, true)
        XCTAssertEqual(log?.screenOff, true)
        XCTAssertEqual(log?.networkChanged, true)
        XCTAssertEqual(log?.networkFrom, "satisfied:wifi")
        XCTAssertEqual(log?.networkTo, "satisfied:cellular")
        XCTAssertEqual(log?.callbackDelayed, false)
        XCTAssertNil(context.disconnect(trigger: "peer", at: drop.addingTimeInterval(0.1)))
    }

    func testOldNetworkChangeAndUserLevelFlagsStayFalse() {
        var context = UplinkDisconnectContext(inBackground: false, deviceLocked: false)
        let drop = Date(timeIntervalSince1970: 10_000)
        context.notePath("satisfied:wifi", at: drop.addingTimeInterval(-40))
        context.notePath("satisfied:cellular", at: drop.addingTimeInterval(-20))
        context.record(.enteredBackground, at: drop.addingTimeInterval(-20))
        context.record(.leftBackground, at: drop.addingTimeInterval(-18))
        context.record(.deviceLocked, at: drop.addingTimeInterval(-20))
        context.record(.deviceUnlocked, at: drop.addingTimeInterval(-18))
        context.noteAlive(at: drop.addingTimeInterval(-12))
        let log = context.disconnect(trigger: "peer", at: drop)
        XCTAssertEqual(log?.background, false)
        XCTAssertEqual(log?.screenOff, false)
        XCTAssertEqual(log?.networkChanged, false)
        XCTAssertEqual(log?.callbackDelayed, true)
        XCTAssertEqual(log?.lastAliveAt, drop.addingTimeInterval(-12))
    }

    func testCurrentBackgroundWithoutRecentEdgeStillCounts() {
        var context = UplinkDisconnectContext(inBackground: true, deviceLocked: true)
        let drop = Date(timeIntervalSince1970: 10_000)
        context.noteAlive(at: drop)
        let log = context.disconnect(trigger: "ice", at: drop)
        XCTAssertEqual(log?.background, true)
        XCTAssertEqual(log?.screenOff, true)
        XCTAssertEqual(log?.networkChanged, false)
    }

    func testReconnectAllowsAnotherDisconnectLog() {
        var context = UplinkDisconnectContext(inBackground: false, deviceLocked: false)
        let first = Date(timeIntervalSince1970: 10_000)
        XCTAssertNotNil(context.disconnect(trigger: "ice", at: first))
        context.noteReconnected()
        XCTAssertNotNil(context.disconnect(trigger: "peer", at: first.addingTimeInterval(30)))
    }

    func testLogLinesContainReasonAndContextWithoutSessionSecrets() {
        let quality = UplinkQualityLog(
            at: Date(timeIntervalSince1970: 1_000),
            kind: .limitation,
            reason: "cpu",
            previousReason: "none",
            width: 1280,
            height: 720,
            previousWidth: 1920,
            previousHeight: 1080,
            framesPerSecond: 24
        ).line()
        XCTAssertTrue(quality.contains("reason=cpu"))
        XCTAssertTrue(quality.contains("previousReason=none"))
        XCTAssertTrue(quality.contains("kind=limitation"))
        XCTAssertFalse(quality.contains("session"))

        let disconnect = UplinkDisconnectLog(
            at: Date(timeIntervalSince1970: 1_000),
            trigger: "ice",
            lastAliveAt: nil,
            callbackDelayed: false,
            background: false,
            screenOff: false,
            networkChanged: false,
            networkFrom: nil,
            networkTo: nil
        ).line()
        XCTAssertTrue(disconnect.contains("trigger=ice"))
        XCTAssertTrue(disconnect.contains("screenOff=false"))
        XCTAssertTrue(disconnect.contains("network=-"))
        XCTAssertTrue(disconnect.contains("lastAliveAt=-"))
    }

    private func sample(_ reason: String, _ width: Int, _ height: Int) -> UplinkVideoOutboundStats {
        UplinkVideoOutboundStats(reason: reason, width: width, height: height, framesPerSecond: 30)
    }
}
