import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastSessionPollingTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!

    override func setUp() {
        super.setUp()
        suite = "session-state-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        SessionStateURLProtocol.handler = nil
    }

    override func tearDown() {
        SessionStateURLProtocol.handler = nil
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    func testChzzkOnlySessionStartsPollingBeforeYouTubeActionAndStopsAtReset() async {
        let polls = expectation(description: "session polls")
        polls.expectedFulfillmentCount = 2
        var pollCount = 0
        SessionStateURLProtocol.handler = { request in
            if request.httpMethod == "GET" {
                pollCount += 1
                if pollCount <= 2 { polls.fulfill() }
            }
            return (request.httpMethod == "POST" ? 201 : 200, SessionStateFixture.json(providers: ["chzzk"]))
        }
        let integration = makeIntegration()
        let prepared = await integration.prepareSession(accessToken: SessionStateFixture.token("user"))
        XCTAssertTrue(prepared)
        await fulfillment(of: [polls], timeout: 2)
        XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
        XCTAssertEqual(integration.visibleBroadcastTargets.map(\.provider), ["chzzk"])
        integration.reset()
        let stoppedCount = pollCount
        try? await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(pollCount, stoppedCount)
        XCTAssertNil(integration.responseState.stream)
    }

    func testPollingContinuesWhenDefaultTargetStops() async {
        let polls = expectation(description: "poll after primary target stopped")
        polls.expectedFulfillmentCount = 3
        var pollCount = 0
        SessionStateURLProtocol.handler = { request in
            if request.httpMethod == "GET" {
                pollCount += 1
                if pollCount <= 3 { polls.fulfill() }
            }
            let dual = SessionStateFixture.json(providers: ["youtube", "chzzk"])
            let firstStream = "\"stream\":\(SessionStateFixture.stream())"
            let replacement = "\"stream\":\(SessionStateFixture.stream(phase: "idle", status: "stopped"))"
            let range = dual.range(of: firstStream)!
            let stoppedPrimary = dual.replacingCharacters(in: range, with: replacement)
                .replacingOccurrences(of: "{\"provider\":\"youtube\",\(firstStream)}", with: "{\"provider\":\"youtube\",\(replacement)}")
            return (request.httpMethod == "POST" ? 201 : 200, stoppedPrimary)
        }
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: SessionStateFixture.token("user"))
        await fulfillment(of: [polls], timeout: 2)
        XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
        XCTAssertEqual(integration.visibleBroadcastTargets.map(\.provider), ["chzzk"])
        integration.reset()
    }

    func testLatePollDoesNotRestoreResetState() async {
        let poll = expectation(description: "poll started")
        var integration: YouTubeIntegration!
        SessionStateURLProtocol.handler = { request in
            if request.httpMethod == "GET" {
                integration.reset()
                poll.fulfill()
            }
            return (request.httpMethod == "POST" ? 201 : 200, SessionStateFixture.json())
        }
        integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: SessionStateFixture.token("user"))
        await fulfillment(of: [poll], timeout: 2)
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertNil(integration.session)
        XCTAssertNil(integration.responseState.stream)
    }

    func testSameModeConfirmationKeepsSessionPolling() async {
        let polls = expectation(description: "poll after mode confirmation")
        var changedMode = false
        var fulfilled = false
        SessionStateURLProtocol.handler = { request in
            if request.httpMethod == "PATCH" { changedMode = true }
            if request.httpMethod == "GET", changedMode, !fulfilled { fulfilled = true; polls.fulfill() }
            return (request.httpMethod == "POST" ? 201 : 200, SessionStateFixture.json(providers: nil, phase: "idle", status: "idle"))
        }
        let integration = makeIntegration()
        let token = SessionStateFixture.token("user")
        _ = await integration.prepareSession(accessToken: token)
        let confirmed = await integration.changeAIProcessingMode(.server, accessToken: token)
        XCTAssertTrue(confirmed)
        await fulfillment(of: [polls], timeout: 2)
        XCTAssertEqual(integration.session?.sessionID, "state-session")
        integration.reset()
    }

    func testSessionActionAcceptsBareAndFullResponses() async throws {
        let api = makeAPI()
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(SessionStateFixture.json().utf8))
        for body in [SessionStateFixture.stream(), SessionStateFixture.json(remaining: "120")] {
            SessionStateURLProtocol.handler = { request in
                XCTAssertEqual(request.url?.path, "/sessions/state-session/stream/pause")
                XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "chzzk")
                return (200, body)
            }
            let result = try await api.streamAction(.pause, session: session, accessToken: "test-token", provider: "chzzk")
            XCTAssertEqual(result.stream.statusValue, .streaming)
        }
    }

    func testUnknownProviderDoesNotBlockKnownTargetStop() async {
        var stoppedProviders: [String] = []
        SessionStateURLProtocol.handler = { request in
            if request.url?.path.hasSuffix("/stream/stop") == true {
                stoppedProviders.append(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "provider" })?.value ?? "")
                return (200, SessionStateFixture.stream(phase: "idle", status: "stopped"))
            }
            return (request.httpMethod == "POST" ? 201 : 200, SessionStateFixture.json(providers: ["future_provider", "youtube"]))
        }
        let integration = makeIntegration()
        let token = SessionStateFixture.token("user")
        let prepared = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(prepared)
        await integration.stopYouTubeStream(accessToken: token)
        XCTAssertEqual(stoppedProviders, ["youtube"])
        integration.reset()
    }

    func testResolutionSwitchSnapshotKeepsPolling() async {
        let polls = expectation(description: "switching state is polled")
        polls.expectedFulfillmentCount = 2
        var count = 0
        SessionStateURLProtocol.handler = { request in
            if request.httpMethod == "GET" {
                count += 1
                if count <= 2 { polls.fulfill() }
            }
            let switching = SessionStateFixture.json(providers: ["youtube", "chzzk"])
                .replacingOccurrences(of: "\"notices\":", with: "\"resolution_switch\":{\"status\":\"switching\",\"resolution\":\"fhd\",\"targets\":[\"youtube\",\"chzzk\"],\"started_at\":\"2026-09-30T00:00:00Z\"},\"notices\":")
            return (request.httpMethod == "POST" ? 201 : 200, switching)
        }
        let integration = makeIntegration()
        let prepared = await integration.prepareSession(accessToken: SessionStateFixture.token("user"))
        XCTAssertTrue(prepared)
        await fulfillment(of: [polls], timeout: 2)
        XCTAssertEqual(integration.responseState.details.resolutionSwitch?.status, "switching")
        integration.reset()
    }

    func testConfiguredMissingTokenCannotUseFallback() async {
        let api = makeAPI()
        api.configureAuthentication(accessTokenProvider: { nil }, refreshSession: { .invalid }, onInvalidRefresh: {})
        SessionStateURLProtocol.handler = { _ in XCTFail("must not send fallback token"); return (200, "[]") }
        do {
            _ = try await api.streamingAccounts(accessToken: SessionStateFixture.token("old"))
            XCTFail("request must fail")
        } catch {}
    }

    func testAccountChangedDuringSuccessRejectsResponseButCreateKeepsCleanupCredentials() async throws {
        for isCreate in [false, true] {
            let api = makeAPI()
            var token = SessionStateFixture.token("old")
            api.configureAuthentication(accessTokenProvider: { token }, refreshSession: { .invalid }, onInvalidRefresh: {})
            SessionStateURLProtocol.handler = { _ in
                token = SessionStateFixture.token("new")
                return (isCreate ? 201 : 200, isCreate ? SessionStateFixture.json() : "[]")
            }
            if isCreate {
                let created = try await api.createSession(accessToken: token)
                XCTAssertEqual(created.ownerToken, "fixture-owner")
            } else {
                do { _ = try await api.streamingAccounts(accessToken: token); XCTFail("old scope response must fail") }
                catch {}
            }
        }
    }

    func testSameAccountTokenRefreshDuringResponseIsAccepted() async throws {
        let api = makeAPI()
        var token = SessionStateFixture.token("user")
        api.configureAuthentication(accessTokenProvider: { token }, refreshSession: { .refreshed }, onInvalidRefresh: {})
        SessionStateURLProtocol.handler = { _ in token = SessionStateFixture.token("user", version: 1); return (200, "[]") }
        let accounts = try await api.streamingAccounts(accessToken: token)
        XCTAssertTrue(accounts.isEmpty)
    }

    private func makeAPI() -> YouTubeAPI {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SessionStateURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: { path in
            var components = URLComponents(string: "https://example.invalid")!
            components.path = path
            components.query = nil
            return components.url
        })
    }

    private func makeIntegration() -> YouTubeIntegration {
        YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: makeAPI(), sessionStore: SessionStateStore(), pollingInterval: .milliseconds(20))
    }
}

private final class SessionStateStore: BroadcastSessionStoring {
    nonisolated deinit {}
    private var record: StoredBroadcastSession?
    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession? { record }
    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws { record = session }
    func remove(scope: BroadcastSessionScope) throws { record = nil }
}

private final class SessionStateURLProtocol: URLProtocol {
    nonisolated deinit {}
    @MainActor static var handler: ((URLRequest) -> (Int, String))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Task { @MainActor in
            let result = Self.handler?(request) ?? (500, "")
            let response = HTTPURLResponse(url: request.url!, statusCode: result.0, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(result.1.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}
