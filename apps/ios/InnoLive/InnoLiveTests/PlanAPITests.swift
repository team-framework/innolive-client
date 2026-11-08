import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class PlanAPITests: XCTestCase {
    override func tearDown() {
        PlanURLProtocol.reset()
        super.tearDown()
    }
    private func api() -> YouTubeAPI {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [PlanURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: config), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
    }
    func testPlanAndUsageUseAuthenticatedGETWithoutOwnerToken() async throws {
        PlanURLProtocol.responses = [
            .init(status: 200, json: "{\"plan\":\"spark\",\"allowed_modes\":[\"720p_single\"],\"monthly_broadcast_seconds\":18000,\"max_per_broadcast_seconds\":7200}"),
            .init(status: 200, json: "{\"plan\":\"spark\",\"used_seconds\":0,\"remaining_seconds\":18000,\"available_by_mode\":[]}")
        ]
        let client = api()
        _ = try await client.userPlan(accessToken: "test-token")
        _ = try await client.userUsage(accessToken: "test-token")
        XCTAssertEqual(PlanURLProtocol.requests.map { $0.url?.path }, ["/users/me/plan", "/users/me/usage"])
        for request in PlanURLProtocol.requests {
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
            XCTAssertNil(request.value(forHTTPHeaderField: "X-Session-Owner-Token"))
        }
    }
    func testUsageRefreshesExpiredAuthenticationOnce() async throws {
        PlanURLProtocol.responses = [
            .init(status: 401, json: "{\"error\":{\"code\":\"unauthorized\"}}"),
            .init(status: 200, json: "{\"plan\":\"glow\",\"used_seconds\":0,\"remaining_seconds\":null,\"available_by_mode\":[]}")
        ]
        let client = api()
        var refreshes = 0
        client.configureAuthentication(accessTokenProvider: { refreshes == 0 ? "old" : "new" }, refreshSession: {
            refreshes += 1
            return .refreshed
        })
        let usage = try await client.userUsage(accessToken: "old")
        XCTAssertNil(usage.remainingSeconds)
        XCTAssertEqual(refreshes, 1)
        XCTAssertEqual(PlanURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer new")
    }
}

private final class PlanURLProtocol: StubURLProtocol {
    override class var fallbackResponse: Response { .failure(.badServerResponse) }
    override class var defaultHeaders: [String: String] { [:] }
}
