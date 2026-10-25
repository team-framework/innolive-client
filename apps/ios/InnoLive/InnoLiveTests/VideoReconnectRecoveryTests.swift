import XCTest
@testable import InnoLive

/// 서버 교체 배포 중 영상 재연결(#791). 서버는 recovery 정책 시간 동안 세션을 복구 대기로 두므로,
/// 그 안의 한 시도 실패가 방송을 끝내면 안 된다.
@MainActor
final class VideoReconnectRecoveryTests: XCTestCase {
    func testRecoveryPolicyDecodesServerValues() throws {
        let data = Data(#"{"window_ms": 30000, "debounce_ms": 1500, "max_attempts": 6}"#.utf8)
        let policy = try JSONDecoder().decode(WebRTCRecoveryPolicy.self, from: data)

        XCTAssertEqual(policy, WebRTCRecoveryPolicy(windowMilliseconds: 30_000, debounceMilliseconds: 1_500, maximumAttempts: 6))
        XCTAssertEqual(policy.window, .seconds(30))
        XCTAssertEqual(policy.retryDelay, .milliseconds(1_500))
    }

    func testRecoveryPolicyFallsBackToDefaultsForMissingOrInvalidValues() throws {
        let missing = try JSONDecoder().decode(WebRTCRecoveryPolicy.self, from: Data("{}".utf8))
        XCTAssertEqual(missing, .default)

        let invalid = try JSONDecoder().decode(
            WebRTCRecoveryPolicy.self,
            from: Data(#"{"window_ms": 0, "debounce_ms": -1, "max_attempts": 0}"#.utf8)
        )
        XCTAssertEqual(invalid, .default)
    }

    func testRetryDelayHasOneSecondFloor() {
        let policy = WebRTCRecoveryPolicy(windowMilliseconds: 50_000, debounceMilliseconds: 0, maximumAttempts: 10)
        XCTAssertEqual(policy.retryDelay, .seconds(1))
    }

    func testFailureDuringRecoveryKeepsBroadcastAlive() {
        let uplink = WebRTCVideoUplink()
        uplink.isRecoveringExistingSession = true

        uplink.fail("signaling refused")

        XCTAssertEqual(uplink.state, .connecting, "a failed attempt must not end the broadcast while recovery continues")
        XCTAssertEqual(uplink.errorMessage, "signaling refused")
    }

    func testFailureOutsideRecoveryStillFails() {
        let uplink = WebRTCVideoUplink()

        uplink.fail("camera unavailable")

        XCTAssertEqual(uplink.state, .failed)
    }

    func testGivingUpRecoveryFailsAndClearsRecoveryFlag() {
        let uplink = WebRTCVideoUplink()
        uplink.isRecoveringExistingSession = true

        uplink.markReconnectFailed("could not recover")

        XCTAssertEqual(uplink.state, .failed)
        XCTAssertFalse(uplink.isRecoveringExistingSession)
    }
}
