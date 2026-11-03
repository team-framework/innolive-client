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

private final class TeardownTokenStore: AuthenticationTokenStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private(set) var tokens: AuthenticationTokenPair? = AuthenticationTokenPair(accessToken: "access", refreshToken: "refresh")

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
