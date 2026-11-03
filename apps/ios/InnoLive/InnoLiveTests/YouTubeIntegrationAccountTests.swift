import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeIntegrationAccountTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.youtube-integration-account.\(UUID().uuidString)"
        userDefaults = UserDefaults(suiteName: suiteName)
        YouTubeIntegrationAccountURLProtocol.reset()
    }

    override func tearDown() {
        YouTubeIntegrationAccountURLProtocol.reset()
        if let suiteName {
            userDefaults.removePersistentDomain(forName: suiteName)
        }
        userDefaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testRefreshFailurePreservesLastKnownConnection() async throws {
        let knownConnection = YouTubeConnection(
            provider: "youtube",
            channel: YouTubeChannel(id: "known-channel", title: "Known Channel")
        )
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        store.saveConnection(knownConnection)
        let integration = makeIntegration(store: store)
        YouTubeIntegrationAccountURLProtocol.responses = [
            .init(status: 503, json: "{\"error\":{\"message\":\"unavailable\"}}")
        ]

        await integration.refreshConnection(accessToken: "access-token")

        XCTAssertEqual(integration.connection, knownConnection)
        XCTAssertEqual(store.loadConnection(), knownConnection)
        XCTAssertNotNil(integration.errorMessage)
    }

    func testRefreshEmptyAccountListClearsLastKnownConnection() async throws {
        let knownConnection = YouTubeConnection(
            provider: "youtube",
            channel: YouTubeChannel(id: "known-channel", title: "Known Channel")
        )
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        store.saveConnection(knownConnection)
        let integration = makeIntegration(store: store)
        YouTubeIntegrationAccountURLProtocol.responses = [
            .init(status: 200, json: "[]")
        ]

        await integration.refreshConnection(accessToken: "access-token")

        XCTAssertNil(integration.connection)
        XCTAssertNil(store.loadConnection())
    }

    func testDisconnectFailurePreservesLastKnownConnection() async throws {
        let knownConnection = YouTubeConnection(
            provider: "youtube",
            channel: YouTubeChannel(id: "known-channel", title: "Known Channel")
        )
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        store.saveConnection(knownConnection)
        let integration = makeIntegration(store: store)
        YouTubeIntegrationAccountURLProtocol.responses = [
            .init(status: 503, json: "{\"error\":{\"message\":\"unavailable\"}}")
        ]

        await integration.disconnectYouTubeAccount(accessToken: "access-token")

        XCTAssertEqual(integration.connection, knownConnection)
        XCTAssertEqual(store.loadConnection(), knownConnection)
        XCTAssertNotNil(integration.errorMessage)
    }

    func testDisconnectSuccessRemovesPersistedConnection() async throws {
        let knownConnection = YouTubeConnection(
            provider: "youtube",
            channel: YouTubeChannel(id: "known-channel", title: "Known Channel")
        )
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        store.saveConnection(knownConnection)
        let integration = makeIntegration(store: store)
        YouTubeIntegrationAccountURLProtocol.responses = [
            .init(status: 204)
        ]

        await integration.disconnectYouTubeAccount(accessToken: "access-token")

        XCTAssertNil(integration.connection)
        XCTAssertNil(store.loadConnection())
    }

    func testPrepareDoesNotStartWhileDisconnectIsInProgress() async throws {
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        store.saveConnection(
            YouTubeConnection(
                provider: "youtube",
                channel: YouTubeChannel(id: "known-channel", title: "Known Channel")
            )
        )
        let integration = makeIntegration(store: store)
        YouTubeIntegrationAccountURLProtocol.responses = [
            .init(status: 204, body: Data(), delay: .milliseconds(200))
        ]
        let requestStarted = expectation(description: "disconnect request started")
        YouTubeIntegrationAccountURLProtocol.onNextRequest = { _ in
            requestStarted.fulfill()
        }

        let disconnectTask = Task { @MainActor in
            await integration.disconnectYouTubeAccount(accessToken: "access-token")
        }
        await fulfillment(of: [requestStarted], timeout: 1)
        XCTAssertTrue(integration.isYouTubeConnectionOperationInProgress)

        await integration.prepareYouTubeStream(accessToken: "access-token")
        XCTAssertNil(integration.errorMessage)
        await disconnectTask.value

        XCTAssertEqual(YouTubeIntegrationAccountURLProtocol.requests.count, 1)
        XCTAssertEqual(YouTubeIntegrationAccountURLProtocol.requests.first?.httpMethod, "DELETE")
    }

    func testResetIgnoresDelayedRefreshResponse() async throws {
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        let integration = makeIntegration(store: store)
        YouTubeIntegrationAccountURLProtocol.responses = [
            .init(
                status: 200,
                json: "[{\"provider\":\"youtube\",\"channel_id\":\"stale-channel\",\"channel_title\":\"Stale Channel\",\"reconnect_required\":false}]",
                delay: .milliseconds(200)
            )
        ]
        let requestStarted = expectation(description: "account refresh request started")
        YouTubeIntegrationAccountURLProtocol.onNextRequest = { _ in
            requestStarted.fulfill()
        }

        let refreshTask = Task { @MainActor in
            await integration.refreshConnection(accessToken: "access-token")
        }
        await fulfillment(of: [requestStarted], timeout: 1)
        integration.reset()
        await refreshTask.value

        XCTAssertNil(integration.connection)
        XCTAssertNil(store.loadConnection())
    }

    func testChannelSelectionSurvivesRefreshAndDisconnectOnlyRemovesChosenChannel() async throws {
        let body = Data("[{\"id\":\"a\",\"provider\":\"youtube\",\"channel_id\":\"uca\",\"channel_title\":\"A\",\"reconnect_required\":false},{\"id\":\"b\",\"provider\":\"youtube\",\"channel_id\":\"ucb\",\"channel_title\":\"B\",\"reconnect_required\":false}]".utf8)
        YouTubeIntegrationAccountURLProtocol.responses = [.init(status: 200, body: body), .init(status: 200, body: body), .init(status: 204)]
        let integration = makeIntegration(store: YouTubePreferencesStore(userDefaults: userDefaults))
        await integration.refreshConnection(accessToken: "access")
        XCTAssertEqual(integration.youtubeAccounts.count, 2)
        integration.selectYouTubeChannel("b", accessToken: "access")
        await integration.refreshConnection(accessToken: "access")
        XCTAssertEqual(integration.selectedYouTubeAccountID, "b")
        XCTAssertEqual(integration.connection?.channel.title, "B")
        await integration.disconnectYouTubeAccount(accessToken: "access", accountID: "a")
        XCTAssertEqual(integration.youtubeAccounts.map(\.accountID), ["b"])
        XCTAssertEqual(integration.selectedYouTubeAccountID, "b")
        let requestCount = YouTubeIntegrationAccountURLProtocol.requests.count
        await integration.disconnectYouTubeAccount(accessToken: "access", accountID: "a")
        XCTAssertEqual(YouTubeIntegrationAccountURLProtocol.requests.count, requestCount)
        XCTAssertEqual(integration.selectedYouTubeAccountID, "b")
        integration.reset()
        XCTAssertNil(integration.selectedYouTubeAccountID)
    }

    func testConnectSuccessThenListFailurePreservesSelectedChannel() async throws {
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        let integration = makeIntegration(store: store)
        YouTubeIntegrationAccountURLProtocol.responses = [
            .init(status: 200, json: "[{\"id\":\"a\",\"provider\":\"youtube\",\"channel_id\":\"uca\",\"channel_title\":\"A\",\"reconnect_required\":false}]"),
            .init(status: 200, json: "{\"connected\":true,\"provider\":\"youtube\",\"channel\":{\"id\":\"ucb\",\"title\":\"B\"}}"),
            .init(status: 503, json: "{}")
        ]
        await integration.refreshConnection(accessToken: "access")
        do {
            try await integration.connectAuthorizedYouTubeAccount(serverAuthCode: "code", accessToken: "access")
            XCTFail("Account list failure must be reported")
        } catch {}
        XCTAssertEqual(YouTubeIntegrationAccountURLProtocol.requests.count, 3)
        XCTAssertEqual(integration.selectedYouTubeAccountID, "a")
        XCTAssertEqual(integration.connection?.channel.id, "uca")
        XCTAssertEqual(store.loadConnection()?.channel.id, "uca")
    }

    func testFiveChannelsStillAllowReconnectButNotAddition() async throws {
        let accounts = (1...5).map { index in
            ["id": "account-\(index)", "provider": "youtube", "channel_id": "uc-\(index)",
             "channel_title": "Channel \(index)", "reconnect_required": true] as [String: Any]
        }
        YouTubeIntegrationAccountURLProtocol.responses = [.init(status: 200, body: try JSONSerialization.data(withJSONObject: accounts))]
        let integration = makeIntegration(store: YouTubePreferencesStore(userDefaults: userDefaults))
        await integration.refreshConnection(accessToken: "access")
        XCTAssertEqual(integration.youtubeAccounts.count, 5)
        XCTAssertFalse(integration.canConnectYouTubeChannel())
        XCTAssertTrue(integration.canConnectYouTubeChannel(reconnecting: true))
    }

    private func makeIntegration(store: YouTubePreferencesStore) -> YouTubeIntegration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [YouTubeIntegrationAccountURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let api = YouTubeAPI(
            urlSession: session,
            serverURLProvider: { path in URL(string: "https://example.invalid\(path)") }
        )
        return YouTubeIntegration(preferencesStore: store, api: api)
    }
}

private final class YouTubeIntegrationAccountURLProtocol: StubURLProtocol {}
