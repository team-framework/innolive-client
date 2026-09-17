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
