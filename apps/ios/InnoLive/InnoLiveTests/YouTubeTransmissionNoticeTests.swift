import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeTransmissionNoticeTests: XCTestCase {
    func testPrepareIsBlockedUntilNoticeIsAcknowledged() {
        var notice = YouTubeTransmissionNotice()
        XCTAssertFalse(notice.canPrepare)
        notice.record(SignupConsent())
        XCTAssertFalse(notice.canPrepare)
        var unread = SignupConsent()
        unread.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        notice.record(unread)
        XCTAssertFalse(notice.canPrepare)
    }

    func testAcknowledgedNoticeAllowsPrepareAndResetsForNextPrepare() {
        var notice = YouTubeTransmissionNotice()
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        notice.record(consent)
        XCTAssertTrue(notice.canPrepare)
        notice.reset()
        XCTAssertFalse(notice.canPrepare)
    }
}

@MainActor
final class YouTubeTransmissionNoticePersistenceTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!
    private var consentStore: ConsentAcknowledgementStore!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.youtube-notice.\(UUID().uuidString)"
        guard let userDefaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("테스트용 UserDefaults suite를 만들지 못했습니다.")
            return
        }
        self.userDefaults = userDefaults
        consentStore = ConsentAcknowledgementStore(userDefaults: userDefaults)
    }

    override func tearDown() {
        if let suiteName {
            userDefaults.removePersistentDomain(forName: suiteName)
        }
        consentStore = nil
        userDefaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testUnreadConsentDoesNotPersistYouTubeNotice() {
        let youtube = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: userDefaults),
            consentStore: consentStore
        )
        XCTAssertFalse(youtube.acknowledgeYouTubeTransmission(SignupConsent()))
        XCTAssertFalse(youtube.hasAcknowledgedYouTubeTransmission)
        XCTAssertFalse(consentStore.hasAcknowledgedYouTubeTransmission)
    }

    func testAcknowledgedNoticeSurvivesNewIntegrationInstance() {
        let youtube = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: userDefaults),
            consentStore: consentStore
        )
        XCTAssertTrue(youtube.acknowledgeYouTubeTransmission(acceptedConsent()))
        XCTAssertTrue(youtube.hasAcknowledgedYouTubeTransmission)

        let reloaded = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: userDefaults),
            consentStore: consentStore
        )
        XCTAssertTrue(reloaded.hasAcknowledgedYouTubeTransmission)
    }

    func testResetClearsYouTubeNoticeButNotMediaConsent() {
        consentStore.recordMediaTransmission()
        let youtube = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: userDefaults),
            consentStore: consentStore
        )
        _ = youtube.acknowledgeYouTubeTransmission(acceptedConsent())
        youtube.reset()
        XCTAssertFalse(youtube.hasAcknowledgedYouTubeTransmission)
        XCTAssertFalse(consentStore.hasAcknowledgedYouTubeTransmission)
        XCTAssertTrue(consentStore.hasAcceptedMediaTransmission)
    }

    private func acceptedConsent() -> SignupConsent {
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        return consent
    }
}
