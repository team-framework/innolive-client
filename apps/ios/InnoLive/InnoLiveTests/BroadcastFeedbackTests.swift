import SwiftUI
import XCTest

@testable import InnoLive

@MainActor
final class BroadcastFeedbackTests: XCTestCase {
    private var suiteName: String!
    private var preferences: UserDefaults!
    private var youtube: YouTubeIntegration!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.feedback.\(UUID().uuidString)"
        preferences = UserDefaults(suiteName: suiteName)
        youtube = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: preferences)
        )
    }

    override func tearDown() {
        youtube.reset()
        youtube = nil
        preferences.removePersistentDomain(forName: suiteName)
        preferences = nil
        suiteName = nil
        super.tearDown()
    }

    func testConnectingDoesNotProduceASeparateProgressBanner() {
        youtube.videoUplink.updateState(.connecting, "Connecting")

        XCTAssertNil(controls().feedback)
    }

    func testUplinkFailureAppearsOnlyAfterRecoveryAndStaysDismissed() async {
        youtube.videoUplink.fail("Connection lost")

        // A transient uplink error must not appear before recovery publishes it again.
        XCTAssertNil(controls().feedback)

        await youtube.recoverFromVideoUplinkFailure(accessToken: nil)

        XCTAssertEqual(controls().feedback?.message, "Connection lost")
        XCTAssertEqual(controls().feedback?.isError, true)

        youtube.dismissError()

        XCTAssertNil(controls().feedback)
        XCTAssertNil(youtube.videoUplink.errorMessage)
    }

    func testSessionFailureWaitsForConnectionAttemptToFinish() async {
        let prepared = await youtube.prepareSession(accessToken: nil)
        XCTAssertFalse(prepared)
        XCTAssertNotNil(youtube.errorMessage)

        XCTAssertNil(controls(isStartingServerConnection: true).feedback)
        XCTAssertEqual(controls().feedback?.message, youtube.errorMessage)

        youtube.dismissError()
        XCTAssertNil(controls().feedback)
    }

    func testRetryCanReportANewFailureAfterDismissal() async {
        youtube.videoUplink.fail("First failure")
        await youtube.recoverFromVideoUplinkFailure(accessToken: nil)
        youtube.dismissError()

        youtube.videoUplink.markReconnectFailed("Retry failure")
        XCTAssertNil(controls().feedback)

        await youtube.recoverFromVideoUplinkFailure(accessToken: nil)
        XCTAssertEqual(controls().feedback?.message, "Retry failure")
    }

    private func controls(isStartingServerConnection: Bool = false) -> BroadcastControllsView {
        BroadcastControllsView(
            isBroadcasting: .constant(false),
            previewTransition: .constant(.none),
            authentication: AuthSession(),
            youtube: youtube,
            isStartingServerConnection: isStartingServerConnection,
            onRetryConnection: {}
        )
    }
}
