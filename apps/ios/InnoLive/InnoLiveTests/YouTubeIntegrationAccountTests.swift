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
        YouTubeIntegrationAccountURLProtocol.responses = []
        YouTubeIntegrationAccountURLProtocol.requests = []
        YouTubeIntegrationAccountURLProtocol.onRequest = nil
    }

    override func tearDown() {
        YouTubeIntegrationAccountURLProtocol.responses = []
        YouTubeIntegrationAccountURLProtocol.requests = []
        YouTubeIntegrationAccountURLProtocol.onRequest = nil
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
            .init(statusCode: 503, data: Data("{\"error\":{\"message\":\"unavailable\"}}".utf8))
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
            .init(statusCode: 200, data: Data("[]".utf8))
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
            .init(statusCode: 503, data: Data("{\"error\":{\"message\":\"unavailable\"}}".utf8))
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
            .init(statusCode: 204, data: Data())
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
            .init(statusCode: 204, data: Data(), delay: .milliseconds(200))
        ]
        let requestStarted = expectation(description: "disconnect request started")
        YouTubeIntegrationAccountURLProtocol.onRequest = {
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
                statusCode: 200,
                data: Data(
                    "[{\"provider\":\"youtube\",\"channel_id\":\"stale-channel\",\"channel_title\":\"Stale Channel\",\"reconnect_required\":false}]".utf8
                ),
                delay: .milliseconds(200)
            )
        ]
        let requestStarted = expectation(description: "account refresh request started")
        YouTubeIntegrationAccountURLProtocol.onRequest = {
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

private final class YouTubeIntegrationAccountURLProtocol: URLProtocol {
    struct Response {
        let statusCode: Int
        let data: Data
        var delay: Duration = .zero
    }

    nonisolated(unsafe) static var responses: [Response] = []
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var onRequest: (() -> Void)?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        Self.onRequest?()
        let response = Self.responses.isEmpty
            ? Response(statusCode: 500, data: Data())
            : Self.responses.removeFirst()
        let deliver: @Sendable () -> Void = { [weak self] in
            guard let self,
                  let url = self.request.url,
                  let httpResponse = HTTPURLResponse(
                      url: url,
                      statusCode: response.statusCode,
                      httpVersion: nil,
                      headerFields: ["Content-Type": "application/json"]
                  ) else { return }
            self.client?.urlProtocol(self, didReceive: httpResponse, cacheStoragePolicy: .notAllowed)
            if !response.data.isEmpty {
                self.client?.urlProtocol(self, didLoad: response.data)
            }
            self.client?.urlProtocolDidFinishLoading(self)
        }

        if response.delay == .zero {
            deliver()
        } else {
            DispatchQueue.global().asyncAfter(deadline: .now() + response.delay.timeInterval, execute: deliver)
        }
    }

    override func stopLoading() {}
}

private extension Duration {
    var timeInterval: TimeInterval {
        let components = self.components
        return TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1_000_000_000_000_000_000
    }
}
