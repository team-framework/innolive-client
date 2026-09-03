import XCTest

@testable import InnoLive

@MainActor
final class AuthSessionRefreshTests: XCTestCase {
    func testConcurrentRefreshCallsShareOneRefreshAndSaveRotatedTokens() async {
        let storedTokens = AuthenticationTokenPair(accessToken: "old-access", refreshToken: "old-refresh")
        let refreshedTokens = AuthenticationTokenPair(accessToken: "new-access", refreshToken: "new-refresh")
        let api = TestAuthenticationAPI()
        api.refreshResult = .success(refreshedTokens)
        api.refreshGateIsEnabled = true
        let tokenStore = TestAuthenticationTokenStore(tokens: storedTokens)
        let session = AuthSession(api: api, tokenStore: tokenStore)

        let firstRefresh = Task { @MainActor in await session.refreshSession() }
        await api.waitForRefreshStart()

        let secondCallStarted = expectation(description: "second refresh call starts")
        let secondRefresh = Task { @MainActor in
            secondCallStarted.fulfill()
            return await session.refreshSession()
        }
        await fulfillment(of: [secondCallStarted], timeout: 1)

        api.completeRefresh(with: .success(refreshedTokens))

        let firstResult = await firstRefresh.value
        let secondResult = await secondRefresh.value

        XCTAssertTrue(isRefreshed(firstResult))
        XCTAssertTrue(isRefreshed(secondResult))
        XCTAssertEqual(api.refreshCallCount, 1)
        XCTAssertEqual(tokenStore.savedTokens.count, 1)
        assertTokens(tokenStore.tokens, equalTo: refreshedTokens)
    }

    func testInvalidRefreshTokenReturnsInvalidAndKeepsStoredTokens() async {
        let storedTokens = AuthenticationTokenPair(accessToken: "old-access", refreshToken: "old-refresh")
        let api = TestAuthenticationAPI()
        api.refreshResult = .failure(
            AuthenticationError.api(
                code: "invalid_refresh_token",
                fallback: "refresh token is invalid"
            )
        )
        let tokenStore = TestAuthenticationTokenStore(tokens: storedTokens)
        let session = AuthSession(api: api, tokenStore: tokenStore)

        let result = await session.refreshSession()

        XCTAssertTrue(isInvalid(result))
        XCTAssertEqual(api.refreshCallCount, 1)
        assertTokens(tokenStore.tokens, equalTo: storedTokens)
        XCTAssertTrue(tokenStore.savedTokens.isEmpty)
    }

    func testTransportFailureReturnsUnavailableAndKeepsStoredTokens() async {
        let storedTokens = AuthenticationTokenPair(accessToken: "old-access", refreshToken: "old-refresh")
        let api = TestAuthenticationAPI()
        api.refreshResult = .failure(TestAuthenticationError.transport)
        let tokenStore = TestAuthenticationTokenStore(tokens: storedTokens)
        let session = AuthSession(api: api, tokenStore: tokenStore)

        let result = await session.refreshSession()

        XCTAssertTrue(isUnavailable(result))
        XCTAssertEqual(api.refreshCallCount, 1)
        assertTokens(tokenStore.tokens, equalTo: storedTokens)
        XCTAssertTrue(tokenStore.savedTokens.isEmpty)
    }

    func testTokenStoreFailureReturnsUnavailableAndKeepsStoredTokens() async {
        let storedTokens = AuthenticationTokenPair(accessToken: "old-access", refreshToken: "old-refresh")
        let refreshedTokens = AuthenticationTokenPair(accessToken: "new-access", refreshToken: "new-refresh")
        let api = TestAuthenticationAPI()
        api.refreshResult = .success(refreshedTokens)
        let tokenStore = TestAuthenticationTokenStore(tokens: storedTokens)
        tokenStore.saveError = TestAuthenticationError.storage
        let session = AuthSession(api: api, tokenStore: tokenStore)

        let result = await session.refreshSession()

        XCTAssertTrue(isUnavailable(result))
        XCTAssertEqual(api.refreshCallCount, 1)
        XCTAssertEqual(tokenStore.saveCallCount, 1)
        assertTokens(tokenStore.tokens, equalTo: storedTokens)
    }

    func testSignOutDuringGatedRefreshIgnoresLateTokens() async {
        let storedTokens = AuthenticationTokenPair(accessToken: "old-access", refreshToken: "old-refresh")
        let lateTokens = AuthenticationTokenPair(accessToken: "late-access", refreshToken: "late-refresh")
        let api = TestAuthenticationAPI()
        api.refreshGateIsEnabled = true
        let tokenStore = TestAuthenticationTokenStore(tokens: storedTokens)
        let session = AuthSession(api: api, tokenStore: tokenStore)

        let refresh = Task { @MainActor in await session.refreshSession() }
        await api.waitForRefreshStart()

        session.signOut()
        api.completeRefresh(with: .success(lateTokens))

        let result = await refresh.value
        XCTAssertTrue(isUnavailable(result))
        XCTAssertNil(tokenStore.tokens)
        XCTAssertTrue(tokenStore.savedTokens.isEmpty)
        XCTAssertFalse(session.isAuthenticated)
    }

    private func isRefreshed(_ result: AuthenticationRefreshResult) -> Bool {
        if case .refreshed = result { return true }
        return false
    }

    private func isInvalid(_ result: AuthenticationRefreshResult) -> Bool {
        if case .invalid = result { return true }
        return false
    }

    private func isUnavailable(_ result: AuthenticationRefreshResult) -> Bool {
        if case .unavailable = result { return true }
        return false
    }

    private func assertTokens(
        _ actual: AuthenticationTokenPair?,
        equalTo expected: AuthenticationTokenPair,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(actual?.accessToken, expected.accessToken, file: file, line: line)
        XCTAssertEqual(actual?.refreshToken, expected.refreshToken, file: file, line: line)
    }
}

@MainActor
private final class TestAuthenticationAPI: AuthenticationAPIClient {
    var refreshResult: Result<AuthenticationTokenPair, Error> = .failure(TestAuthenticationError.unexpectedCall)
    var refreshGateIsEnabled = false
    private(set) var refreshCallCount = 0

    private var refreshStartContinuation: CheckedContinuation<Void, Never>?
    private var refreshContinuation: CheckedContinuation<AuthenticationTokenPair, Error>?

    func emailSignIn(email: String, password: String) async throws -> AuthenticationTokenPair {
        throw TestAuthenticationError.unexpectedCall
    }

    func startEmailSignup(email: String, password: String) async throws -> String {
        throw TestAuthenticationError.unexpectedCall
    }

    func verifyEmail(signupToken: String, code: String) async throws {
        throw TestAuthenticationError.unexpectedCall
    }

    func googleSignIn(idToken: String) async throws -> AuthenticationTokenPair {
        throw TestAuthenticationError.unexpectedCall
    }

    func appleSignIn(authorizationCode: String, nonce: String, givenName: String?, familyName: String?) async throws -> AuthenticationTokenPair {
        throw TestAuthenticationError.unexpectedCall
    }

    func refresh(refreshToken: String) async throws -> AuthenticationTokenPair {
        refreshCallCount += 1
        refreshStartContinuation?.resume()
        refreshStartContinuation = nil

        if refreshGateIsEnabled {
            return try await withCheckedThrowingContinuation { continuation in
                refreshContinuation = continuation
            }
        }
        return try refreshResult.get()
    }

    func waitForRefreshStart() async {
        if refreshCallCount > 0 { return }
        await withCheckedContinuation { continuation in
            refreshStartContinuation = continuation
        }
    }

    func completeRefresh(with result: Result<AuthenticationTokenPair, Error>) {
        refreshGateIsEnabled = false
        refreshContinuation?.resume(with: result)
        refreshContinuation = nil
    }
}

@MainActor
private final class TestAuthenticationTokenStore: AuthenticationTokenStoring {
    private(set) var tokens: AuthenticationTokenPair?
    private(set) var savedTokens: [AuthenticationTokenPair] = []
    private(set) var saveCallCount = 0
    var saveError: Error?

    init(tokens: AuthenticationTokenPair?) {
        self.tokens = tokens
    }

    func save(_ tokens: AuthenticationTokenPair) throws {
        saveCallCount += 1
        if let saveError { throw saveError }
        self.tokens = tokens
        savedTokens.append(tokens)
    }

    func load() -> AuthenticationTokenPair? { tokens }

    func remove() { tokens = nil }
}

private enum TestAuthenticationError: Error {
    case transport
    case storage
    case unexpectedCall
}
