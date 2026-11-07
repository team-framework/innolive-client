import XCTest

@testable import InnoLive

@MainActor
final class AuthSessionTeardownTests: XCTestCase {
    func testSignOutRemovesStoredCredentialsAndClearsError() {
        let tokenStore = TeardownTokenStore()
        let grantStore = TeardownOfflineGrantStore()
        let session = makeSignedInSession(tokenStore: tokenStore, grantStore: grantStore)
        session.showError("previous")

        session.signOut()

        XCTAssertNil(tokenStore.tokens)
        XCTAssertNil(grantStore.grant)
        XCTAssertFalse(session.isAuthenticated)
        XCTAssertNil(session.errorMessage)
    }

    func testExpireSessionRemovesStoredCredentialsAndShowsExpiry() {
        let tokenStore = TeardownTokenStore()
        let grantStore = TeardownOfflineGrantStore()
        let session = makeSignedInSession(tokenStore: tokenStore, grantStore: grantStore)

        session.expireSession()

        XCTAssertNil(tokenStore.tokens)
        XCTAssertNil(grantStore.grant)
        XCTAssertFalse(session.isAuthenticated)
        XCTAssertEqual(session.errorMessage, String(localized: "로그인이 만료되었습니다. 다시 로그인해 주세요."))
    }

    private func makeSignedInSession(
        tokenStore: TeardownTokenStore,
        grantStore: TeardownOfflineGrantStore
    ) -> AuthSession {
        let session = AuthSession(tokenStore: tokenStore, offlineGrantStore: grantStore)
        session.restore()
        XCTAssertTrue(session.isAuthenticated)
        XCTAssertNotNil(grantStore.grant)
        return session
    }
}

/// 로그인 세션이 끝나는 모든 경로가 진행 중 방송을 정리하는지 본다. 얼굴 등록처럼 방송과 무관한 요청의
/// refresh 무효도 세션 만료로 끝나므로, 만료 자체가 방송을 멈춰야 한다.
@MainActor
final class AuthSessionBroadcastTeardownTests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!
    private var token: String { SessionStateFixture.token("teardown-user") }
    private var integration: YouTubeIntegration?

    override func setUp() {
        super.setUp()
        suite = "auth-teardown-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        TeardownBroadcastURLProtocol.reset()
    }

    override func tearDown() {
        integration?.reset()
        TeardownBroadcastURLProtocol.reset()
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    func testExpireSessionTearsDownLiveBroadcast() async {
        let (session, integration) = makeSignedInBroadcast()
        await startLiveBroadcast(integration)

        session.expireSession()

        assertBroadcastTornDown(integration)
        XCTAssertFalse(session.isAuthenticated)
    }

    func testSignOutTearsDownLiveBroadcast() async {
        let (session, integration) = makeSignedInBroadcast()
        await startLiveBroadcast(integration)

        session.signOut()

        assertBroadcastTornDown(integration)
    }

    func testAccountDeletionWithInvalidRefreshTearsDownLiveBroadcast() async {
        let api = TeardownAuthenticationAPI(
            deleteError: AuthenticationError.api(code: "unauthorized", fallback: ""),
            refreshError: AuthenticationError.api(code: "invalid_refresh_token", fallback: "")
        )
        let (session, integration) = makeSignedInBroadcast(api: api)
        await startLiveBroadcast(integration)

        let deleted = await session.deleteAccount()

        XCTAssertFalse(deleted)
        assertBroadcastTornDown(integration)
        XCTAssertEqual(session.errorMessage, String(localized: "로그인이 만료되었습니다. 다시 로그인해 주세요."))
    }

    func testAccountDeletionTearsDownLiveBroadcastAndClearsAccountSettings() async {
        let (session, integration) = makeSignedInBroadcast()
        await startLiveBroadcast(integration)
        integration.broadcastSettings.title = "deleted account title"

        let deleted = await session.deleteAccount()

        XCTAssertTrue(deleted)
        assertBroadcastTornDown(integration)
        XCTAssertEqual(integration.broadcastSettings, .defaultValue)
    }

    func testExpireSessionClearsPendingLocalTakes() {
        let (session, integration) = makeSignedInBroadcast()
        let takeID = UUID()
        integration.localTakeLedger.record(SavedBroadcastRecording(
            id: takeID, sessionID: "\(CaptureSessionID.localKeyPrefix)\(UUID().uuidString)", createdAt: Date(),
            duration: 30, fileName: "take.mp4", bytes: 1, videoFrames: nil, audioFrames: nil, droppedVideoFrames: nil
        ))
        XCTAssertTrue(integration.localTakeLedger.pending.contains { $0.takeID == takeID })

        session.expireSession()

        XCTAssertTrue(integration.localTakeLedger.pending.isEmpty)
    }

    private func makeSignedInBroadcast(
        api: AuthenticationAPIClient? = nil
    ) -> (AuthSession, YouTubeIntegration) {
        let session = AuthSession(
            api: api ?? TeardownAuthenticationAPI(),
            tokenStore: TeardownTokenStore(tokens: AuthenticationTokenPair(accessToken: token, refreshToken: "refresh")),
            offlineGrantStore: TeardownOfflineGrantStore()
        )
        session.restore()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [TeardownBroadcastURLProtocol.self]
        let youtubeAPI = YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: { path in
            URL(string: "https://example.invalid\(path)")
        })
        let integration = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: defaults),
            api: youtubeAPI,
            sessionStore: TeardownSessionStore(),
            pollingInterval: .milliseconds(20)
        )
        integration.configureAuthentication(session)
        self.integration = integration
        return (session, integration)
    }

    private func startLiveBroadcast(_ integration: YouTubeIntegration) async {
        let polled = expectation(description: "session polled while live")
        polled.assertForOverFulfill = false
        TeardownBroadcastURLProtocol.handler = { request in
            if request.httpMethod == "GET" { polled.fulfill() }
            return .init(status: request.httpMethod == "POST" ? 201 : 200, json: SessionStateFixture.json())
        }
        let prepared = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(prepared)
        await fulfillment(of: [polled], timeout: 2)
        XCTAssertNotNil(integration.session)
        XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
    }

    private func assertBroadcastTornDown(
        _ integration: YouTubeIntegration,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertNil(integration.session, file: file, line: line)
        XCTAssertFalse(integration.hasStartedYouTubeBroadcast, file: file, line: line)
    }
}

private final class TeardownBroadcastURLProtocol: StubURLProtocol {
    override class var defaultHeaders: [String: String] { [:] }
}

private final class TeardownSessionStore: BroadcastSessionStoring {
    nonisolated deinit {}
    private var record: StoredBroadcastSession?
    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession? { record }
    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws { record = session }
    func remove(scope: BroadcastSessionScope) throws { record = nil }
}

@MainActor
private final class TeardownAuthenticationAPI: AuthenticationAPIClient {
    nonisolated deinit {}

    private let deleteError: Error?
    private let refreshError: Error

    init(deleteError: Error? = nil, refreshError: Error = AuthenticationError.response) {
        self.deleteError = deleteError
        self.refreshError = refreshError
    }

    func emailSignIn(email: String, password: String) async throws -> AuthenticationTokenPair { throw AuthenticationError.response }
    func startEmailSignup(email: String, password: String) async throws -> String { throw AuthenticationError.response }
    func verifyEmail(signupToken: String, code: String) async throws { throw AuthenticationError.response }
    func googleSignIn(idToken: String) async throws -> AuthenticationTokenPair { throw AuthenticationError.response }
    func appleSignIn(authorizationCode: String, nonce: String, givenName: String?, familyName: String?) async throws -> AuthenticationTokenPair {
        throw AuthenticationError.response
    }
    func refresh(refreshToken: String) async throws -> AuthenticationTokenPair { throw refreshError }
    func deleteAccount(accessToken: String) async throws { if let deleteError { throw deleteError } }
}

private final class TeardownTokenStore: AuthenticationTokenStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private(set) var tokens: AuthenticationTokenPair?

    init(tokens: AuthenticationTokenPair? = AuthenticationTokenPair(accessToken: "access", refreshToken: "refresh")) {
        self.tokens = tokens
    }

    func save(_ tokens: AuthenticationTokenPair) throws { self.tokens = tokens }
    func load() -> AuthenticationTokenPair? { tokens }
    func remove() { tokens = nil }
}

private final class TeardownOfflineGrantStore: OfflineGrantStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private(set) var grant: OfflineGrant? = OfflineGrant(
        accountID: "account", isAdministrator: true, serverDate: nil, receivedAt: Date()
    )

    func save(_ grant: OfflineGrant) { self.grant = grant }
    func load() -> OfflineGrant? { grant }
    func remove() { grant = nil }
}
