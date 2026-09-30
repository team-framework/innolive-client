import XCTest
@testable import InnoLive

@MainActor
final class BroadcastProblemTests: XCTestCase {
    func testRecoveryRoutesForAllDocumentedErrors() {
        let groups: [(BroadcastProblem.Action, [String])] = [
            (.accounts, ["streaming_not_connected", "streaming_reconnect_required"]),
            (.plan, ["plan_resolution_not_allowed", "plan_simulcast_not_allowed", "plan_server_streaming_not_allowed", "monthly_limit_exhausted"]),
            (.retry, ["streaming_prepare_failed", "streaming_golive_failed", "streaming_update_failed"]),
            (.none, ["broadcast_limit_reached", "egress_slots_exhausted", "capacity_exceeded", "server_busy", "streaming_quota_exceeded", "streaming_rate_limited", "streaming_account_in_use", "withdrawal_in_progress"])
        ]
        for (action, codes) in groups {
            for code in codes {
                let value = problem(code)
                XCTAssertEqual(value.action, action, code)
                XCTAssertEqual(value.presentation, .alert, code)
                XCTAssertFalse(value.userMessage.isEmpty, code)
                XCTAssertNotEqual(value.userMessage, "server-private-message", code)
            }
        }
    }

    func testBusySilentInlineAndConfirmationHaveNoGenericModal() {
        for code in ["resolution_switch_in_progress", "broadcast_busy", "broadcast_going_live"] {
            XCTAssertEqual(problem(code).presentation, .busy)
        }
        for code in ["stale_negotiation", "upgrade_offer_not_found"] {
            XCTAssertEqual(problem(code).presentation, .silent)
        }
        for code in ["bad_request", "field_not_changeable_live"] {
            XCTAssertEqual(problem(code).presentation, .inline)
        }
        XCTAssertEqual(problem("channel_already_live").presentation, .confirmation)
    }

    func testPlatformSpecificMessageAndHelpRoute() {
        XCTAssertNotEqual(problem("streaming_prepare_failed").userMessage, problem("streaming_prepare_failed", provider: "chzzk").userMessage)
        XCTAssertEqual(problem("live_streaming_blocked").action, .none)
        let value = BroadcastProblem(status: 403, code: "live_streaming_blocked", message: "", provider: "youtube", field: nil, reason: nil, helpURL: URL(string: "https://example.test/help"))
        XCTAssertEqual(value.action, .help)
    }

    func testLegacyResponseAndNullableFeedbackAreCompatible() throws {
        let legacy = try snapshot()
        XCTAssertNil(legacy.details.targets)
        XCTAssertTrue(legacy.details.notices.isEmpty)
        let nullable = try snapshot(extra: ["targets": NSNull(), "notices": NSNull(), "warnings": NSNull(), "resolution_switch": NSNull()])
        XCTAssertTrue(nullable.details.notices.isEmpty)
        let partial = try snapshot(extra: ["resolution_switch": ["status": "done", "resolution": "720p", "targets": [], "started_at": "fixture", "failed_targets": [["provider": "chzzk", "code": "streaming_rate_limited"]]]])
        XCTAssertEqual(partial.details.resolutionSwitch?.failedTargets?.first?.provider, "chzzk")
    }

    func testEveryNoticePersistsUntilDismissedAndDoesNotResurface() {
        var center = BroadcastNoticeCenter()
        let codes = ["broadcast_limit_30m", "broadcast_limit_10m", "broadcast_limit_reached", "monthly_usage_80", "monthly_usage_100", "monthly_limit_reached", "no_input_stopped", "channel_live_elsewhere", "platform_broadcast_ended", "youtube_quota_low"]
        let notices = codes.map { BroadcastNotice(code: $0, at: "fixture") }
        center.consume(sessionID: "a", notices: notices, warnings: [], targets: [])
        XCTAssertEqual(center.banners.count, codes.count)
        center.consume(sessionID: "a", notices: [], warnings: [], targets: [])
        XCTAssertEqual(center.banners.count, codes.count)
        for banner in center.banners { center.dismiss(banner.id) }
        center.consume(sessionID: "a", notices: notices, warnings: [], targets: [])
        XCTAssertTrue(center.banners.isEmpty)
        center.consume(sessionID: "b", notices: notices, warnings: [], targets: [])
        XCTAssertEqual(center.banners.count, codes.count)
    }

    func testWarningsAndStopReasonsShareNoticeDeduplication() throws {
        var center = BroadcastNoticeCenter()
        center.consume(sessionID: "a", notices: [.init(code: "youtube_quota_low", at: "fixture"), .init(code: "platform_broadcast_ended", at: "fixture")], warnings: [.init(code: "youtube_quota_low", message: "english")], targets: [try target("youtube", reason: "platform_ended")])
        XCTAssertEqual(center.banners.count, 2)
        for banner in center.banners { center.dismiss(banner.id) }
        center.consume(sessionID: "a", notices: [], warnings: [.init(code: "youtube_quota_low", message: "")], targets: [try target("youtube", reason: "platform_ended")])
        XCTAssertTrue(center.banners.isEmpty)
    }

    func testStopFirstNoticeLaterDoesNotRepeat() throws {
        var center = BroadcastNoticeCenter()
        center.consume(sessionID: "a", notices: [], warnings: [], targets: [try target("youtube", reason: "platform_ended")])
        XCTAssertEqual(center.banners.count, 1)
        center.dismiss(center.banners[0].id)
        center.consume(sessionID: "a", notices: [.init(code: "platform_broadcast_ended", at: "fixture")], warnings: [], targets: [])
        XCTAssertTrue(center.banners.isEmpty)
    }

    func testNormalStopsAndUnknownNoticesAreQuiet() throws {
        var center = BroadcastNoticeCenter()
        for reason in ["user_requested", "resolution_change", "target_removed", "future_reason"] {
            center.consume(sessionID: "a", notices: [.init(code: "future_notice", at: "fixture")], warnings: [], targets: [try target("youtube", reason: reason)])
        }
        XCTAssertTrue(center.banners.isEmpty)
    }

    func testUnexpectedStopReasonsAndFailuresArePlatformSpecific() throws {
        var center = BroadcastNoticeCenter()
        center.consume(sessionID: "a", notices: [], warnings: [], targets: [try target("youtube", reason: "rtmp_reconnect_exhausted"), try target("chzzk", reason: "reconnect_input_timeout")], failures: [.init(provider: "chzzk", code: "resolution_switch_canceled", message: nil)])
        XCTAssertEqual(center.banners.count, 3)
        XCTAssertTrue(center.banners.allSatisfy(\.isError))
        XCTAssertTrue(center.banners[0].message.contains("YouTube"))
    }

    func testSingleTargetStopDoesNotDiscardOtherTargetOrRelabelDefaultStream() throws {
        let integration = makeIntegration()
        defer { integration.reset() }
        let live = stream(status: "streaming", phase: "live")
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: JSONSerialization.data(withJSONObject: ["session_id": "a", "owner_token": "fixture-owner", "stream": live]))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "fixture", title: "fixture")), session: session, videoTrack: .init(id: "fixture", kind: "video", readyState: "live"))
        let response = try snapshot(extra: ["targets": [["provider": "youtube", "stream": stream(reason: "platform_ended")], ["provider": "chzzk", "stream": live]]])
        integration.applyBroadcastSnapshot(response, sessionID: "a")
        XCTAssertTrue(integration.isYouTubeBroadcastActive)
        XCTAssertTrue(integration.isYouTubeBroadcastActive)
        XCTAssertEqual(integration.noticeBanners.count, 1)
        let otherOnly = try snapshot(extra: ["targets": [["provider": "chzzk", "stream": live]]])
        integration.applyBroadcastSnapshot(otherOnly, sessionID: "a")
        XCTAssertEqual(integration.visibleBroadcastTargets.map(\.provider), ["chzzk"])
        XCTAssertTrue(integration.isYouTubeBroadcastActive)
    }

    func testBusyClearsOnlyAfterFreshSnapshotAndStaleSnapshotIsIgnored() throws {
        let integration = makeIntegration()
        defer { integration.reset() }
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: JSONSerialization.data(withJSONObject: ["session_id": "a", "owner_token": "fixture-owner", "stream": stream(status: "idle", phase: "idle")]))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "fixture", title: "fixture")), session: session, videoTrack: .init(id: "fixture", kind: "video", readyState: "live"))
        integration.showBroadcastProblem(problem("broadcast_busy"))
        XCTAssertTrue(integration.isServerBroadcastBusy)
        XCTAssertTrue(integration.isBroadcastSettingsLocked)
        XCTAssertNil(integration.errorMessage)
        integration.applyBroadcastSnapshot(try snapshot(), sessionID: "stale")
        XCTAssertTrue(integration.isServerBroadcastBusy)
        integration.applyBroadcastSnapshot(try snapshot(extra: ["resolution_switch": ["status": "switching", "resolution": "720p", "targets": [], "started_at": "fixture"]]), sessionID: "a")
        XCTAssertTrue(integration.isServerBroadcastBusy)
        integration.applyBroadcastSnapshot(try snapshot(), sessionID: "a")
        XCTAssertFalse(integration.isServerBroadcastBusy)
        XCTAssertNil(integration.problem)
    }

    func testCommittedFeedbackFixtureUsesCommonSessionModel() throws {
        let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let url = directory.appendingPathComponent("../../../../contracts/fixtures/broadcast-feedback.v1.json").standardizedFileURL
        let data = try Data(contentsOf: url)
        let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let session = try JSONDecoder().decode(YouTubeSessionResponse.self, from: JSONSerialization.data(withJSONObject: XCTUnwrap(envelope["session"])))
        var center = BroadcastNoticeCenter()
        center.consume(sessionID: "fixture-session", notices: session.details.notices, warnings: session.details.warnings,
                       targets: session.details.targets ?? [], failures: session.details.resolutionSwitch?.failedTargets ?? [])
        XCTAssertEqual(center.banners.count, 3)
        XCTAssertEqual(center.banners.filter { $0.id == "platform_broadcast_ended" }.count, 1)
        XCTAssertTrue(YouTubeBroadcastStatePolicy(stream: session.stream, isChangingStreamState: false, targets: session.details.targets).isBroadcastActive)
    }

    func testBarePartialGoLiveFailureProducesBannerWithoutEndingOtherTarget() throws {
        let integration = makeIntegration()
        defer { integration.reset() }
        let initial = try SessionStateFixture.decode(providers: ["youtube", "chzzk"])
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(SessionStateFixture.json(providers: ["youtube", "chzzk"]).utf8))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "fixture", title: "fixture")), session: session, videoTrack: .init(id: "fixture", kind: "video", readyState: "live"))
        integration.applyBroadcastSnapshot(initial, sessionID: session.sessionID)
        integration.applyBroadcastSnapshot(try SessionStateFixture.committed("golive-partial"), sessionID: session.sessionID)
        XCTAssertEqual(integration.noticeBanners.count, 1)
        XCTAssertTrue(integration.isYouTubeBroadcastActive)
        XCTAssertEqual(integration.visibleBroadcastTargets.map(\.provider), ["chzzk"])
    }

    private func problem(_ code: String, provider: String = "youtube") -> BroadcastProblem {
        .init(status: 409, code: code, message: "server-private-message", provider: provider, field: nil, reason: nil, helpURL: nil)
    }
    private func makeIntegration() -> YouTubeIntegration {
        YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: "feedback.\(UUID().uuidString)")!))
    }
    private func stream(status: String = "stopped", phase: String = "idle", reason: String? = nil) -> [String: Any] {
        ["status": status, "publisher_active": true, "reconnect_attempts": 0, "broadcast_phase": phase, "stop_reason": reason as Any? ?? NSNull()]
    }
    private func target(_ provider: String, reason: String) throws -> BroadcastTargetState {
        try JSONDecoder().decode(BroadcastTargetState.self, from: JSONSerialization.data(withJSONObject: ["provider": provider, "stream": stream(reason: reason)]))
    }
    private func snapshot(extra: [String: Any] = [:]) throws -> YouTubeSessionResponse {
        var value: [String: Any] = ["stream": stream(status: "idle", phase: "idle"), "media": ["raw_video_track": NSNull(), "anonymization_enabled": false]]
        value.merge(extra) { _, new in new }
        return try JSONDecoder().decode(YouTubeSessionResponse.self, from: JSONSerialization.data(withJSONObject: value))
    }
}
