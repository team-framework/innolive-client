import Foundation
import XCTest
@testable import InnoLive

final class ConsentAcknowledgementStoreTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!
    private var store: ConsentAcknowledgementStore!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.consent.\(UUID().uuidString)"
        guard let userDefaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("테스트용 UserDefaults suite를 만들지 못했습니다.")
            return
        }
        self.userDefaults = userDefaults
        store = ConsentAcknowledgementStore(userDefaults: userDefaults)
    }

    override func tearDown() {
        if let suiteName {
            userDefaults.removePersistentDomain(forName: suiteName)
        }
        store = nil
        userDefaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testFlagsStartFalse() {
        XCTAssertFalse(store.hasAcceptedMediaTransmission)
        XCTAssertFalse(store.hasAcknowledgedYouTubeTransmission)
    }

    func testMediaRoundTripUsesInjectedUserDefaults() {
        store.recordMediaTransmission()
        XCTAssertTrue(store.hasAcceptedMediaTransmission)
        XCTAssertTrue(
            userDefaults.bool(forKey: "com.framework.innolive.consent.media-transmission.v1")
        )

        let reloaded = ConsentAcknowledgementStore(userDefaults: userDefaults)
        XCTAssertTrue(reloaded.hasAcceptedMediaTransmission)
        XCTAssertFalse(reloaded.hasAcknowledgedYouTubeTransmission)
    }

    func testYouTubeRoundTripIsIndependentOfMedia() {
        store.recordYouTubeTransmission()
        XCTAssertTrue(store.hasAcknowledgedYouTubeTransmission)
        XCTAssertFalse(store.hasAcceptedMediaTransmission)

        store.recordMediaTransmission()
        store.clearYouTubeTransmission()
        XCTAssertTrue(store.hasAcceptedMediaTransmission)
        XCTAssertFalse(store.hasAcknowledgedYouTubeTransmission)
        XCTAssertNil(
            userDefaults.object(forKey: "com.framework.innolive.consent.youtube-transmission.v1")
        )
    }

    func testClearMediaDoesNotClearYouTube() {
        store.recordMediaTransmission()
        store.recordYouTubeTransmission()
        store.clearMediaTransmission()
        XCTAssertFalse(store.hasAcceptedMediaTransmission)
        XCTAssertTrue(store.hasAcknowledgedYouTubeTransmission)
    }
}
