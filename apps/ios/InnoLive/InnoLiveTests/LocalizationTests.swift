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

    func testUnknownServerMessagesAreNotTreatedAsLocalizationKeys() {
        let message = "서버가 제공한 사용자 메시지 / User content / ユーザーの内容"
        XCTAssertEqual(ReferenceFaceAPIError.api(code: "future_error", fallback: message).userMessage, message)
        XCTAssertEqual(YouTubeAPIError.api(code: "future_error", fallback: message, helpURL: nil).userMessage, message)
    }

    func testPrivacyPolicyLinkUsesAppLanguage() {
        let language = Bundle.main.preferredLocalizations.first ?? "ko"
        XCTAssertEqual(InnoLiveLinks.privacyPolicyURL?.path, "/\(language)/privacy")
        XCTAssertEqual(InnoLiveLinks.privacyPolicyURL?.scheme, "https")
        XCTAssertEqual(InnoLiveLinks.privacyPolicyURL?.host, "innolive.studio")
    }

    private func localizedBundle(_ language: String) throws -> Bundle {
        let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"))
        return try XCTUnwrap(Bundle(path: path))
    }
}
