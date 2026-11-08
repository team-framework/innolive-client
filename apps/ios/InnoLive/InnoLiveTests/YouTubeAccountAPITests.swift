import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeAccountAPITests: XCTestCase {
    override func tearDown() {
        AccountManagementURLProtocol.reset()
        super.tearDown()
    }

    func testStreamingAccountsDecodesYouTubeSummaryAndReconnectState() async throws {
        AccountManagementURLProtocol.responses = [
            .init(
                status: 200,
                body: Data(
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
            .init(status: 401, json: "{\"error\":{\"code\":\"unauthorized\"}}"),
            .init(status: 200, json: "[]")
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
            }
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

    func testUnknownYouTubeServerErrorsDoNotExposeResponseMessages() async {
        for body in [
            "{\"error\":{\"code\":\"internal_error\",\"message\":\"private server details\"}}",
            "<html>private diagnostics</html>"
        ] {
            AccountManagementURLProtocol.responses = [
                .init(status: 500, body: Data(body.utf8))
            ]

            do {
                _ = try await makeAPI().streamingAccounts(accessToken: "access-token")
                XCTFail("an unknown server error must fail")
            } catch let error as YouTubeAPIError {
                XCTAssertEqual(error.userMessage, String(localized: "방송 요청을 처리하지 못했습니다."))
                XCTAssertFalse(error.userMessage.contains("private"))
            } catch {
                XCTFail("unexpected error: \(error)")
            }
        }
    }

    func testKnownYouTubeServerErrorStillUsesRecoveryMessage() async {
        AccountManagementURLProtocol.responses = [
            .init(
                status: 422,
                json: "{\"error\":{\"code\":\"youtube_channel_missing\",\"message\":\"private server details\"}}"
            )
        ]

        do {
            _ = try await makeAPI().streamingAccounts(accessToken: "access-token")
            XCTFail("missing channel must fail")
        } catch let error as YouTubeAPIError {
            XCTAssertEqual(
                error.userMessage,
                String(localized: "이 Google 계정에 YouTube 채널이 없습니다. 채널을 먼저 만들어 주세요.")
            )
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testDisconnectStreamingAccountUsesDeleteAndRequires204() async throws {
        AccountManagementURLProtocol.responses = [.init(status: 204)]
        let api = makeAPI()

        try await api.disconnectStreamingAccount(accessToken: "access-token")

        let request = try XCTUnwrap(AccountManagementURLProtocol.requests.first)
        XCTAssertEqual(request.url?.path, "/auth/streaming/accounts/youtube")
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-token")
        XCTAssertNil(request.httpBody)
    }

    func testDisconnectStreamingAccountRejectsSuccessfulStatusOtherThan204() async {
        AccountManagementURLProtocol.responses = [.init(status: 202)]
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
            .init(status: 401, json: "{\"error\":{\"code\":\"unauthorized\"}}"),
            .init(status: 204)
        ]
        let refreshStarted = expectation(description: "authentication refresh started")
        let api = makeAPI()
        api.configureAuthentication(
            accessTokenProvider: { "refreshed-access-token" },
            refreshSession: {
                refreshStarted.fulfill()
                try? await Task.sleep(for: .milliseconds(200))
                return .refreshed
            }
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

    func testDisconnectSpecificChannelUsesAccountIDPath() async throws {
        AccountManagementURLProtocol.responses = [.init(status: 204)]
        try await makeAPI().disconnectStreamingAccount(accessToken: "access", accountID: "account-b")
        XCTAssertEqual(AccountManagementURLProtocol.requests.last?.url?.path, "/auth/streaming/accounts/youtube/account-b")
    }

    func testPrepareAndModeCarryAccountIDButExistingTargetModeOmitsIt() async throws {
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data("{\"session_id\":\"session\",\"owner_token\":\"owner\",\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0}}".utf8))
        let api = makeAPI()
        for operation in 0..<3 {
            AccountManagementURLProtocol.responses = [.init(status: 200, json: "{}")]
            if operation == 0 { _ = try? await api.prepareStream(session: session, accessToken: "access", accountID: "account-b") }
            else { _ = try? await api.changeBroadcastMode(session: session, accessToken: "access", resolution: "fhd", targets: [.youtube], accountID: operation == 1 ? "account-b" : nil) }
            let request = try XCTUnwrap(AccountManagementURLProtocol.requests.last)
            let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.bodyData)) as? [String: Any])
            XCTAssertEqual(body["account_id"] as? String, operation == 2 ? nil : "account-b")
        }
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

    func testDeleteSessionUsesOwnerAndAccessTokens() async throws {
        AccountManagementURLProtocol.responses = [.init(status: 204)]
        try await makeAPI().deleteSession(.init(sessionID: "previous", ownerToken: "owner"), accessToken: "access")
        let request = try XCTUnwrap(AccountManagementURLProtocol.requests.first)
        XCTAssertEqual(request.url?.path, "/sessions/previous")
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "owner")
        XCTAssertNil(request.httpBody)
    }

    func testDeleteSessionAcceptsOnlyNoContentOrSessionNotFound() async {
        for (status, body, succeeds) in [
            (204, "", true), (404, "{\"error\":{\"code\":\"not_found\"}}", true),
            (404, "", false), (202, "", false), (403, "", false), (503, "", false)
        ] {
            AccountManagementURLProtocol.responses = [.init(status: status, body: Data(body.utf8))]
            do {
                try await makeAPI().deleteSession(.init(sessionID: "old", ownerToken: "owner"), accessToken: "access")
                XCTAssertTrue(succeeds, "unexpected success: \(status)")
            } catch { XCTAssertFalse(succeeds, "unexpected failure: \(status)") }
        }
    }

    func testDeleteSessionRefreshesAccessTokenAndKeepsOwnerToken() async throws {
        AccountManagementURLProtocol.responses = [
            .init(status: 401), .init(status: 204)
        ]
        var refreshed = false
        let api = makeAPI()
        api.configureAuthentication(
            accessTokenProvider: { refreshed ? "new" : "old" },
            refreshSession: { refreshed = true; return .refreshed }
        )
        try await api.deleteSession(.init(sessionID: "session", ownerToken: "owner"), accessToken: "old")
        XCTAssertEqual(AccountManagementURLProtocol.requests.count, 2)
        XCTAssertEqual(AccountManagementURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer new")
        XCTAssertEqual(AccountManagementURLProtocol.requests.last?.value(forHTTPHeaderField: "X-Session-Owner-Token"), "owner")
    }
}

private final class AccountManagementURLProtocol: StubURLProtocol {}
