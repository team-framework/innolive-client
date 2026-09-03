import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeModelsTests: XCTestCase {
    func testBroadcastSettingsNormalizationTrimsTitleAndCapsBothTextFields() {
        let title = " \n" + String(repeating: "방", count: 120) + " \t"
        let description = String(repeating: "설명", count: 2_501)
        let settings = YouTubeBroadcastSettings(
            title: title,
            description: description,
            privacy: .unlisted,
            audience: .madeForKids
        )

        let normalized = settings.normalized

        XCTAssertEqual(normalized.title, String(repeating: "방", count: YouTubeBroadcastSettings.maxTitleLength))
        XCTAssertEqual(normalized.description, String(repeating: "설명", count: YouTubeBroadcastSettings.maxDescriptionLength / 2))
        XCTAssertEqual(normalized.privacy, .unlisted)
        XCTAssertEqual(normalized.audience, .madeForKids)
    }

    func testBroadcastSessionDecodesSnakeCaseKeysAndDates() throws {
        let data = Data(
            """
            {
              "session_id": "session-123",
              "owner_token": "fixture-owner",
              "stream": {
                "status": "future_status",
                "started_at": "1970-01-01T00:00:00.123Z",
                "stopped_at": null,
                "publisher_active": true,
                "last_error": null,
                "reconnect_attempts": 2,
                "stop_reason": null,
                "paused_at": "1970-01-01T00:00:01Z",
                "broadcast_phase": "future_phase"
              }
            }
            """.utf8
        )

        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: data)

        XCTAssertEqual(session.sessionID, "session-123")
        XCTAssertEqual(session.ownerToken, "fixture-owner")
        XCTAssertEqual(session.stream.status, "future_status")
        XCTAssertEqual(session.stream.broadcastPhase, "future_phase")
        XCTAssertEqual(session.stream.startedAtDate?.timeIntervalSince1970 ?? -1, 0.123, accuracy: 0.0001)
        XCTAssertEqual(session.stream.pausedAtDate?.timeIntervalSince1970 ?? -1, 1, accuracy: 0.0001)
    }

    func testVideoTrackDecodingPreservesUnknownReadyState() throws {
        let data = Data(
            """
            {
              "id": "track-123",
              "kind": "video",
              "ready_state": "future_ready_state"
            }
            """.utf8
        )

        let track = try JSONDecoder().decode(YouTubeVideoTrackState.self, from: data)

        XCTAssertEqual(track.id, "track-123")
        XCTAssertEqual(track.kind, "video")
        XCTAssertEqual(track.readyState, "future_ready_state")
    }

    func testMarkedStoppedByUserKeepsExistingValuesAndUsesIdlePhase() throws {
        let data = Data(
            """
            {
              "status": "live",
              "started_at": "2026-09-02T12:00:00Z",
              "stopped_at": "2026-09-02T12:10:00Z",
              "publisher_active": true,
              "last_error": "temporary",
              "reconnect_attempts": 3,
              "stop_reason": "provider_stopped",
              "paused_at": "2026-09-02T12:05:00Z",
              "broadcast_phase": "live"
            }
            """.utf8
        )
        let source = try JSONDecoder().decode(YouTubeStreamState.self, from: data)

        let stopped = source.markedStoppedByUser()

        XCTAssertEqual(stopped.status, "stopped")
        XCTAssertNil(stopped.startedAt)
        XCTAssertEqual(stopped.stoppedAt, source.stoppedAt)
        XCTAssertEqual(stopped.publisherActive, source.publisherActive)
        XCTAssertEqual(stopped.lastError, source.lastError)
        XCTAssertEqual(stopped.reconnectAttempts, source.reconnectAttempts)
        XCTAssertEqual(stopped.stopReason, source.stopReason)
        XCTAssertEqual(stopped.pausedAt, source.pausedAt)
        XCTAssertEqual(stopped.broadcastPhase, "idle")
    }

    func testMarkedStoppedByUserSetsUserRequestedReasonWhenReasonIsMissing() throws {
        let data = Data(
            """
            {
              "status": "live",
              "started_at": null,
              "stopped_at": null,
              "publisher_active": false,
              "last_error": null,
              "reconnect_attempts": 0,
              "stop_reason": null,
              "paused_at": null,
              "broadcast_phase": "live"
            }
            """.utf8
        )
        let source = try JSONDecoder().decode(YouTubeStreamState.self, from: data)

        XCTAssertEqual(source.markedStoppedByUser().stopReason, "user_requested")
    }
}
