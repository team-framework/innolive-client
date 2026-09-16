import XCTest

@testable import InnoLive

@MainActor
final class SignupConsentTests: XCTestCase {
    func testLastVisibleLineCountsAsReadEvenWithABottomSafeAreaInset() {
        let position = SignupConsent.scrollPosition(
            contentHeight: 1400,
            visibleHeight: 700,
            offset: 700,
            topInset: 0,
            bottomInset: 180
        )
        var consent = SignupConsent()
        consent.observeScroll(
            contentHeight: position.contentHeight,
            visibleHeight: position.visibleHeight,
            offset: position.offset
        )
        XCTAssertEqual(position.contentHeight, 1400)
        XCTAssertTrue(consent.hasReadToEnd)
        XCTAssertFalse(consent.isAccepted)
    }

    func testNegativeTopInsetDoesNotBlockReachingEnd() {
        let position = SignupConsent.scrollPosition(
            contentHeight: 1400,
            visibleHeight: 700,
            offset: 644,
            topInset: 56,
            bottomInset: 180
        )
        var consent = SignupConsent()
        consent.observeScroll(
            contentHeight: position.contentHeight,
            visibleHeight: position.visibleHeight,
            offset: position.offset
        )
        XCTAssertTrue(consent.hasReadToEnd)
    }

    func testCannotAcceptBeforeReachingEndOrWithUnmeasuredContent() {
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 0, visibleHeight: 0, offset: 0)
        consent.accept()
        XCTAssertFalse(consent.isAccepted)
        consent.observeScroll(contentHeight: 900, visibleHeight: 400, offset: 100)
        consent.accept()
        XCTAssertFalse(consent.hasReadToEnd)
        XCTAssertFalse(consent.isAccepted)
    }

    func testReachingEndEnablesAcceptAndPreservesReadingWhenScrollingBack() {
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 900, visibleHeight: 400, offset: 500)
        XCTAssertTrue(consent.hasReadToEnd)
        XCTAssertFalse(consent.isAccepted)
        consent.accept()
        consent.observeScroll(contentHeight: 900, visibleHeight: 400, offset: 0)
        XCTAssertTrue(consent.isAccepted)
    }

    func testShortContentAllowsAcceptAndNewPresentationStartsFresh() {
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        XCTAssertTrue(consent.isAccepted)
        consent = SignupConsent()
        XCTAssertFalse(consent.hasReadToEnd)
        XCTAssertFalse(consent.isAccepted)
    }

    func testVoiceOverReachingFinalContentStillRequiresExplicitAccept() {
        var consent = SignupConsent()
        consent.reachedEnd()
        XCTAssertTrue(consent.hasReadToEnd)
        XCTAssertFalse(consent.isAccepted)
        consent.accept()
        XCTAssertTrue(consent.isAccepted)
    }

    func testEmailSignupCheckboxStaysUncheckedUntilSheetIsAccepted() {
        var form = EmailSignupConsent()
        form.setChecked(true)
        XCTAssertFalse(form.isChecked)
        XCTAssertFalse(form.canSubmit)
        form.recordSheetAcceptance(SignupConsent())
        XCTAssertFalse(form.isChecked)
        form.recordSheetAcceptance(acceptedConsent())
        XCTAssertTrue(form.isChecked)
        XCTAssertTrue(form.canSubmit)
    }

    func testUncheckingEmailSignupConsentBlocksSubmitUntilCheckedAgain() {
        var form = EmailSignupConsent()
        form.recordSheetAcceptance(acceptedConsent())
        form.setChecked(false)
        XCTAssertFalse(form.canSubmit)
        form.setChecked(true)
        XCTAssertTrue(form.canSubmit)
    }

    func testEmailSignupConsentResetsWhenLeavingSignup() {
        var form = EmailSignupConsent()
        form.recordSheetAcceptance(acceptedConsent())
        form.reset()
        XCTAssertFalse(form.isChecked)
        XCTAssertFalse(form.policy.isAccepted)
        XCTAssertFalse(form.canSubmit)
    }

    func testSignupAndGoogleRequestsAreBlockedWithoutConsent() async {
        let api = ConsentTestAPI()
        let session = AuthSession(api: api, tokenStore: ConsentTokenStore())
        let started = await session.startSignup(email: "user@example.com", password: "password123", consent: SignupConsent())
        await session.signInWithGoogle(idToken: "test-id-token", consent: SignupConsent())
        XCTAssertFalse(started)
        XCTAssertEqual(api.signupCalls, 0)
        XCTAssertEqual(api.googleCalls, 0)
        XCTAssertFalse(session.isAuthenticated)
    }

    func testReadingWithoutAcceptingStillBlocksSignup() async {
        let api = ConsentTestAPI()
        let session = AuthSession(api: api, tokenStore: ConsentTokenStore())
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        let started = await session.startSignup(email: "user@example.com", password: "password123", consent: consent)
        XCTAssertFalse(started)
        XCTAssertEqual(api.signupCalls, 0)
    }

    func testAcceptedSignupCanResendAndCancellationClearsPendingSignup() async {
        let api = ConsentTestAPI()
        let session = AuthSession(api: api, tokenStore: ConsentTokenStore())
        let started = await session.startSignup(email: "user@example.com", password: "password123", consent: acceptedConsent())
        let resent = await session.resendSignup()
        XCTAssertTrue(started)
        XCTAssertTrue(resent)
        XCTAssertEqual(api.signupCalls, 2)
        session.cancelSignup()
        let resentAfterCancellation = await session.resendSignup()
        XCTAssertFalse(resentAfterCancellation)
        XCTAssertEqual(api.signupCalls, 2)
    }

    func testFailedSignupCanRetryWithSameConsent() async {
        let api = ConsentTestAPI()
        let session = AuthSession(api: api, tokenStore: ConsentTokenStore())
        let consent = acceptedConsent()
        api.failSignup = true
        let failed = await session.startSignup(email: "user@example.com", password: "password123", consent: consent)
        api.failSignup = false
        let retried = await session.startSignup(email: "user@example.com", password: "password123", consent: consent)
        XCTAssertFalse(failed)
        XCTAssertTrue(retried)
        XCTAssertEqual(api.signupCalls, 2)
    }

    func testAcceptedGoogleSignInAndExistingEmailSignInSucceed() async {
        let api = ConsentTestAPI()
        let session = AuthSession(api: api, tokenStore: ConsentTokenStore())
        await session.signInWithGoogle(idToken: "test-id-token", consent: acceptedConsent())
        XCTAssertEqual(api.googleCalls, 1)
        XCTAssertTrue(session.isAuthenticated)
        session.signOut()
        await session.signIn(email: "user@example.com", password: "password123")
        XCTAssertEqual(api.emailSignInCalls, 1)
        XCTAssertTrue(session.isAuthenticated)
    }

    private func acceptedConsent() -> SignupConsent {
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        return consent
    }
}

private final class ConsentTestAPI: AuthenticationAPIClient {
    var signupCalls = 0
    var googleCalls = 0
    var emailSignInCalls = 0
    var failSignup = false
    private var tokens: AuthenticationTokenPair {
        AuthenticationTokenPair(accessToken: "test-access", refreshToken: "test-refresh")
    }
    func startEmailSignup(email: String, password: String) async throws -> String {
        signupCalls += 1
        if failSignup { throw AuthenticationError.response }
        return "test-signup"
    }
    func emailSignIn(email: String, password: String) async throws -> AuthenticationTokenPair {
        emailSignInCalls += 1
        return tokens
    }
    func googleSignIn(idToken: String) async throws -> AuthenticationTokenPair {
        googleCalls += 1
        return tokens
    }
    func appleSignIn(authorizationCode: String, nonce: String, givenName: String?, familyName: String?) async throws -> AuthenticationTokenPair { tokens }
    func verifyEmail(signupToken: String, code: String) async throws {}
    func refresh(refreshToken: String) async throws -> AuthenticationTokenPair { tokens }
    func deleteAccount(accessToken: String) async throws {}
}

private final class ConsentTokenStore: AuthenticationTokenStoring {
    var tokens: AuthenticationTokenPair?
    func load() -> AuthenticationTokenPair? { tokens }
    func save(_ tokens: AuthenticationTokenPair) throws { self.tokens = tokens }
    func remove() { tokens = nil }
}
