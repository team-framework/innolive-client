import XCTest

@testable import InnoLive

@MainActor
final class AuthSessionAccountDeletionTests: XCTestCase {
    func testSuccessfulDeletionRunsCleanupAndClearsAuthentication() async {
        let api = AccountDeletionAuthenticationAPI()
        api.deleteResults = [.success(())]
        let tokenStore = AccountDeletionTokenStore(
            tokens: AuthenticationTokenPair(accessToken: "access", refreshToken: "refresh")
        )
        let session = AuthSession(api: api, tokenStore: tokenStore)
        session.restore()
        var cleanupCallCount = 0

        let didDelete = await session.deleteAccount {
            cleanupCallCount += 1
        }

        XCTAssertTrue(didDelete)
        XCTAssertEqual(api.receivedAccessTokens, ["access"])
        XCTAssertEqual(cleanupCallCount, 1)
        XCTAssertNil(tokenStore.tokens)
        XCTAssertFalse(session.isAuthenticated)
        XCTAssertFalse(session.isDeletingAccount)
    }

    func testServerFailureKeepsAuthenticationForRetry() async {
        let api = AccountDeletionAuthenticationAPI()
        api.deleteResults = [
            .failure(
                AuthenticationError.api(
                    code: "withdrawal_failed",
                    fallback: "withdrawal failed"
                )
            )
        ]
        let storedTokens = AuthenticationTokenPair(accessToken: "access", refreshToken: "refresh")
        let tokenStore = AccountDeletionTokenStore(tokens: storedTokens)
        let session = AuthSession(api: api, tokenStore: tokenStore)
        session.restore()

        let didDelete = await session.deleteAccount()

        XCTAssertFalse(didDelete)
        XCTAssertEqual(api.deleteCallCount, 1)
        assertTokens(tokenStore.tokens, equalTo: storedTokens)
        XCTAssertTrue(session.isAuthenticated)
        XCTAssertEqual(session.errorMessage, "계정 삭제를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.")
    }

    func testUnauthorizedDeletionRefreshesOnceAndRetriesWithRotatedAccessToken() async {
        let api = AccountDeletionAuthenticationAPI()
        api.deleteResults = [
            .failure(AuthenticationError.api(code: "unauthorized", fallback: "unauthorized")),
            .success(())
        ]
        api.refreshResult = .success(
            AuthenticationTokenPair(accessToken: "refreshed-access", refreshToken: "refreshed-refresh")
        )
        let tokenStore = AccountDeletionTokenStore(
            tokens: AuthenticationTokenPair(accessToken: "expired-access", refreshToken: "refresh")
        )
        let session = AuthSession(api: api, tokenStore: tokenStore)
        session.restore()

        let didDelete = await session.deleteAccount()

        XCTAssertTrue(didDelete)
        XCTAssertEqual(api.refreshCallCount, 1)
        XCTAssertEqual(api.receivedAccessTokens, ["expired-access", "refreshed-access"])
        XCTAssertNil(tokenStore.tokens)
        XCTAssertFalse(session.isAuthenticated)
    }

    func testTransientRefreshFailureKeepsAuthenticationAndReturnsError() async {
        let api = AccountDeletionAuthenticationAPI()
        api.deleteResults = [
            .failure(AuthenticationError.api(code: "unauthorized", fallback: "unauthorized"))
        ]
        api.refreshResult = .failure(AccountDeletionAuthenticationError.transport)
        let storedTokens = AuthenticationTokenPair(accessToken: "access", refreshToken: "refresh")
        let tokenStore = AccountDeletionTokenStore(tokens: storedTokens)
        let session = AuthSession(api: api, tokenStore: tokenStore)
        session.restore()

        let didDelete = await session.deleteAccount()

        XCTAssertFalse(didDelete)
        XCTAssertEqual(api.refreshCallCount, 1)
        assertTokens(tokenStore.tokens, equalTo: storedTokens)
        XCTAssertTrue(session.isAuthenticated)
        XCTAssertEqual(session.errorMessage, "로그인 상태를 갱신하지 못했습니다. 잠시 후 다시 시도해 주세요.")
    }

    func testDuplicateAndLateDeletionResponseCannotClearNewSession() async throws {
        let api = AccountDeletionAuthenticationAPI()
        api.deleteGateIsEnabled = true
        let oldTokens = AuthenticationTokenPair(accessToken: "old-access", refreshToken: "old-refresh")
        let tokenStore = AccountDeletionTokenStore(tokens: oldTokens)
        let session = AuthSession(api: api, tokenStore: tokenStore)
        session.restore()
        var cleanupCallCount = 0

        let deletion = Task { @MainActor in
            await session.deleteAccount {
                cleanupCallCount += 1
            }
        }
        await api.waitForDeleteStart()

        let duplicateResult = await session.deleteAccount()
        XCTAssertFalse(duplicateResult)
        XCTAssertEqual(api.deleteCallCount, 1)

        session.signOut()
        let newTokens = AuthenticationTokenPair(accessToken: "new-access", refreshToken: "new-refresh")
        try tokenStore.save(newTokens)
        session.restore()
        api.completeDelete(with: .success(()))

        let didDelete = await deletion.value

        XCTAssertFalse(didDelete)
        XCTAssertEqual(cleanupCallCount, 0)
        XCTAssertTrue(session.isAuthenticated)
        assertTokens(tokenStore.tokens, equalTo: newTokens)
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
private final class AccountDeletionAuthenticationAPI: AuthenticationAPIClient {
    var deleteResults: [Result<Void, Error>] = []
    var deleteGateIsEnabled = false
    var refreshResult: Result<AuthenticationTokenPair, Error> = .failure(AccountDeletionAuthenticationError.unexpectedCall)
    private(set) var deleteCallCount = 0
    private(set) var refreshCallCount = 0
    private(set) var receivedAccessTokens: [String] = []

    private var deleteStartContinuation: CheckedContinuation<Void, Never>?
    private var deleteContinuation: CheckedContinuation<Void, Error>?

    func emailSignIn(email: String, password: String) async throws -> AuthenticationTokenPair {
        throw AccountDeletionAuthenticationError.unexpectedCall
    }

    func startEmailSignup(email: String, password: String) async throws -> String {
        throw AccountDeletionAuthenticationError.unexpectedCall
    }

    func verifyEmail(signupToken: String, code: String) async throws {
        throw AccountDeletionAuthenticationError.unexpectedCall
    }

    func googleSignIn(idToken: String) async throws -> AuthenticationTokenPair {
        throw AccountDeletionAuthenticationError.unexpectedCall
    }

    func appleSignIn(
        authorizationCode: String,
        nonce: String,
        givenName: String?,
        familyName: String?
    ) async throws -> AuthenticationTokenPair {
        throw AccountDeletionAuthenticationError.unexpectedCall
    }

    func deleteAccount(accessToken: String) async throws {
        deleteCallCount += 1
        receivedAccessTokens.append(accessToken)
        deleteStartContinuation?.resume()
        deleteStartContinuation = nil

        if deleteGateIsEnabled {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                deleteContinuation = continuation
            }
            return
        }

        guard !deleteResults.isEmpty else {
            throw AccountDeletionAuthenticationError.unexpectedCall
        }
        try deleteResults.removeFirst().get()
    }

    func refresh(refreshToken: String) async throws -> AuthenticationTokenPair {
        refreshCallCount += 1
        return try refreshResult.get()
    }

    func waitForDeleteStart() async {
        if deleteCallCount > 0 { return }
        await withCheckedContinuation { continuation in
            deleteStartContinuation = continuation
        }
    }

    func completeDelete(with result: Result<Void, Error>) {
        deleteGateIsEnabled = false
        deleteContinuation?.resume(with: result)
        deleteContinuation = nil
    }
}

@MainActor
private final class AccountDeletionTokenStore: AuthenticationTokenStoring {
    private(set) var tokens: AuthenticationTokenPair?

    init(tokens: AuthenticationTokenPair?) {
        self.tokens = tokens
    }

    func save(_ tokens: AuthenticationTokenPair) throws {
        self.tokens = tokens
    }

    func load() -> AuthenticationTokenPair? { tokens }

    func remove() { tokens = nil }
}

private enum AccountDeletionAuthenticationError: Error {
    case transport
    case unexpectedCall
}
