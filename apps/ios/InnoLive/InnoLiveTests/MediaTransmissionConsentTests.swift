import XCTest

@testable import InnoLive

@MainActor
final class MediaTransmissionConsentTests: XCTestCase {
    func testMediaTransmissionStartsRejected() {
        let session = AuthSession(api: MediaConsentAPI(), tokenStore: MediaConsentTokenStore())
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
    }

    func testReadingWithoutAcceptingDoesNotGrantMediaTransmission() {
        let session = AuthSession(api: MediaConsentAPI(), tokenStore: MediaConsentTokenStore())
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        XCTAssertFalse(session.acceptMediaTransmission(consent))
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
        XCTAssertFalse(session.acceptMediaTransmission(SignupConsent()))
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
    }

    func testAcceptedScrollConsentGrantsMediaTransmission() {
        let session = AuthSession(api: MediaConsentAPI(), tokenStore: MediaConsentTokenStore())
        XCTAssertTrue(session.acceptMediaTransmission(acceptedConsent()))
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
    }

    func testSignOutAndExpirationClearMediaTransmission() async {
        let store = MediaConsentTokenStore()
        let session = AuthSession(api: MediaConsentAPI(), tokenStore: store)
        _ = session.acceptMediaTransmission(acceptedConsent())
        session.signOut()
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
        _ = session.acceptMediaTransmission(acceptedConsent())
        session.expireSession()
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
    }

    func testDeleteAccountClearsMediaTransmission() async {
        let api = MediaConsentAPI()
        let store = MediaConsentTokenStore()
        store.tokens = AuthenticationTokenPair(accessToken: "test-access", refreshToken: "test-refresh")
        let session = AuthSession(api: api, tokenStore: store)
        session.restore()
        _ = session.acceptMediaTransmission(acceptedConsent())
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
        let deleted = await session.deleteAccount()
        XCTAssertTrue(deleted)
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
    }

    func testRestoreDoesNotReuseMediaTransmission() {
        let store = MediaConsentTokenStore()
        store.tokens = AuthenticationTokenPair(accessToken: "test-access", refreshToken: "test-refresh")
        let session = AuthSession(api: MediaConsentAPI(), tokenStore: store)
        _ = session.acceptMediaTransmission(acceptedConsent())
        session.signOut()
        store.tokens = AuthenticationTokenPair(accessToken: "test-access", refreshToken: "test-refresh")
        session.restore()
        XCTAssertTrue(session.isAuthenticated)
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
    }

    private func acceptedConsent() -> SignupConsent {
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        return consent
    }
}

private final class MediaConsentAPI: AuthenticationAPIClient {
    private var tokens: AuthenticationTokenPair {
        AuthenticationTokenPair(accessToken: "test-access", refreshToken: "test-refresh")
    }
    func startEmailSignup(email: String, password: String) async throws -> String { "test-signup" }
    func emailSignIn(email: String, password: String) async throws -> AuthenticationTokenPair { tokens }
    func googleSignIn(idToken: String) async throws -> AuthenticationTokenPair { tokens }
    func appleSignIn(authorizationCode: String, nonce: String, givenName: String?, familyName: String?) async throws -> AuthenticationTokenPair { tokens }
    func verifyEmail(signupToken: String, code: String) async throws {}
    func refresh(refreshToken: String) async throws -> AuthenticationTokenPair { tokens }
    func deleteAccount(accessToken: String) async throws {}
}

private final class MediaConsentTokenStore: AuthenticationTokenStoring {
    var tokens: AuthenticationTokenPair?
    func load() -> AuthenticationTokenPair? { tokens }
    func save(_ tokens: AuthenticationTokenPair) throws { self.tokens = tokens }
    func remove() { tokens = nil }
}
