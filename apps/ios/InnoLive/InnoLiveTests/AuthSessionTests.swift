import XCTest

@testable import InnoLive

@MainActor
final class AuthSessionTests: XCTestCase {
    func testNormalizedEmailTrimsWhitespaceAndLowercasesAddress() {
        XCTAssertEqual(
            AuthSession.normalizedEmail("  USER@Example.COM\n"),
            "user@example.com"
        )
    }

    func testEmailValidationAcceptsAddressAndRejectsMalformedValues() {
        XCTAssertTrue(AuthSession.isValidEmail("viewer@example.com"))
        XCTAssertFalse(AuthSession.isValidEmail("viewer@example"))
        XCTAssertFalse(AuthSession.isValidEmail("viewer @example.com"))
        XCTAssertFalse(AuthSession.isValidEmail("viewer@example.com "))
    }

    func testSignupPasswordUsesEightToSeventyTwoUTF8BytesAsInclusiveBounds() {
        XCTAssertFalse(AuthSession.isValidSignupPassword(String(repeating: "a", count: 7)))
        XCTAssertTrue(AuthSession.isValidSignupPassword(String(repeating: "a", count: 8)))
        XCTAssertTrue(AuthSession.isValidSignupPassword(String(repeating: "a", count: 72)))
        XCTAssertFalse(AuthSession.isValidSignupPassword(String(repeating: "a", count: 73)))

        XCTAssertTrue(AuthSession.isValidSignupPassword(String(repeating: "가", count: 24)))
        XCTAssertFalse(AuthSession.isValidSignupPassword(String(repeating: "가", count: 25)))
    }
}
