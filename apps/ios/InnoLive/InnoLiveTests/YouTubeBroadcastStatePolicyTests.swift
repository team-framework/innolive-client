import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeBroadcastStatePolicyTests: XCTestCase {
    func testLiveStreamingStateIsActiveAndCanPause() throws {
        let stream = try decodeStream(status: "streaming", broadcastPhase: "live")
        let policy = YouTubeBroadcastStatePolicy(stream: stream, isChangingStreamState: false)

        XCTAssertEqual(policy.broadcastPhase, .live)
        XCTAssertEqual(policy.streamStatus, .some(.streaming))
        XCTAssertTrue(policy.isBroadcastActive)
        XCTAssertTrue(policy.hasStartedBroadcast)
        XCTAssertTrue(policy.isBroadcastSettingsLocked)
        XCTAssertFalse(policy.isBroadcastPaused)
        XCTAssertTrue(policy.canPauseBroadcast)
        XCTAssertFalse(policy.canResumeBroadcast)
        XCTAssertTrue(policy.canChangePauseState)
    }

    func testPausedStateCanResumeWithoutPausingAgain() throws {
        let stream = try decodeStream(
            status: "paused",
            broadcastPhase: "live",
            pausedAt: "2026-09-02T12:05:00Z"
        )
        let policy = YouTubeBroadcastStatePolicy(stream: stream, isChangingStreamState: false)

        XCTAssertTrue(policy.isBroadcastActive)
        XCTAssertTrue(policy.isBroadcastPaused)
        XCTAssertFalse(policy.canPauseBroadcast)
        XCTAssertTrue(policy.canResumeBroadcast)
        XCTAssertTrue(policy.canChangePauseState)
        XCTAssertTrue(policy.streamStatusText.hasPrefix("YouTube 송출 일시 중지됨"))
    }

    func testStoppedStateIsNotActiveAndUnlocksSettings() throws {
        let stream = try decodeStream(status: "stopped", broadcastPhase: "live")
        let policy = YouTubeBroadcastStatePolicy(stream: stream, isChangingStreamState: false)

        XCTAssertEqual(policy.streamStatus, .some(.stopped))
        XCTAssertFalse(policy.isBroadcastActive)
        XCTAssertFalse(policy.hasStartedBroadcast)
        XCTAssertFalse(policy.isBroadcastSettingsLocked)
        XCTAssertFalse(policy.isBroadcastPaused)
        XCTAssertFalse(policy.canPauseBroadcast)
        XCTAssertFalse(policy.canResumeBroadcast)
        XCTAssertEqual(policy.streamStatusText, "YouTube 송출 중지됨")
    }

    func testUnknownStatePreservesRawValuesAndUsesSafePolicyFallback() throws {
        let stream = try decodeStream(status: "future_status", broadcastPhase: "future_phase")
        let policy = YouTubeBroadcastStatePolicy(stream: stream, isChangingStreamState: false)

        XCTAssertEqual(stream.statusValue, .unknown("future_status"))
        XCTAssertEqual(stream.broadcastPhaseValue, .unknown("future_phase"))
        XCTAssertEqual(policy.broadcastPhase, .unknown("future_phase"))
        XCTAssertEqual(policy.streamStatus, .some(.unknown("future_status")))
        XCTAssertFalse(policy.isBroadcastActive)
        XCTAssertFalse(policy.isWaitingForBroadcastStart)
        XCTAssertFalse(policy.isBroadcastSettingsLocked)
        XCTAssertFalse(policy.isBroadcastPaused)
        XCTAssertFalse(policy.canPauseBroadcast)
        XCTAssertFalse(policy.canResumeBroadcast)
        XCTAssertEqual(policy.streamStatusText, "YouTube 송출 대기")
    }

    private func decodeStream(
        status: String,
        broadcastPhase: String,
        pausedAt: String? = nil
    ) throws -> YouTubeStreamState {
        let pausedAtJSON = pausedAt.map { "\"\($0)\"" } ?? "null"
        let data = Data(
            """
            {
              "status": "\(status)",
              "started_at": "2026-09-02T12:00:00Z",
              "stopped_at": null,
              "publisher_active": true,
              "last_error": null,
              "reconnect_attempts": 0,
              "stop_reason": null,
              "paused_at": \(pausedAtJSON),
              "broadcast_phase": "\(broadcastPhase)"
            }
            """.utf8
        )
        return try JSONDecoder().decode(YouTubeStreamState.self, from: data)
    }
}
