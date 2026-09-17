import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastSessionLifecycleTests: XCTestCase {
    private var store: MemoryBroadcastSessionStore!
    private var defaults: UserDefaults!
    private var suite: String!
    private let old = StoredBroadcastSession(sessionID: "old", ownerToken: "old-owner")
    private var token: String { Self.token("user-a") }

    override func setUp() {
        super.setUp()
        store = MemoryBroadcastSessionStore()
        suite = "broadcast-lifecycle-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        SessionLifecycleURLProtocol.responses = []
        SessionLifecycleURLProtocol.requests = []
        SessionLifecycleURLProtocol.beforeResponse = nil
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        SessionLifecycleURLProtocol.beforeResponse = nil
        super.tearDown()
    }

    func testColdLaunchDeletesPreviousBeforeCreatingAndSavesCredentials() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(204), .created("new")]
        let integration = makeIntegration()
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["DELETE", "POST"])
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "new")
        XCTAssertEqual(integration.session?.sessionID, "new")
    }

    func testAnotherLaunchDeletesTheLastCreatedSession() async throws {
        SessionLifecycleURLProtocol.responses = [.created("first"), .empty(204), .created("second")]
        let first = makeIntegration()
        let firstResult = await first.prepareSession(accessToken: token)
        XCTAssertTrue(firstResult)
        let second = makeIntegration()
        let secondResult = await second.prepareSession(accessToken: token)
        XCTAssertTrue(secondResult)
        XCTAssertEqual(methods, ["POST", "DELETE", "POST"])
        XCTAssertEqual(SessionLifecycleURLProtocol.requests[1].url?.path, "/sessions/first")
    }

    func testExistingInMemorySessionIsReusedWithoutDeletion() async {
        SessionLifecycleURLProtocol.responses = [.created("current")]
        let integration = makeIntegration()
        let first = await integration.prepareSession(accessToken: token)
        let second = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(first && second)
        XCTAssertEqual(methods, ["POST"])
    }

    func testDeletionFailureRetainsRecordAndRetryCleansBeforeCreation() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(503), .empty(204), .created("new")]
        let integration = makeIntegration()
        let failed = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(failed)
        XCTAssertEqual(methods, ["DELETE"])
        XCTAssertEqual(try store.load(scope: scope()), old)
        XCTAssertNotNil(integration.errorMessage)
        let retried = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertEqual(methods, ["DELETE", "DELETE", "POST"])
        XCTAssertNil(integration.errorMessage)
    }

    func testMissingSessionAllowsCreation() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.init(status: 404, body: "{\"error\":{\"code\":\"not_found\"}}"), .created("new")]
        let result = await makeIntegration().prepareSession(accessToken: token)
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["DELETE", "POST"])
    }

    func testStoreReadFailureBlocksCreation() async {
        store.failLoad = true
        let result = await makeIntegration().prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertTrue(methods.isEmpty)
    }

    func testStorageFailureDeletesCreatedSessionAndDoesNotConnect() async {
        store.failSave = true
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testStorageAndCleanupFailureRetainsInMemoryCleanupForRetry() async {
        store.failSave = true
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(503), .empty(204), .created("retry")]
        let integration = makeIntegration()
        let failed = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(failed)
        store.failSave = false
        let retried = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertEqual(methods, ["POST", "DELETE", "DELETE", "POST"])
    }

    func testOtherAccountRecordIsNotDeleted() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.created("user-b-session")]
        let result = await makeIntegration().prepareSession(accessToken: Self.token("user-b"))
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["POST"])
        XCTAssertEqual(try store.load(scope: scope()), old)
    }

    func testExplicitEndDeletesSessionAndClearsJournal() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.endBroadcast(accessToken: token)
        XCTAssertNil(integration.session)
        XCTAssertNil(try store.load(scope: scope()))
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testEndFailureAndResetPreserveJournal() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(503)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.endBroadcast(accessToken: token)
        integration.reset()
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "new")
        XCTAssertNil(integration.session)
    }

    func testVideoFailureDeletesStoredSession() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.recoverFromVideoUplinkFailure(accessToken: token)
        XCTAssertNil(try store.load(scope: scope()))
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testConcurrentPreparationCreatesOnlyOneSession() async {
        SessionLifecycleURLProtocol.responses = [.created("new")]
        let integration = makeIntegration()
        async let first = integration.prepareSession(accessToken: token)
        async let second = integration.prepareSession(accessToken: token)
        let results = await (first, second)
        XCTAssertTrue(results.0 && results.1)
        XCTAssertEqual(methods, ["POST"])
    }

    func testResetDuringCreateDoesNotRestoreSessionAndKeepsCleanupRecord() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new")]
        let integration = makeIntegration()
        SessionLifecycleURLProtocol.beforeResponse = { _ in integration.reset() }
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertNil(integration.session)
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "new")
    }

    func testAccountChangesDuringDeletionPreventCreation() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(204)]
        var currentToken = token
        let api = makeAPI()
        api.configureAuthentication(accessTokenProvider: { currentToken }, refreshSession: { .invalid }, onInvalidRefresh: {})
        let integration = makeIntegration(api: api)
        SessionLifecycleURLProtocol.beforeResponse = { _ in currentToken = Self.token("user-b") }
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["DELETE"])
    }

    func testAccountChangeDuringRefreshDoesNotRetryWithNewAccount() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(401)]
        var currentToken = token
        let api = makeAPI()
        api.configureAuthentication(
            accessTokenProvider: { currentToken },
            refreshSession: { currentToken = Self.token("user-b"); return .refreshed },
            onInvalidRefresh: {}
        )
        let result = await makeIntegration(api: api).prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertEqual(methods, ["DELETE"])
        XCTAssertEqual(try store.load(scope: scope()), old)
    }

    func testKeychainRemovalFailureBlocksNewSessionUntilRetried() async throws {
        try store.save(old, scope: scope())
        store.failRemove = true
        SessionLifecycleURLProtocol.responses = [
            .empty(204), .init(status: 404, body: "{\"error\":{\"code\":\"not_found\"}}"), .created("new")
        ]
        let integration = makeIntegration()
        let failed = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(failed)
        XCTAssertEqual(methods, ["DELETE"])
        store.failRemove = false
        let retried = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertEqual(methods, ["DELETE", "DELETE", "POST"])
    }

    func testEndWhileCreatingDeletesLateSessionWithoutRestoringIt() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        var ending: Task<Void, Never>?
        SessionLifecycleURLProtocol.beforeResponse = { request in
            if request.httpMethod == "POST" {
                ending = Task { await integration.endBroadcast(accessToken: self.token) }
            }
        }
        _ = await integration.prepareSession(accessToken: token)
        await ending?.value
        XCTAssertNil(integration.session)
        XCTAssertNil(try store.load(scope: scope()))
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    private var methods: [String] { SessionLifecycleURLProtocol.requests.compactMap(\.httpMethod) }

    private static func token(_ user: String) -> String {
        "header." + Data("{\"sub\":\"\(user)\"}".utf8).base64EncodedString() + ".signature"
    }

    private func scope() throws -> BroadcastSessionScope {
        try BroadcastSessionScope(server: XCTUnwrap(URL(string: "https://example.invalid/")), accessToken: token)
    }

    private func makeAPI() -> YouTubeAPI {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SessionLifecycleURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: {
            URL(string: "https://example.invalid\($0)")
        })
    }

    private func makeIntegration(api: YouTubeAPI? = nil) -> YouTubeIntegration {
        YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api ?? makeAPI(), sessionStore: store)
    }
}

@MainActor
private final class MemoryBroadcastSessionStore: BroadcastSessionStoring {
    var records: [String: StoredBroadcastSession] = [:]
    var failSave = false
    var failLoad = false
    var failRemove = false
    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession? {
        if failLoad { throw AuthenticationError.storage }
        return records[scope.storageKey]
    }
    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws {
        if failSave { throw AuthenticationError.storage }
        records[scope.storageKey] = session
    }
    func remove(scope: BroadcastSessionScope) throws {
        if failRemove { throw AuthenticationError.storage }
        records.removeValue(forKey: scope.storageKey)
    }
}

private final class SessionLifecycleURLProtocol: URLProtocol {
    struct Response {
        let status: Int
        let body: String
        static func empty(_ status: Int) -> Self { .init(status: status, body: "") }
        static func created(_ id: String) -> Self {
            .init(status: 201, body: "{\"session_id\":\"\(id)\",\"owner_token\":\"owner\",\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0}}")
        }
    }
    @MainActor static var responses: [Response] = []
    @MainActor static var requests: [URLRequest] = []
    @MainActor static var beforeResponse: ((URLRequest) -> Void)?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Task { @MainActor in
            Self.requests.append(request)
            guard !Self.responses.isEmpty else {
                client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
                return
            }
            let response = Self.responses.removeFirst()
            Self.beforeResponse?(request)
            let http = HTTPURLResponse(url: request.url!, statusCode: response.status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(response.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}
