import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class PlanAPITests: XCTestCase {
    override func tearDown() {
        PlanURLProtocol.responses = []
        PlanURLProtocol.requests = []
        super.tearDown()
    }
    private func api() -> YouTubeAPI {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [PlanURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: config), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
    }
    func testPlanAndUsageUseAuthenticatedGETWithoutOwnerToken() async throws {
        PlanURLProtocol.responses = [
            (200, Data("{\"plan\":\"spark\",\"allowed_modes\":[\"720p_single\"],\"monthly_broadcast_seconds\":18000,\"max_per_broadcast_seconds\":7200}".utf8)),
            (200, Data("{\"plan\":\"spark\",\"used_seconds\":0,\"remaining_seconds\":18000,\"available_by_mode\":[]}".utf8))
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
            (401, Data("{\"error\":{\"code\":\"unauthorized\"}}".utf8)),
            (200, Data("{\"plan\":\"glow\",\"used_seconds\":0,\"remaining_seconds\":null,\"available_by_mode\":[]}".utf8))
        ]
        let client = api()
        var refreshes = 0
        client.configureAuthentication(accessTokenProvider: { refreshes == 0 ? "old" : "new" }, refreshSession: {
            refreshes += 1
            return .refreshed
        }, onInvalidRefresh: {})
        let usage = try await client.userUsage(accessToken: "old")
        XCTAssertNil(usage.remainingSeconds)
        XCTAssertEqual(refreshes, 1)
        XCTAssertEqual(PlanURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer new")
    }
}

private final class PlanURLProtocol: URLProtocol {
    static var responses: [(Int, Data)] = []
    static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        guard !Self.responses.isEmpty else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        let (status, data) = Self.responses.removeFirst()
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
