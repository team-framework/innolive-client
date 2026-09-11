import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeAccountAPITests: XCTestCase {
    override func tearDown() {
        AccountManagementURLProtocol.responses = []
        AccountManagementURLProtocol.requests = []
        super.tearDown()
    }

    func testStreamingAccountsDecodesYouTubeSummaryAndReconnectState() async throws {
        AccountManagementURLProtocol.responses = [
            .init(
                statusCode: 200,
                data: Data(
                    """
                    [{
                      "provider": "youtube",
                      "channel_id": "UCabc",
                      "channel_title": "Team Framework",
                      "connected_at": "2026-09-12T00:00:00Z",
                      "reconnect_required": true
                    }]
                    """.utf8
                )
            )
        ]
        let api = makeAPI()

        let accounts = try await api.streamingAccounts(accessToken: "access-token")
        let connection = try XCTUnwrap(accounts.first?.youtubeConnection)

        XCTAssertEqual(connection.provider, "youtube")
        XCTAssertEqual(connection.channel, YouTubeChannel(id: "UCabc", title: "Team Framework"))
        XCTAssertTrue(connection.requiresReconnection)
        XCTAssertEqual(AccountManagementURLProtocol.requests.first?.url?.path, "/auth/streaming/accounts")
        XCTAssertEqual(
            AccountManagementURLProtocol.requests.first?.value(forHTTPHeaderField: "Authorization"),
            "Bearer access-token"
        )
    }

    func testStreamingAccountsRetriesUnauthorizedAfterAuthenticationRefresh() async throws {
        AccountManagementURLProtocol.responses = [
            .init(statusCode: 401, data: Data("{\"error\":{\"code\":\"unauthorized\"}}".utf8)),
            .init(statusCode: 200, data: Data("[]".utf8))
        ]
        var refreshCallCount = 0
        let api = makeAPI()
        api.configureAuthentication(
            accessTokenProvider: {
                refreshCallCount == 0 ? "expired-access-token" : "refreshed-access-token"
            },
            refreshSession: {
                refreshCallCount += 1
                return .refreshed
            },
            onInvalidRefresh: {}
        )

        let accounts = try await api.streamingAccounts(accessToken: "fallback-access-token")

        XCTAssertTrue(accounts.isEmpty)
        XCTAssertEqual(refreshCallCount, 1)
        XCTAssertEqual(AccountManagementURLProtocol.requests.count, 2)
        XCTAssertEqual(
            AccountManagementURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"),
            "Bearer refreshed-access-token"
        )
    }

    func testDisconnectStreamingAccountUsesDeleteAndRequires204() async throws {
        AccountManagementURLProtocol.responses = [.init(statusCode: 204, data: Data())]
        let api = makeAPI()

        try await api.disconnectStreamingAccount(accessToken: "access-token")

        let request = try XCTUnwrap(AccountManagementURLProtocol.requests.first)
        XCTAssertEqual(request.url?.path, "/auth/streaming/accounts/youtube")
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-token")
        XCTAssertNil(request.httpBody)
    }

    func testDisconnectStreamingAccountRejectsSuccessfulStatusOtherThan204() async {
        AccountManagementURLProtocol.responses = [.init(statusCode: 202, data: Data())]
        let api = makeAPI()

        do {
            try await api.disconnectStreamingAccount(accessToken: "access-token")
            XCTFail("disconnect must require 204 No Content")
        } catch let error as YouTubeAPIError {
            XCTAssertEqual(error, .response)
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testDisconnectDoesNotRetryAfterAuthenticationRequestInvalidation() async {
        AccountManagementURLProtocol.responses = [
            .init(statusCode: 401, data: Data("{\"error\":{\"code\":\"unauthorized\"}}".utf8)),
            .init(statusCode: 204, data: Data())
        ]
        let refreshStarted = expectation(description: "authentication refresh started")
        let api = makeAPI()
        api.configureAuthentication(
            accessTokenProvider: { "refreshed-access-token" },
            refreshSession: {
                refreshStarted.fulfill()
                try? await Task.sleep(for: .milliseconds(200))
                return .refreshed
            },
            onInvalidRefresh: {}
        )

        let deleteTask = Task { () -> Error? in
            do {
                try await api.disconnectStreamingAccount(accessToken: "expired-access-token")
                return nil
            } catch {
                return error
            }
        }
        await fulfillment(of: [refreshStarted], timeout: 1)
        api.invalidateAuthenticationRequests()

        let error = await deleteTask.value
        XCTAssertNotNil(error)
        XCTAssertEqual(AccountManagementURLProtocol.requests.count, 1)
    }

    private func makeAPI() -> YouTubeAPI {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountManagementURLProtocol.self]
        let session = URLSession(configuration: configuration)
        return YouTubeAPI(
            urlSession: session,
            serverURLProvider: { path in URL(string: "https://example.invalid\(path)") }
        )
    }
}

private final class AccountManagementURLProtocol: URLProtocol {
    struct Response {
        let statusCode: Int
        let data: Data
    }

    nonisolated(unsafe) static var responses: [Response] = []
    nonisolated(unsafe) static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let response = Self.responses.isEmpty
            ? Response(statusCode: 500, data: Data())
            : Self.responses.removeFirst()
        let httpResponse = HTTPURLResponse(
            url: request.url!,
            statusCode: response.statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: httpResponse, cacheStoragePolicy: .notAllowed)
        if !response.data.isEmpty {
            client?.urlProtocol(self, didLoad: response.data)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
