import XCTest
@testable import InnoLive

/// Admin local mode with the radio off: every request fails as offline and is recorded, and the critical path must make none.
@MainActor
final class OfflineLocalModeTests: XCTestCase {
    private let account = "550e8400-e29b-41d4-a716-446655440000"
    private var suite: String!
    private var defaults: UserDefaults!
    private var directory: URL!

    override func setUp() {
        super.setUp()
        suite = "OfflineLocalModeTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        OfflineURLProtocol.reset()
        URLProtocol.registerClass(OfflineURLProtocol.self)
    }

    override func tearDown() {
        URLProtocol.unregisterClass(OfflineURLProtocol.self)
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var token: String {
        let payload = Data(#"{"sub":"\#(account)"}"#.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "eyJhbGciOiJIUzI1NiJ9.\(payload).sig"
    }

    private func makeIntegration(grant: OfflineGrant?) -> YouTubeIntegration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OfflineURLProtocol.self]
        let api = YouTubeAPI(urlSession: URLSession(configuration: configuration),
                             serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        let token = token
        api.configureAuthentication(accessTokenProvider: { token }, refreshSession: { .unavailable }, onInvalidRefresh: {})
        return YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api,
            localModelsAvailable: { true },
            offlineGrantStore: MemoryOfflineGrantStore(grant),
            modelReadiness: ModelReadinessStore(available: true, loaders: [.init(name: "bundled") {}], defaults: defaults),
            planStore: PlanStore(cacheURL: directory.appendingPathComponent("PlanSnapshot.json"))
        )
    }

    func testExpiredGrantLaunchAndTakeMakeNoNetworkRequests() async throws {
        let monthAgo = Date.now.addingTimeInterval(-30 * 24 * 60 * 60)
        let youtube = makeIntegration(grant: OfflineGrant(accountID: account, isAdministrator: true,
                                                          serverDate: monthAgo, receivedAt: monthAgo))

        youtube.planStore.restore(accountID: account)
        XCTAssertTrue(youtube.storedAdministrator(accessToken: token))
        XCTAssertTrue(youtube.isOfflineGrantStale)
        await youtube.modelReadiness.warm()
        XCTAssertTrue(youtube.modelReadiness.isReady)
        XCTAssertNoThrow(try youtube.startLocalTake(), "an expired grant does not block a local take")
        for _ in 0..<5 { await Task.yield() }

        XCTAssertEqual(OfflineURLProtocol.recorded, [], "the offline local-mode path made a network request")
    }

    func testOtherAccountGrantStillBlocksLocalTake() async {
        let youtube = makeIntegration(grant: OfflineGrant(accountID: "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                                                          isAdministrator: true, serverDate: .now, receivedAt: .now))
        XCTAssertFalse(youtube.storedAdministrator(accessToken: token))
        await youtube.modelReadiness.warm()
        XCTAssertThrowsError(try youtube.startLocalTake())
        XCTAssertEqual(OfflineURLProtocol.recorded, [])
    }
}

private final class MemoryOfflineGrantStore: OfflineGrantStoring {
    nonisolated deinit {}
    private var grant: OfflineGrant?
    init(_ grant: OfflineGrant?) { self.grant = grant }
    func save(_ grant: OfflineGrant) { self.grant = grant }
    func load() -> OfflineGrant? { grant }
    func remove() { grant = nil }
}

private final class OfflineURLProtocol: URLProtocol {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private nonisolated static let lock = NSLock()
    nonisolated(unsafe) private static var urls: [String] = []

    nonisolated static var recorded: [String] { lock.withLock { urls } }
    nonisolated static func reset() { lock.withLock { urls = [] } }

    override nonisolated class func canInit(with request: URLRequest) -> Bool { true }
    override nonisolated class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override nonisolated func startLoading() {
        Self.lock.withLock { Self.urls.append(request.url?.absoluteString ?? "?") }
        client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
    }
    override nonisolated func stopLoading() {}
}
