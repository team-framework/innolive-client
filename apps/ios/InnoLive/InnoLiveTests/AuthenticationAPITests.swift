import Foundation
import XCTest

@testable import InnoLive

final class AuthenticationAPITests: XCTestCase {
    override func tearDown() {
        AccountDeletionURLProtocol.reset()
        super.tearDown()
    }

    func testDeleteAccountUsesBearerDeleteWithoutBodyAndAcceptsOnlyNoContent() async throws {
        let api = makeAPI()

        try await api.deleteAccount(accessToken: "access-token")

        XCTAssertEqual(AccountDeletionURLProtocol.requests.last?.url?.path, "/auth/me")
        XCTAssertEqual(AccountDeletionURLProtocol.requests.last?.httpMethod, "DELETE")
        XCTAssertEqual(
            AccountDeletionURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"),
            "Bearer access-token"
        )
        XCTAssertEqual(
            AccountDeletionURLProtocol.requests.last?.value(forHTTPHeaderField: "Accept"),
            "application/json"
        )
        XCTAssertNil(AccountDeletionURLProtocol.requests.last?.httpBody)
    }

    func testDeleteAccountRejectsSuccessfulStatusOtherThanNoContent() async {
        AccountDeletionURLProtocol.responses = [.init(status: 202)]
        let api = makeAPI()

        do {
            try await api.deleteAccount(accessToken: "access-token")
            XCTFail("DELETE /auth/me must require 204 No Content")
        } catch let AuthenticationError.api(code, _) {
            XCTAssertNil(code)
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testAPIErrorPreservesServerCodeOnUnauthorizedResponse() async {
        AccountDeletionURLProtocol.responses = [
            .init(status: 401, json: "{\"error\":{\"code\":\"invalid_refresh_token\",\"message\":\"expired\"}}")
        ]
        let api = makeAPI()

        do {
            try await api.deleteAccount(accessToken: "access-token")
            XCTFail("unauthorized response must throw")
        } catch let AuthenticationError.api(code, _) {
            XCTAssertEqual(code, "invalid_refresh_token")
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    private func makeAPI() -> AuthenticationAPI {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountDeletionURLProtocol.self]
        let session = URLSession(configuration: configuration)
        return AuthenticationAPI(
            urlSession: session,
            serverURLProvider: { path in URL(string: "https://example.invalid\(path)") }
        )
    }
}

private final class AccountDeletionURLProtocol: StubURLProtocol {
    override class var fallbackResponse: Response { Response(status: 204) }
}
