import XCTest

@testable import InnoLive

/// innolive-server#505: YouTube 요청 한도와 방송 준비 거절 사유를 일반 오류 문구로 가리지 않는다.
@MainActor
final class YouTubeRateLimitMessageTests: XCTestCase {
    private let generic = String(localized: "YouTube 요청을 처리하지 못했습니다.")

    func testBroadcastRejectionCodesHaveDedicatedMessages() {
        let expected = [
            "streaming_rate_limited": String(localized: "이 YouTube 채널이 YouTube 요청 한도에 도달했습니다. 잠시 후 다시 시도하거나 다른 채널을 선택해 주세요."),
            "streaming_quota_exceeded": String(localized: "오늘 YouTube 연동 한도를 모두 사용했습니다. 한국 시간 오후 4시(겨울에는 오후 5시)에 초기화됩니다."),
            "channel_in_use_by_other_account": String(localized: "다른 InnoLive 계정이 이 채널로 방송 중입니다. 그 방송이 끝난 뒤 다시 시도해 주세요."),
            "youtube_channel_required": String(localized: "연결된 YouTube 채널이 여러 개입니다. 송출할 채널을 선택해 주세요."),
        ]
        for (code, message) in expected {
            let error = YouTubeAPIError.api(code: code, fallback: "server text", helpURL: nil)
            XCTAssertEqual(error.userMessage, message, code)
            XCTAssertNotEqual(error.userMessage, generic, code)
        }
    }

    func testRateLimitedGoLiveTargetExplainsTheChannelLimit() {
        let limited = BroadcastTargetFailure(provider: "youtube", code: "streaming_rate_limited", message: nil)
        XCTAssertTrue(limited.isRateLimited)
        XCTAssertEqual(
            YouTubeIntegration.liveStartFailureMessage(limited),
            "YouTube: " + String(localized: "채널 요청 한도로 라이브를 시작하지 못했습니다. 다른 플랫폼의 방송은 유지됩니다. 잠시 후 다시 시도하거나 다른 채널을 선택해 주세요.", table: "Simulcast")
        )

        let other = BroadcastTargetFailure(provider: "chzzk", code: "streaming_golive_failed", message: nil)
        XCTAssertFalse(other.isRateLimited)
        XCTAssertEqual(
            YouTubeIntegration.liveStartFailureMessage(other),
            BroadcastSettingsProvider.chzzk.title + ": " + String(localized: "라이브 시작에 실패했습니다. 다른 플랫폼의 방송은 유지됩니다.", table: "Simulcast")
        )
    }

    func testFailedTargetDecodesTheServerCode() throws {
        let payload = #"{"provider":"youtube","code":"streaming_rate_limited","message":"The streaming platform is limiting requests from this account. Try again later."}"#
        let failure = try JSONDecoder().decode(BroadcastTargetFailure.self, from: Data(payload.utf8))
        XCTAssertTrue(failure.isRateLimited)
    }
}
