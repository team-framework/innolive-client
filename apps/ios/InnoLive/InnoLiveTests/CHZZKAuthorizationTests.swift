import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class CHZZKAuthorizationTests: XCTestCase {
    private let redirect = URL(string: "https://callback.example.invalid/auth/chzzk/callback")!

    func testRandomStateIsFreshAndHas256Bits() throws {
        let states = try (0..<100).map { _ in try CHZZKAuthorizationAttempt.random().state }
        XCTAssertEqual(Set(states).count, 100)
        XCTAssertTrue(states.allSatisfy { $0.count == 64 && $0.allSatisfy(\.isHexDigit) })
    }

    func testValidCallbackPreservesCodeAndState() throws {
        let result = try CHZZKAuthorizationAttempt(state: "expected").complete(
            callback: callback("code=fake%2Bcode%2Fvalue&state=expected"), redirectURI: redirect
        )
        XCTAssertEqual(result.code, "fake+code/value")
        XCTAssertEqual(result.state, "expected")
    }

    func testMissingWrongEmptyAndDuplicateStateAreRejected() {
        for query in ["code=fake", "code=fake&state=wrong", "code=fake&state=",
                      "code=fake&state=expected&state=expected"] {
            assertError(.stateMismatch, query: query)
        }
    }

    func testMissingEmptyAndDuplicateCodeAreRejected() {
        for query in ["state=expected", "state=expected&code=", "state=expected&code=%20",
                      "state=expected&code=a&code=b"] {
            assertError(.invalidCallback, query: query)
        }
    }

    func testForeignCallbackHostSchemePathPortAndFragmentAreRejected() {
        for value in ["https://other.invalid/auth/chzzk/callback", "http://callback.example.invalid/auth/chzzk/callback",
                      "https://callback.example.invalid/auth/chzzk/other",
                      "https://callback.example.invalid:444/auth/chzzk/callback",
                      "https://user@callback.example.invalid/auth/chzzk/callback"] {
            XCTAssertThrowsError(try CHZZKAuthorizationAttempt(state: "expected").complete(
                callback: URL(string: value + "?state=expected&code=fake")!, redirectURI: redirect
            )) { XCTAssertEqual($0 as? CHZZKAuthorizationError, .invalidCallback) }
        }
        assertError(.invalidCallback, query: "state=expected&code=fake#fragment")
    }

    func testCancellationFailureAndStateMismatchAreDistinct() {
        assertError(.cancelled, query: "state=expected&error=access_denied")
        assertError(.failed, query: "state=expected&error=server_error&error_description=private")
        assertError(.stateMismatch, query: "state=wrong&error=access_denied")
    }

    func testExpiryUsesMonotonicClockAndRejectsLateCallback() throws {
        let start = ContinuousClock.now
        let attempt = CHZZKAuthorizationAttempt(state: "expected", startedAt: start)
        XCTAssertEqual(try attempt.remaining(at: start.advanced(by: .seconds(299))), .seconds(1))
        for seconds in [300, 301] {
            XCTAssertThrowsError(try attempt.complete(callback: callback("state=expected&code=fake"),
                redirectURI: redirect, at: start.advanced(by: .seconds(seconds)))) {
                XCTAssertEqual($0 as? CHZZKAuthorizationError, .expired)
            }
        }
    }

    func testConfigurationMustMatchProviderStateRedirectAndAssociatedHost() throws {
        let attempt = CHZZKAuthorizationAttempt(state: "expected")
        let valid = configuration()
        try attempt.validate(valid, callbackHost: "callback.example.invalid")
        for config in [configuration(state: "wrong"), configuration(host: "evil.invalid"),
                       configuration(redirect: "https://other.invalid/auth/chzzk/callback"),
                       configuration(extraQuery: "&state=expected")] {
            XCTAssertThrowsError(try attempt.validate(config, callbackHost: "callback.example.invalid")) {
                XCTAssertEqual($0 as? CHZZKAuthorizationError, .configuration)
            }
        }
        XCTAssertThrowsError(try attempt.validate(valid, callbackHost: "other.invalid"))
    }

    private func callback(_ query: String) -> URL { URL(string: redirect.absoluteString + "?" + query)! }

    private func configuration(state: String = "expected", host: String = "chzzk.naver.com",
                               redirect: String? = nil, extraQuery: String = "") -> CHZZKConfiguration {
        let redirect = redirect ?? self.redirect.absoluteString
        var url = URLComponents(string: "https://\(host)/account-interlock")!
        url.queryItems = [.init(name: "state", value: state), .init(name: "clientId", value: "fake-client"),
                          .init(name: "redirectUri", value: redirect)]
        return CHZZKConfiguration(clientID: "fake-client", redirectURI: URL(string: redirect)!,
                                  authorizeURL: URL(string: url.url!.absoluteString + extraQuery)!)
    }

    private func assertError(_ error: CHZZKAuthorizationError, query: String,
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try CHZZKAuthorizationAttempt(state: "expected").complete(
            callback: callback(query), redirectURI: redirect), file: file, line: line) {
            XCTAssertEqual($0 as? CHZZKAuthorizationError, error, file: file, line: line)
        }
    }
}
