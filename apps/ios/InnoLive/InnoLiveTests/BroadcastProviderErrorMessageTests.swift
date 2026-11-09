import Foundation
import XCTest

@testable import InnoLive

/// 공통 서버 오류는 치지직 방송에도 표시되므로 특정 플랫폼 이름을 쓰지 않는다.
@MainActor
final class BroadcastProviderErrorMessageTests: XCTestCase {
    private static let sharedErrors: [YouTubeAPIError] = [
        .featureUnavailable, .streamingNotConnected, .configuration, .transport, .response,
    ]
    private static let sharedCodes = [
        "bad_request", "streaming_not_connected", "streaming_reconnect_required", "broadcast_not_ready",
        "broadcast_not_prepared", "conflict", "stream_already_active", "stream_not_active",
        "stream_already_paused", "stream_not_paused", "not_supported", "streaming_prepare_failed",
        "streaming_golive_failed", "future_error",
    ]
    private static let youtubeOnlyCodes = [
        "invalid_auth_code", "youtube_channel_missing", "youtube_token_exchange_failed", "live_streaming_blocked",
        "streaming_rate_limited", "streaming_quota_exceeded", "youtube_channel_required",
    ]
    private static let neutralKeys = [
        "이 서버에는 플랫폼 계정 연결 기능이 아직 구성되지 않았습니다.",
        "플랫폼 계정을 먼저 연결해 주세요.",
        "플랫폼 계정을 다시 연결해 주세요.",
        "서버 연결 설정이 필요합니다.",
        "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        "서버 응답을 확인하지 못했습니다. 다시 시도해 주세요.",
        "방송 설정을 확인한 뒤 다시 시도해 주세요.",
        "플랫폼이 아직 영상을 받을 준비가 되지 않았습니다. 잠시 후 다시 시도해 주세요.",
        "방송을 먼저 준비해 주세요.",
        "카메라 영상을 연결한 뒤 송출을 다시 시작해 주세요.",
        "송출이 이미 진행 중입니다.",
        "송출 중이 아닙니다.",
        "송출이 이미 일시 중지되어 있습니다.",
        "일시 중지된 송출이 아닙니다.",
        "이 서버에는 송출 기능이 아직 구성되지 않았습니다.",
        "송출을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        "방송을 라이브로 전환하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        "방송 요청을 처리하지 못했습니다.",
    ]

    func testSharedErrorsDoNotNameAPlatform() {
        for error in Self.sharedErrors {
            XCTAssertFalse(error.userMessage.contains("YouTube"), "\(error)")
        }
        for code in Self.sharedCodes {
            XCTAssertFalse(apiError(code).userMessage.contains("YouTube"), code)
        }
    }

    func testYouTubeOnlyCodesKeepTheirGuidance() {
        for code in Self.youtubeOnlyCodes {
            XCTAssertTrue(apiError(code).userMessage.contains("YouTube"), code)
        }
    }

    func testBroadcastNotPreparedHasADedicatedMessage() {
        XCTAssertEqual(apiError("broadcast_not_prepared").userMessage, String(localized: "방송을 먼저 준비해 주세요."))
        XCTAssertNotEqual(apiError("broadcast_not_prepared").userMessage, apiError("future_error").userMessage)
    }

    func testNeutralMessagesAreTranslatedInEachLanguage() throws {
        for language in ["ko", "en", "ja"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), language)
            let bundle = try XCTUnwrap(Bundle(path: path), language)
            for key in Self.neutralKeys {
                let message = bundle.localizedString(forKey: key, value: "MISSING", table: nil)
                XCTAssertNotEqual(message, "MISSING", "\(language): \(key)")
                XCTAssertFalse(message.contains("YouTube"), "\(language): \(key)")
                if language != "ko" {
                    XCTAssertNil(message.range(of: "[가-힣]", options: .regularExpression), "\(language): \(key)")
                }
            }
        }
    }

    func testCHZZKSettingsErrorNamesThePlatformOnce() {
        let integration = makeIntegration()

        integration.handleSettingsError(YouTubeAPIError.transport, provider: .chzzk)

        XCTAssertEqual(integration.errorMessage,
                       BroadcastSettingsProvider.chzzk.title + ": " + YouTubeAPIError.transport.userMessage)
        XCTAssertFalse(integration.errorMessage?.contains("YouTube") ?? true)
    }

    func testCHZZKSettingsErrorKeepsTheHelpLink() throws {
        let integration = makeIntegration()
        let helpURL = try XCTUnwrap(URL(string: "https://example.invalid/help"))

        integration.handleSettingsError(YouTubeAPIError.api(code: "bad_request", fallback: "", helpURL: helpURL), provider: .chzzk)

        XCTAssertEqual(integration.helpURL, helpURL)
    }

    func testCHZZKSettingsErrorKeepsFeatureAvailability() {
        let integration = makeIntegration()

        integration.handleSettingsError(YouTubeAPIError.featureUnavailable, provider: .chzzk)

        XCTAssertTrue(integration.isFeatureAvailable)
    }

    private func apiError(_ code: String) -> YouTubeAPIError {
        .api(code: code, fallback: "server text", helpURL: nil)
    }

    private func makeIntegration() -> YouTubeIntegration {
        let suite = "BroadcastProviderErrorMessageTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let api = YouTubeAPI(urlSession: URLSession(configuration: .ephemeral),
                             serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        return YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api)
    }
}
