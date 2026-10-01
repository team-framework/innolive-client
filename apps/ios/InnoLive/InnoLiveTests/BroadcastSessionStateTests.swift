import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastSessionStateTests: XCTestCase {
    func testTargetsDecodeForYouTubeChzzkAndDualBroadcasts() throws {
        for providers in [["youtube"], ["chzzk"], ["youtube", "chzzk"]] {
            let response = try SessionStateFixture.decode(providers: providers)
            XCTAssertEqual(response.details.targets?.map(\.provider), providers)
            XCTAssertEqual(response.details.broadcastResolution, "720p")
        }
    }

    func testRemainingTimeDistinguishesMissingNullAndNumber() throws {
        for (value, expected) in [(nil, BroadcastRemainingTime.missing), ("null", .unlimitedOrInactive), ("120", .seconds(120))] {
            let response = try SessionStateFixture.decode(remaining: value)
            XCTAssertEqual(response.details.remainingTime, expected)
        }
    }

    func testUnknownValuesSurviveDecoding() throws {
        let response = try SessionStateFixture.decode(providers: ["future_provider"], phase: "future_phase", status: "future_status")
        let target = try XCTUnwrap(response.details.targets?.first)
        XCTAssertEqual(target.stream.broadcastPhaseValue, .unknown("future_phase"))
        XCTAssertEqual(target.stream.statusValue, .unknown("future_status"))
        XCTAssertEqual(response.details.notices.first?.code, "future_notice")
        let policy = YouTubeBroadcastStatePolicy(stream: response.stream, isChangingStreamState: false, targets: response.details.targets)
        XCTAssertFalse(policy.hasStartedBroadcast)
        XCTAssertEqual(policy.visibleTargets.count, 1)
    }

    func testCreationSharesSessionDetails() throws {
        let data = Data(SessionStateFixture.json(providers: ["chzzk"], remaining: "null").utf8)
        let created = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: data)
        XCTAssertEqual(created.details.targets?.first?.provider, "chzzk")
        XCTAssertEqual(created.details.remainingTime, .unlimitedOrInactive)
        XCTAssertEqual(created.media?.rawVideoTrack?.readyStateValue, .live)
    }

    func testBareStopPreservesRemainingTargetAndMetadata() throws {
        var state = BroadcastSessionSnapshot()
        state.apply(try SessionStateFixture.decode(providers: ["youtube", "chzzk"], remaining: "120"))
        let stop = try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(SessionStateFixture.stream(phase: "idle", status: "stopped").utf8))
        XCTAssertFalse(stop.isFullSnapshot)
        state.apply(stop, provider: "youtube")
        XCTAssertEqual(state.details.remainingTime, .seconds(120))
        XCTAssertEqual(state.details.targets?.last?.stream.statusValue, .streaming)
        XCTAssertNotNil(state.media?.rawVideoTrack)
        let policy = YouTubeBroadcastStatePolicy(stream: state.stream, isChangingStreamState: false, targets: state.details.targets)
        XCTAssertTrue(policy.hasStartedBroadcast)
        XCTAssertEqual(policy.visibleTargets.map(\.provider), ["chzzk"])
    }

    func testAuthoritativeSnapshotClearsOptionalState() throws {
        var state = BroadcastSessionSnapshot()
        state.apply(try SessionStateFixture.decode(providers: ["youtube"], remaining: "120"))
        state.apply(try SessionStateFixture.decode(providers: [], phase: "idle", status: "stopped", remaining: "null"))
        XCTAssertEqual(state.details.targets, [])
        XCTAssertEqual(state.details.remainingTime, .unlimitedOrInactive)
    }

    func testLegacyStreamStillDrivesPolicyWithoutTargets() throws {
        let response = try SessionStateFixture.decode(providers: nil)
        let policy = YouTubeBroadcastStatePolicy(stream: response.stream, isChangingStreamState: false, targets: response.details.targets)
        XCTAssertTrue(policy.hasStartedBroadcast)
        XCTAssertTrue(policy.canPauseBroadcast)
    }

    func testSwitchFailureAndUpgradeOptionsDecode() throws {
        let payload = """
        {"resolution_switch":{"status":"future_switch","resolution":"fhd","targets":["youtube","chzzk"],"failed_targets":[{"provider":"youtube","code":"future_failure"}],"started_at":"2026-09-30T00:00:00Z"},
        "upgrade_offer":{"resolution":"fhd","mode":"future_mode","units_from":1,"units_to":2,"remaining_seconds_after":null,"expires_at":"2026-09-30T01:00:00Z","options":[{"mode":"future_mode","resolution":"fhd","targets":["chzzk"],"units_to":2,"remaining_seconds_after":null,"needs_settings":false,"restarts_broadcast":true,"restart_effects":[{"provider":"chzzk","same_link":true,"gap_seconds":30}]}]},
        "warnings":[{"code":"future_warning","message":"setting unavailable"}]}
        """
        let details = try JSONDecoder().decode(BroadcastSessionDetails.self, from: Data(payload.utf8))
        XCTAssertEqual(details.resolutionSwitch?.failedTargets?.first?.code, "future_failure")
        XCTAssertEqual(details.resolutionSwitch?.status, "future_switch")
        XCTAssertEqual(details.upgradeOffer?.options?.first?.restartEffects.first?.gapSeconds, 30)
        XCTAssertEqual(details.upgradeOffer?.remainingSecondsAfter, .unlimitedOrInactive)
        XCTAssertEqual(details.warnings.first?.code, "future_warning")
    }

    func testCommittedContractFixturesDecodeAndKeepNullableTimeMeaning() throws {
        let cases: [(String, [String]?, BroadcastRemainingTime)] = [
            ("youtube", ["youtube"], .seconds(120)),
            ("chzzk", ["chzzk"], .seconds(120)),
            ("dual", ["youtube", "chzzk"], .seconds(120)),
            ("null", [], .unlimitedOrInactive),
            ("missing", nil, .missing),
            ("unknown", ["future_provider"], .unlimitedOrInactive)
        ]
        for (name, providers, remaining) in cases {
            let response = try SessionStateFixture.committed(name)
            XCTAssertEqual(response.details.targets?.map(\.provider), providers, name)
            XCTAssertEqual(response.details.remainingTime, remaining, name)
        }
        let dual = try SessionStateFixture.committed("dual")
        XCTAssertEqual(dual.details.upgradeOffer?.remainingSecondsAfter, .unlimitedOrInactive)
        XCTAssertEqual(dual.details.upgradeOffer?.options?.first?.remainingSecondsAfter, .seconds(120))
        XCTAssertEqual(dual.details.resolutionSwitch?.status, "switching")
        let partial = try SessionStateFixture.committed("golive-partial")
        XCTAssertFalse(partial.isFullSnapshot)
        XCTAssertEqual(partial.details.failedTargets.first?.provider, "youtube")
        var snapshot = BroadcastSessionSnapshot()
        snapshot.apply(dual)
        snapshot.apply(partial)
        XCTAssertEqual(snapshot.details.targets?.last?.stream.statusValue, .streaming)
        XCTAssertEqual(snapshot.details.failedTargets.first?.code, "broadcast_not_ready")
        XCTAssertEqual(snapshot.details.remainingTime, .seconds(120))
    }

    func testPrepareWarningsSurviveGetButClearOnNextPrepare() throws {
        var snapshot = BroadcastSessionSnapshot()
        let prepared = try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(
            SessionStateFixture.json().replacingOccurrences(of: "\"notices\":", with: "\"warnings\":[{\"code\":\"setting_warning\",\"message\":\"setting failed\"}],\"notices\":").utf8
        ))
        snapshot.apply(prepared, clearsWarnings: true)
        XCTAssertEqual(snapshot.details.warnings.first?.code, "setting_warning")
        snapshot.apply(try SessionStateFixture.decode())
        XCTAssertEqual(snapshot.details.warnings.first?.code, "setting_warning")
        snapshot.apply(try SessionStateFixture.decode(), clearsWarnings: true)
        XCTAssertTrue(snapshot.details.warnings.isEmpty)
    }
}

enum SessionStateFixture {
    static func committed(_ name: String) throws -> YouTubeSessionResponse {
        let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let url = directory.appendingPathComponent("../../../../contracts/fixtures/broadcast-session-state-\(name).v1.json").standardizedFileURL
        return try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(contentsOf: url))
    }
    static func stream(phase: String = "live", status: String = "streaming") -> String {
        """
        {"status":"\(status)","started_at":null,"stopped_at":null,"publisher_active":true,"last_error":null,"reconnect_attempts":0,"stop_reason":null,"paused_at":null,"broadcast_phase":"\(phase)"}
        """
    }

    static func json(providers: [String]? = ["youtube"], phase: String = "live", status: String = "streaming", remaining: String? = nil) -> String {
        let targets = providers.map { providers in
            ",\"targets\":[" + providers.map { "{\"provider\":\"\($0)\",\"stream\":\(stream(phase: phase, status: status))}" }.joined(separator: ",") + "]"
        } ?? ""
        let remainingField = remaining.map { ",\"broadcast_remaining_seconds\":\($0)" } ?? ""
        return """
        {"session_id":"state-session","owner_token":"fixture-owner","provider":"\(providers?.first ?? "youtube")","ai_processing":"server","stream":\(stream(phase: phase, status: status)),"media":{"anonymization_enabled":false,"raw_video_track":{"id":"video","kind":"video","ready_state":"live"}},"broadcast_resolution":"720p","notices":[{"code":"future_notice","at":"2026-09-30T00:00:00Z"}]\(targets)\(remainingField)}
        """
    }

    static func decode(providers: [String]? = ["youtube"], phase: String = "live", status: String = "streaming", remaining: String? = nil) throws -> YouTubeSessionResponse {
        try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(json(providers: providers, phase: phase, status: status, remaining: remaining).utf8))
    }

    static func token(_ user: String, version: Int = 0) -> String {
        let payload = Data("{\"sub\":\"\(user)\",\"version\":\(version)}".utf8).base64EncodedString()
        return "header.\(payload).signature"
    }
}
