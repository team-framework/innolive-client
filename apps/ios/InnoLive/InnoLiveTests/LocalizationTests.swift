import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class LocalizationTests: XCTestCase {
    func testAppBundlesAllSupportedLanguages() {
        XCTAssertTrue(Set(["ko", "en", "ja"]).isSubset(of: Set(Bundle.main.localizations)))
        XCTAssertEqual(Bundle.main.developmentLocalization, "ko")
    }

    func testSignInIsTranslatedInEachLanguage() throws {
        for (language, expected) in [("ko", "로그인"), ("en", "Sign In"), ("ja", "ログイン")] {
            let bundle = try localizedBundle(language)
            XCTAssertEqual(bundle.localizedString(forKey: "로그인", value: nil, table: nil), expected)
        }
    }

    func testPermissionDescriptionsAreBundledForEachLanguage() throws {
        for language in ["ko", "en", "ja"] {
            let bundle = try localizedBundle(language)
            for key in ["NSCameraUsageDescription", "NSMicrophoneUsageDescription"] {
                let description = bundle.localizedString(forKey: key, value: "MISSING", table: "InfoPlist")
                XCTAssertNotEqual(description, "MISSING", "\(language): \(key)")
                XCTAssertFalse(description.isEmpty)
                if language != "ko" {
                    XCTAssertNil(description.range(of: "[가-힣]", options: .regularExpression))
                }
            }
        }
    }

    func testDisplayTitlesUseLocalizationWithoutChangingWireValues() {
        XCTAssertEqual(
            YouTubeBroadcastPrivacy.public.title,
            Bundle.main.localizedString(forKey: "공개", value: nil, table: nil)
        )
        XCTAssertEqual(YouTubeBroadcastPrivacy.public.rawValue, "public")
        XCTAssertEqual(YouTubeBroadcastPrivacy.unlisted.rawValue, "unlisted")
        XCTAssertEqual(YouTubeBroadcastAudience.notMadeForKids.rawValue, "not_made_for_kids")
    }

    func testClientErrorsUseLocalizedMessages() {
        let expired = Bundle.main.localizedString(
            forKey: "로그인이 만료되었습니다. 다시 로그인해 주세요.", value: nil, table: nil
        )
        XCTAssertEqual(ReferenceFaceAPIError.unauthorized.userMessage, expired)
        XCTAssertEqual(YouTubeAPIError.unauthorized.userMessage, expired)
        XCTAssertEqual(
            WebRTCVideoUplinkError.cancelled.errorDescription,
            Bundle.main.localizedString(forKey: "영상 연결이 취소되었습니다.", value: nil, table: nil)
        )
    }

    func testUnknownYouTubeServerMessagesAreNotShownToUsers() {
        let message = "서버가 제공한 사용자 메시지 / User content / ユーザーの内容"
        XCTAssertEqual(ReferenceFaceAPIError.api(code: "future_error", fallback: message).userMessage, message)
        XCTAssertEqual(
            YouTubeAPIError.api(code: "future_error", fallback: message, helpURL: nil).userMessage,
            String(localized: "YouTube 요청을 처리하지 못했습니다.")
        )
    }

    func testPlanUsageLabelsAreTranslatedInSupportedLanguages() throws {
        for language in ["ko", "en", "ja"] {
            let bundle = try localizedBundle(language)
            for key in ["사용량 구분", "서버", "온디바이스", "이 요금제는 서버 송출을 지원하지 않습니다.", "1회 방송 한도까지 10분 남았습니다.", "요금제 및 사용량", "월 방송 한도", "1회 방송 한도", "소진", "잠김", "마지막 조회값 · 재시도 중"] {
                let label = bundle.localizedString(forKey: key, value: "MISSING", table: nil)
                XCTAssertNotEqual(label, "MISSING")
                if language != "ko" { XCTAssertNil(label.range(of: "[가-힣]", options: .regularExpression)) }
            }
        }
    }

    func testBroadcastGuideTableIsTranslatedInEachLanguage() throws {
        var keysByLanguage: [String: Set<String>] = [:]
        for language in ["ko", "en", "ja"] {
            let bundle = try localizedBundle(language)
            let path = try XCTUnwrap(bundle.path(forResource: "BroadcastGuide", ofType: "strings"), language)
            let table = try XCTUnwrap(NSDictionary(contentsOfFile: path) as? [String: String], language)
            keysByLanguage[language] = Set(table.keys)
            if language != "ko" {
                for (key, value) in table {
                    XCTAssertNil(value.range(of: "[가-힣]", options: .regularExpression), "\(language): \(key)")
                }
            }
        }
        XCTAssertFalse(keysByLanguage["ko", default: []].isEmpty)
        XCTAssertEqual(keysByLanguage["en"], keysByLanguage["ko"])
        XCTAssertEqual(keysByLanguage["ja"], keysByLanguage["ko"])
    }

    func testPrivacyPolicyLinkUsesAppLanguage() {
        let language = Bundle.main.preferredLocalizations.first ?? "ko"
        XCTAssertEqual(InnoLiveLinks.privacyPolicyURL?.path, "/\(language)/privacy")
        XCTAssertEqual(InnoLiveLinks.privacyPolicyURL?.scheme, "https")
        XCTAssertEqual(InnoLiveLinks.privacyPolicyURL?.host, "innolive.studio")
    }

    /// innolive-server#505: 방송 준비 오류 안내가 세 언어 모두에 있고, 한국어 외에는 한글이 섞이지 않는다.
    func testBroadcastLimitErrorsAreTranslatedInEachLanguage() throws {
        let defaultKeys = [
            "이 YouTube 채널이 YouTube 요청 한도에 도달했습니다. 잠시 후 다시 시도하거나 다른 채널을 선택해 주세요.",
            "오늘 YouTube 연동 한도를 모두 사용했습니다. 한국 시간 오후 4시(겨울에는 오후 5시)에 초기화됩니다.",
            "다른 InnoLive 계정이 이 채널로 방송 중입니다. 그 방송이 끝난 뒤 다시 시도해 주세요.",
            "연결된 YouTube 채널이 여러 개입니다. 송출할 채널을 선택해 주세요.",
        ]
        let simulcastKeys = [
            "채널 요청 한도로 라이브를 시작하지 못했습니다. 다른 플랫폼의 방송은 유지됩니다. 잠시 후 다시 시도하거나 다른 채널을 선택해 주세요.",
            "채널 요청 한도",
        ]
        for language in ["ko", "en", "ja"] {
            let bundle = try localizedBundle(language)
            for (table, keys) in [(nil, defaultKeys), ("Simulcast", simulcastKeys)] as [(String?, [String])] {
                for key in keys {
                    let text = bundle.localizedString(forKey: key, value: "MISSING", table: table)
                    XCTAssertNotEqual(text, "MISSING", "\(language): \(key)")
                    if language != "ko" { XCTAssertNil(text.range(of: "[가-힣]", options: .regularExpression), "\(language): \(key)") }
                }
            }
        }
    }

    private func localizedBundle(_ language: String) throws -> Bundle {
        let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"))
        return try XCTUnwrap(Bundle(path: path))
    }
}
