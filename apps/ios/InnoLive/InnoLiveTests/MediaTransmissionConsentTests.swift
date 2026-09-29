import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class MediaTransmissionConsentTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!
    private var consentStore: ConsentAcknowledgementStore!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.media-consent.\(UUID().uuidString)"
        guard let userDefaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("테스트용 UserDefaults suite를 만들지 못했습니다.")
            return
        }
        self.userDefaults = userDefaults
        consentStore = ConsentAcknowledgementStore(userDefaults: userDefaults)
    }

    override func tearDown() {
        if let suiteName {
            userDefaults.removePersistentDomain(forName: suiteName)
        }
        consentStore = nil
        userDefaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testMediaTransmissionStartsRejected() {
        let session = makeSession()
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
    }

    func testReadingWithoutAcceptingDoesNotGrantMediaTransmission() {
        let session = makeSession()
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        XCTAssertFalse(session.acceptMediaTransmission(consent))
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
        XCTAssertFalse(session.acceptMediaTransmission(SignupConsent()))
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
        XCTAssertFalse(consentStore.hasAcceptedMediaTransmission)
    }

    func testAcceptedScrollConsentGrantsMediaTransmission() {
        let session = makeSession()
        XCTAssertTrue(session.acceptMediaTransmission(acceptedConsent()))
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
        XCTAssertTrue(consentStore.hasAcceptedMediaTransmission)
    }

    func testNewSessionReadsPersistedMediaTransmission() {
        consentStore.recordMediaTransmission()
        let session = makeSession()
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
    }

    func testSignOutAndExpirationKeepMediaTransmission() {
        let tokenStore = MediaConsentTokenStore()
        let session = makeSession(tokenStore: tokenStore)
        _ = session.acceptMediaTransmission(acceptedConsent())
        session.signOut()
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
        XCTAssertTrue(consentStore.hasAcceptedMediaTransmission)
        _ = session.acceptMediaTransmission(acceptedConsent())
        session.expireSession()
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
        XCTAssertTrue(consentStore.hasAcceptedMediaTransmission)
    }

    func testDeleteAccountClearsMediaTransmission() async {
        let api = MediaConsentAPI()
        let tokenStore = MediaConsentTokenStore()
        tokenStore.tokens = AuthenticationTokenPair(accessToken: "test-access", refreshToken: "test-refresh")
        let session = makeSession(api: api, tokenStore: tokenStore)
        session.restore()
        _ = session.acceptMediaTransmission(acceptedConsent())
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
        let deleted = await session.deleteAccount()
        XCTAssertTrue(deleted)
        XCTAssertFalse(session.hasAcceptedMediaTransmission)
        XCTAssertFalse(consentStore.hasAcceptedMediaTransmission)
    }

    func testRestoreReusesPersistedMediaTransmission() {
        let tokenStore = MediaConsentTokenStore()
        let session = makeSession(tokenStore: tokenStore)
        _ = session.acceptMediaTransmission(acceptedConsent())
        session.signOut()
        tokenStore.tokens = AuthenticationTokenPair(accessToken: "test-access", refreshToken: "test-refresh")
        session.restore()
        XCTAssertTrue(session.isAuthenticated)
        XCTAssertTrue(session.hasAcceptedMediaTransmission)
    }

    private func makeSession(
        api: AuthenticationAPIClient = MediaConsentAPI(),
        tokenStore: AuthenticationTokenStoring = MediaConsentTokenStore()
    ) -> AuthSession {
        AuthSession(api: api, tokenStore: tokenStore, consentStore: consentStore)
    }

    private func acceptedConsent() -> SignupConsent {
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        return consent
    }
}

private final class MediaConsentAPI: AuthenticationAPIClient {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

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
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    var tokens: AuthenticationTokenPair?
    func load() -> AuthenticationTokenPair? { tokens }
    func save(_ tokens: AuthenticationTokenPair) throws { self.tokens = tokens }
    func remove() { tokens = nil }
}
