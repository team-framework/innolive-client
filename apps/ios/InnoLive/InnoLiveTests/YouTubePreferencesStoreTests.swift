import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubePreferencesStoreTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!
    private var store: YouTubePreferencesStore!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.youtube-preferences.\(UUID().uuidString)"
        guard let userDefaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("테스트용 UserDefaults suite를 만들지 못했습니다.")
            return
        }
        self.userDefaults = userDefaults
        store = YouTubePreferencesStore(userDefaults: userDefaults)
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

    func testConnectionRoundTripUsesInjectedUserDefaults() {
        let connection = YouTubeConnection(
            provider: "youtube",
            channel: YouTubeChannel(id: "channel-id", title: "Test Channel")
        )

        store.saveConnection(connection)

        XCTAssertEqual(store.loadConnection(), connection)
    }

    func testBroadcastSettingsAndAudienceRoundTrip() {
        let settings = YouTubeBroadcastSettings(
            title: "Test broadcast",
            description: "Description",
            privacy: .unlisted,
            audience: .madeForKids
        )

        store.saveBroadcastSettings(settings)

        XCTAssertEqual(store.loadBroadcastSettings(), settings.normalized)
        XCTAssertEqual(
            userDefaults.string(forKey: YouTubeBroadcastAudience.storageKey),
            YouTubeBroadcastAudience.madeForKids.rawValue
        )
    }

    func testEmptyTitleUsesExistingDefaultTitleAndAudienceFallback() {
        let settings = YouTubeBroadcastSettings(
            title: " \n\t",
            description: "Description",
            privacy: .private,
            audience: nil
        )
        store.saveBroadcastSettings(settings)

        let loaded = store.loadBroadcastSettings()

        XCTAssertEqual(loaded.title, YouTubeBroadcastSettings.defaultTitle())
        XCTAssertEqual(loaded.description, settings.description)
        XCTAssertEqual(loaded.privacy, settings.privacy)
#if DEBUG
        XCTAssertEqual(loaded.audience, .notMadeForKids)
#else
        XCTAssertNil(loaded.audience)
#endif
    }

    func testRemoveConnectionClearsPersistedConnection() {
        let connection = YouTubeConnection(
            provider: "youtube",
            channel: YouTubeChannel(id: "channel-id", title: "Test Channel")
        )
        store.saveConnection(connection)

        store.removeConnection()

        XCTAssertNil(store.loadConnection())
        XCTAssertNil(userDefaults.data(forKey: "com.framework.innolive.youtube.connection"))
    }
}
