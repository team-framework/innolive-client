import XCTest
@testable import InnoLive

@MainActor
final class BroadcastProblemAPITests: XCTestCase {
    override func setUp() { super.setUp(); FeedbackURLProtocol.requests = []; FeedbackURLProtocol.responses = [] }

    func testHTTPErrorKeepsProviderStatusFieldReasonAndHelp() async {
        FeedbackURLProtocol.responses = [(400, Data(#"{"error":{"code":"bad_request","message":"diagnostic","details":{"provider":"chzzk","field":"title","reason":"too_long","help_url":"https://example.test/help"}}}"#.utf8))]
        do {
            _ = try await api().streamingAccounts(accessToken: "fixture-access")
            XCTFail("must reject")
        } catch let YouTubeAPIError.server(value) {
            XCTAssertEqual(value.status, 400)
            XCTAssertEqual(value.provider, "chzzk")
            XCTAssertEqual(value.field, "title")
            XCTAssertEqual(value.reason, "too_long")
            XCTAssertEqual(value.helpURL?.host, "example.test")
            XCTAssertEqual(value.presentation, .inline)
            XCTAssertFalse(value.userMessage.contains("diagnostic"))
        } catch { XCTFail("unexpected error") }
    }

    func testProviderFallsBackToDisconnectRequest() async {
        FeedbackURLProtocol.responses = [(409, Data(#"{"error":{"code":"streaming_account_in_use"}}"#.utf8))]
        do { try await api().disconnectStreamingAccount(accessToken: "fixture-access", provider: "chzzk"); XCTFail("must reject") }
        catch let YouTubeAPIError.server(value) { XCTAssertEqual(value.provider, "chzzk"); XCTAssertEqual(value.status, 409) }
        catch { XCTFail("unexpected error") }
    }

    func testCancelConcurrentConfirmationMakesNoRequest() async throws {
        let integration = try integration()
        defer { integration.reset() }
        FeedbackURLProtocol.responses = [(200, snapshot()), (409, concurrentError())]
        await integration.prepareYouTubeStream(accessToken: "fixture-access")
        XCTAssertEqual(integration.problem?.presentation, .confirmation)
        let count = FeedbackURLProtocol.requests.count
        integration.cancelConcurrentPreparation()
        await integration.confirmConcurrentPreparation(accessToken: "fixture-access")
        XCTAssertEqual(FeedbackURLProtocol.requests.count, count)
        XCTAssertNil(integration.problem)
        XCTAssertNil(try requestBody(1)["allow_concurrent"])
    }

    func testConfirmConcurrentRetriesOnlyPrepareAndOnlyOnce() async throws {
        let integration = try integration()
        defer { integration.reset() }
        FeedbackURLProtocol.responses = [(200, snapshot()), (409, concurrentError()), (200, snapshot(phase: "prepared", warnings: [["code": "youtube_quota_low", "message": "fixture"]]))]
        await integration.prepareYouTubeStream(accessToken: "fixture-access")
        await integration.confirmConcurrentPreparation(accessToken: "fixture-access")
        await integration.confirmConcurrentPreparation(accessToken: "fixture-access")
        XCTAssertEqual(FeedbackURLProtocol.requests.count, 3)
        XCTAssertEqual(FeedbackURLProtocol.requests[2].url?.path, "/sessions/fixture-session/stream/prepare")
        XCTAssertEqual(try requestBody(2)["allow_concurrent"] as? Bool, true)
        XCTAssertEqual(try requestBody(2)["provider"] as? String, "youtube")
        XCTAssertEqual(integration.broadcastPhase, "prepared")
        XCTAssertEqual(integration.noticeBanners.count, 1)
    }

    func testResetInvalidatesPendingConcurrentConsent() async throws {
        let integration = try integration()
        FeedbackURLProtocol.responses = [(200, snapshot()), (409, concurrentError())]
        await integration.prepareYouTubeStream(accessToken: "fixture-access")
        integration.reset()
        await integration.confirmConcurrentPreparation(accessToken: "fixture-access")
        XCTAssertEqual(FeedbackURLProtocol.requests.count, 2)
    }

    func testEditedSettingsInvalidatesPendingConcurrentConsent() async throws {
        let integration = try integration()
        defer { integration.reset() }
        FeedbackURLProtocol.responses = [(200, snapshot()), (409, concurrentError())]
        await integration.prepareYouTubeStream(accessToken: "fixture-access")
        integration.broadcastSettings.title = "Changed"
        await integration.confirmConcurrentPreparation(accessToken: "fixture-access")
        XCTAssertEqual(FeedbackURLProtocol.requests.count, 2)
    }

    func testUnauthorizedRefreshRetriesOriginalRequestOnce() async throws {
        let client = api()
        var token = "fixture-access"
        var refreshes = 0
        client.configureAuthentication(accessTokenProvider: { token }, refreshSession: { token = "fixture-refreshed"; refreshes += 1; return .refreshed }, onInvalidRefresh: {})
        FeedbackURLProtocol.responses = [(401, Data(#"{"error":{"code":"unauthorized"}}"#.utf8)), (200, Data("[]".utf8))]
        _ = try await client.streamingAccounts(accessToken: token)
        XCTAssertEqual(refreshes, 1)
        XCTAssertEqual(FeedbackURLProtocol.requests.count, 2)
        XCTAssertEqual(FeedbackURLProtocol.requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer fixture-refreshed")
    }

    func testNotReadyStillRetriesGoLiveAndRemainsPreparedOnFailure() async throws {
        let integration = try integration(phase: "prepared")
        defer { integration.reset() }
        let stopped = Data(#"{"error":{"code":"broadcast_stopped"}}"#.utf8)
        FeedbackURLProtocol.responses = [(409, Data(#"{"error":{"code":"broadcast_not_ready"}}"#.utf8)), (409, stopped)]
        await integration.goLiveYouTubeStream(accessToken: "fixture-access")
        XCTAssertEqual(FeedbackURLProtocol.requests.count, 2)
        XCTAssertTrue(FeedbackURLProtocol.requests.allSatisfy { $0.url?.path.hasSuffix("/golive") == true })
        XCTAssertEqual(integration.broadcastPhase, "prepared")
        XCTAssertEqual(integration.problem?.code, "broadcast_stopped")
    }

    func testMalformedErrorResponseDoesNotExposeRawDiagnostics() async {
        FeedbackURLProtocol.responses = [(500, Data("<html>private diagnostics</html>".utf8))]
        do { _ = try await api().streamingAccounts(accessToken: "fixture-access"); XCTFail("must reject") }
        catch let YouTubeAPIError.server(value) { XCTAssertEqual(value.status, 500); XCTAssertNil(value.code); XCTAssertFalse(value.userMessage.contains("private")) }
        catch { XCTFail("unexpected error") }
    }

    func testPlanRouteReadsLimitsAndDoesNotSendMutation() async throws {
        FeedbackURLProtocol.responses = [(200, Data(#"{"plan":"pro","allowed_modes":["720p_single","fhd_multi"],"monthly_broadcast_seconds":0,"max_per_broadcast_seconds":0}"#.utf8))]
        let plan = try await api().broadcastPlanInformation(accessToken: "fixture-access")
        XCTAssertEqual(plan.plan, "pro")
        XCTAssertEqual(plan.allowedModes, ["720p_single", "fhd_multi"])
        XCTAssertEqual(plan.monthlyBroadcastSeconds, 0)
        XCTAssertEqual(plan.maxPerBroadcastSeconds, 0)
        XCTAssertEqual(FeedbackURLProtocol.requests.first?.httpMethod, "GET")
        XCTAssertEqual(FeedbackURLProtocol.requests.first?.url?.path, "/users/me/plan")
    }

    func testTargetActionPreservesProviderWithoutDetails() async throws {
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(SessionStateFixture.json().utf8))
        FeedbackURLProtocol.responses = [(429, Data(#"{"error":{"code":"streaming_rate_limited"}}"#.utf8))]
        do { _ = try await api().streamAction(.goLive, session: session, accessToken: "fixture-access", provider: "chzzk"); XCTFail("must reject") }
        catch let YouTubeAPIError.server(value) { XCTAssertEqual(value.provider, "chzzk"); XCTAssertEqual(value.status, 429) }
        catch { XCTFail("unexpected error") }
    }

    private func api() -> YouTubeAPI {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [FeedbackURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: config), serverURLProvider: { URL(string: "https://example.test\($0)") })
    }
    private func integration(phase: String = "idle") throws -> YouTubeIntegration {
        let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: "feedback-api.\(UUID().uuidString)")!), api: api())
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: snapshot(phase: phase)) as? [String: Any])
        object["session_id"] = "fixture-session"
        object["owner_token"] = "fixture-owner"
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: JSONSerialization.data(withJSONObject: object))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "fixture", title: "fixture")), session: session, videoTrack: .init(id: "fixture", kind: "video", readyState: "live"))
        integration.broadcastSettings = .init(title: "Fixture title", description: "", privacy: .private, audience: .notMadeForKids)
        return integration
    }
    private func snapshot(phase: String = "idle", warnings: [[String: String]] = []) -> Data {
        try! JSONSerialization.data(withJSONObject: ["stream": ["status": "idle", "publisher_active": true, "reconnect_attempts": 0, "broadcast_phase": phase], "media": ["raw_video_track": ["id": "fixture", "kind": "video", "ready_state": "live"], "anonymization_enabled": false], "warnings": warnings])
    }
    private func concurrentError() -> Data { Data(#"{"error":{"code":"channel_already_live","message":"already live"}}"#.utf8) }
    private func requestBody(_ index: Int) throws -> [String: Any] {
        let request = FeedbackURLProtocol.requests[index]
        var data = request.httpBody
        if data == nil, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var bytes = [UInt8](repeating: 0, count: 4096); var value = Data()
            while stream.hasBytesAvailable { let count = stream.read(&bytes, maxLength: bytes.count); if count <= 0 { break }; value.append(bytes, count: count) }
            data = value
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(data)) as? [String: Any])
    }
}

private final class FeedbackURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var responses: [(Int, Data)] = []
    nonisolated(unsafe) static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        guard !Self.responses.isEmpty else { client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return }
        let (status, data) = Self.responses.removeFirst()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
