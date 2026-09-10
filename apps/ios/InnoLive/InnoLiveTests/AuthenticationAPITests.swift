import Foundation
import XCTest

@testable import InnoLive

final class AuthenticationAPITests: XCTestCase {
    override func tearDown() {
        AccountDeletionURLProtocol.lastRequest = nil
        AccountDeletionURLProtocol.statusCode = 204
        AccountDeletionURLProtocol.responseData = Data()
        super.tearDown()
    }

    func testDeleteAccountUsesBearerDeleteWithoutBodyAndAcceptsOnlyNoContent() async throws {
        let api = makeAPI()

        try await api.deleteAccount(accessToken: "access-token")

        XCTAssertEqual(AccountDeletionURLProtocol.lastRequest?.url?.path, "/auth/me")
        XCTAssertEqual(AccountDeletionURLProtocol.lastRequest?.httpMethod, "DELETE")
        XCTAssertEqual(
            AccountDeletionURLProtocol.lastRequest?.value(forHTTPHeaderField: "Authorization"),
            "Bearer access-token"
        )
        XCTAssertEqual(
            AccountDeletionURLProtocol.lastRequest?.value(forHTTPHeaderField: "Accept"),
            "application/json"
        )
        XCTAssertNil(AccountDeletionURLProtocol.lastRequest?.httpBody)
    }

    func testDeleteAccountRejectsSuccessfulStatusOtherThanNoContent() async {
        AccountDeletionURLProtocol.statusCode = 202
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
        AccountDeletionURLProtocol.statusCode = 401
        AccountDeletionURLProtocol.responseData = Data(
            "{\"error\":{\"code\":\"invalid_refresh_token\",\"message\":\"expired\"}}".utf8
        )
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

private final class AccountDeletionURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var statusCode = 204
    nonisolated(unsafe) static var responseData = Data()

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: Self.statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !Self.responseData.isEmpty {
            client?.urlProtocol(self, didLoad: Self.responseData)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
