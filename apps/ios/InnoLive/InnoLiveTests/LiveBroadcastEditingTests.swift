import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class LiveBroadcastEditingTests: XCTestCase {
    private var api: YouTubeAPI!
    private var integration: YouTubeIntegration!
    private var suite: String!
    private var token: String { "h." + Data("{\"sub\":\"live-editor-user\"}".utf8).base64EncodedString() + ".s" }
    private var editor: BroadcastSettingsEditor { integration.liveSettingsEditor }
    override func setUp() {
        suite = "live-edit-tests-\(UUID())"
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [LiveEditURLProtocol.self]
        api = YouTubeAPI(urlSession: URLSession(configuration: config), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: suite)!), api: api)
        LiveEditURLProtocol.requests = []
        LiveEditURLProtocol.responses = []
    }
    override func tearDown() {
        integration.reset()
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        LiveEditURLProtocol.responses = []
        LiveEditURLProtocol.requests = []
    }
    private func snapshot(youtube: String = "live", chzzk: String = "live") -> String {
        let stream: (String) -> String = { phase in
            "{\"status\":\"\(phase == "live" ? "streaming" : "stopped")\",\"broadcast_phase\":\"\(phase)\",\"publisher_active\":true,\"reconnect_attempts\":0}"
        }
        return "{\"session_id\":\"session\",\"owner_token\":\"test-owner\",\"stream\":\(stream(youtube)),\"targets\":[{\"provider\":\"youtube\",\"stream\":\(stream(youtube))},{\"provider\":\"chzzk\",\"stream\":\(stream(chzzk))}],\"media\":{}}"
    }
    private func seed(youtube: String = "live", chzzk: String = "live", id: String = "session") throws {
        let data = Data(snapshot(youtube: youtube, chzzk: chzzk).replacingOccurrences(of: "\"session\"", with: "\"\(id)\"").utf8)
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: data)
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "test-yt", title: "YT")), session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"))
        integration.settingsEditor.editYouTube { $0.title = "old yt"; $0.audience = .notMadeForKids; $0.categoryID = "20" }
        integration.settingsEditor.editCHZZK { $0.title = "old cz" }
    }
    private func begin(_ provider: BroadcastSettingsProvider = .youtube) { integration.beginLiveEditing(provider, accessToken: token) }
    private func enqueue(_ body: String, status: Int = 200, delay: TimeInterval = 0) {
        LiveEditURLProtocol.responses.append(.init(body: Data(body.utf8), status: status, delay: delay))
    }
    private func body(_ request: URLRequest) throws -> [String: Any] {
        if let data = request.httpBody { return try JSONSerialization.jsonObject(with: data) as! [String: Any] }
        let input = try XCTUnwrap(request.httpBodyStream)
        input.open(); defer { input.close() }
        var data = Data(), buffer = [UInt8](repeating: 0, count: 2048)
        while input.hasBytesAvailable { let n = input.read(&buffer, maxLength: buffer.count); if n <= 0 { break }; data.append(buffer, count: n) }
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }
    private func waitForRequest() async {
        for _ in 0..<100 { if !LiveEditURLProtocol.requests.isEmpty { return }; try? await Task.sleep(for: .milliseconds(5)) }
        XCTFail("PATCH did not begin")
    }
    func testYouTubePayloadOnlyAllowedFieldsAndNoRestart() async throws {
        try seed(); begin(); editor.editYouTube { $0.title = "  new title  "; $0.description = "description" }
        enqueue(snapshot())
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertTrue(saved)
        let request = try XCTUnwrap(LiveEditURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertEqual(request.url?.path, "/sessions/session/broadcast/live")
        XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "youtube")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "test-owner")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + token)
        let payload = try body(request)
        XCTAssertEqual(Set(payload.keys), ["title", "description", "category_id"])
        XCTAssertEqual(payload["title"] as? String, "new title")
        XCTAssertEqual(integration.settingsEditor.youtube.title, "new title")
        XCTAssertEqual(integration.settingsEditor.youtube.privacy, .private)
        XCTAssertEqual(integration.session?.sessionID, "session")
        XCTAssertEqual(integration.liveEditingTargets, [.youtube, .chzzk])
        XCTAssertEqual(LiveEditURLProtocol.requests.count, 1)
        begin(); XCTAssertEqual(editor.youtube.title, "new title")
    }
    func testCHZZKNoCategoryAndEmptyTagsAreExplicit() async throws {
        try seed(); begin(.chzzk); editor.editCHZZK { $0.title = "new cz"; $0.tags = [] }
        enqueue(snapshot())
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertTrue(saved)
        let request = try XCTUnwrap(LiveEditURLProtocol.requests.first)
        let payload = try body(request)
        XCTAssertEqual(Set(payload.keys), ["title", "category_type", "category_id", "tags"])
        XCTAssertEqual(payload["category_type"] as? String, "")
        XCTAssertEqual(payload["category_id"] as? String, "")
        XCTAssertEqual((payload["tags"] as? [String])?.count, 0)
        XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "chzzk")
        XCTAssertEqual(integration.settingsEditor.youtube.title, "old yt")
    }
    func testValidationDoesNotRequireLockedAudienceButRejectsBadTitle() async throws {
        try seed(); begin(); editor.editYouTube { $0.audience = nil; $0.title = "  " }
        let invalid = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(invalid); XCTAssertNotNil(editor.fieldErrors["title"]); XCTAssertNil(editor.fieldErrors["made_for_kids"])
        editor.editYouTube { $0.title = "valid" }; enqueue(snapshot())
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertTrue(saved)
        XCTAssertEqual(LiveEditURLProtocol.requests.count, 1)
    }
    func testInvalidCHZZKCategoryAndTagsNeverCallAPI() async throws {
        try seed(); begin(.chzzk); editor.editCHZZK { $0.categoryType = "GAME"; $0.tags = ["has space"] }
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved); XCTAssertNotNil(editor.fieldErrors["category_id"]); XCTAssertNotNil(editor.fieldErrors["tags"])
        XCTAssertTrue(LiveEditURLProtocol.requests.isEmpty)
    }
    func testFailureKeepsInputAndRetryUpdatesOnlyOnSuccess() async throws {
        try seed(); begin(); editor.editYouTube { $0.title = "retry title" }
        enqueue("{\"error\":{\"code\":\"streaming_update_failed\"}}", status: 502)
        let failed = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(failed); XCTAssertEqual(editor.youtube.title, "retry title")
        XCTAssertEqual(integration.settingsEditor.youtube.title, "old yt")
        XCTAssertNotNil(integration.liveSettingsError)
        XCTAssertFalse(integration.isSavingLiveSettings)
        enqueue(snapshot()); let retried = await integration.saveLiveSettings(accessToken: token)
        XCTAssertTrue(retried); XCTAssertNil(integration.liveSettingsError)
        XCTAssertEqual(integration.settingsEditor.youtube.title, "retry title")
    }
    func testBadRequestFieldAppearsBesideInput() async throws {
        try seed(); begin(); editor.editYouTube { $0.title = "typed" }
        enqueue("{\"error\":{\"code\":\"bad_request\",\"details\":{\"field\":\"title\",\"reason\":\"contains invalid characters\"}}}", status: 400)
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved); XCTAssertNotNil(editor.fieldErrors["title"]); XCTAssertEqual(editor.youtube.title, "typed")
    }
    func testForbiddenFieldArrayMapsToEachLockedField() async throws {
        try seed(); begin()
        enqueue("{\"error\":{\"code\":\"field_not_changeable_live\",\"details\":{\"fields\":[\"privacy\",\"made_for_kids\",\"thumbnail\"],\"provider\":\"youtube\"}}}", status: 400)
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved)
        for field in ["privacy", "made_for_kids", "thumbnail"] { XCTAssertNotNil(editor.fieldErrors[field]) }
    }
    func testDuplicateTapMakesOneRequestAndCannotSwitchEditorDuringSave() async throws {
        try seed(); begin(); enqueue(snapshot(), delay: 0.15)
        let task = Task { await integration.saveLiveSettings(accessToken: token) }
        await waitForRequest(); XCTAssertTrue(integration.isSavingLiveSettings)
        integration.beginLiveEditing(.chzzk, accessToken: token)
        XCTAssertEqual(editor.provider, .youtube)
        let second = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(second)
        let first = await task.value
        XCTAssertTrue(first); XCTAssertEqual(LiveEditURLProtocol.requests.count, 1)
        XCTAssertFalse(integration.isSavingLiveSettings)
    }
    func testIdleTargetCannotEditWhileOtherTargetIsLive() async throws {
        try seed(youtube: "idle"); begin()
        XCTAssertFalse(integration.canEditLiveBroadcast(.youtube)); XCTAssertTrue(integration.canEditLiveBroadcast(.chzzk))
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved); XCTAssertTrue(LiveEditURLProtocol.requests.isEmpty)
    }
    func testExplicitEmptyTargetsNeverFallBackToLegacyLiveStream() throws {
        try seed()
        let json = snapshot()
        var object = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
        object["targets"] = []
        let snapshot = try JSONDecoder().decode(YouTubeSessionResponse.self, from: JSONSerialization.data(withJSONObject: object))
        integration.applyLiveEditingSnapshotForTesting(snapshot)
        XCTAssertTrue(integration.liveEditingTargets.isEmpty)
    }
    func testNoSessionAndMissingAuthenticationNeverPatch() async throws {
        let empty = await integration.saveLiveSettings(accessToken: token); XCTAssertFalse(empty)
        try seed(); begin()
        let missing = await integration.saveLiveSettings(accessToken: nil); XCTAssertFalse(missing)
        XCTAssertTrue(LiveEditURLProtocol.requests.isEmpty)
    }
    func testLateSuccessAfterTargetEndsCannotRestoreLiveOrCommitTitle() async throws {
        try seed(); begin(); editor.editYouTube { $0.title = "late title" }; enqueue(snapshot(), delay: 0.1)
        let task = Task { await integration.saveLiveSettings(accessToken: token) }
        await waitForRequest()
        integration.applyLiveEditingSnapshotForTesting(try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(snapshot(youtube: "idle").utf8)))
        let saved = await task.value
        XCTAssertFalse(saved); XCTAssertFalse(integration.canEditLiveBroadcast(.youtube))
        XCTAssertEqual(integration.liveEditingTargets, [.chzzk]); XCTAssertEqual(integration.settingsEditor.youtube.title, "old yt")
    }
    func testLateSuccessKeepsNewerOtherTargetState() async throws {
        try seed(); begin(); editor.editYouTube { $0.title = "saved title" }; enqueue(snapshot(), delay: 0.1)
        let task = Task { await integration.saveLiveSettings(accessToken: token) }; await waitForRequest()
        integration.applyLiveEditingSnapshotForTesting(try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(snapshot(chzzk: "idle").utf8)))
        let saved = await task.value
        XCTAssertTrue(saved); XCTAssertEqual(integration.liveEditingTargets, [.youtube])
    }
    func testLateResponseAfterAccountResetCannotCommitOrShowError() async throws {
        for status in [200, 502] {
            try seed(); begin(); editor.editYouTube { $0.title = "old account" }
            LiveEditURLProtocol.requests = []
            enqueue(status == 200 ? snapshot() : "{}", status: status, delay: 0.1)
            let task = Task { await integration.saveLiveSettings(accessToken: token) }; await waitForRequest()
            integration.reset()
            let saved = await task.value
            XCTAssertFalse(saved); XCTAssertNil(integration.session); XCTAssertNil(integration.liveSettingsError)
            XCTAssertNotEqual(editor.youtube.title, "old account"); XCTAssertFalse(integration.isSavingLiveSettings)
        }
    }
    func testEditorFromPreviousSessionCannotPatchNewSession() async throws {
        try seed(); begin(); try seed(id: "next-session")
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved); XCTAssertTrue(LiveEditURLProtocol.requests.isEmpty)
    }
    func testBroadcastNotLiveLocksEditingEvenWhenStatusRefreshFails() async throws {
        try seed(); begin(); editor.editYouTube { $0.title = "typed" }
        enqueue("{\"error\":{\"code\":\"broadcast_not_live\"}}", status: 409); enqueue("{}", status: 503)
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved); XCTAssertFalse(integration.canEditLiveBroadcast(.youtube))
        XCTAssertEqual(editor.youtube.title, "typed")
        let retry = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(retry); XCTAssertEqual(LiveEditURLProtocol.requests.count, 2)
    }
    func testNewBroadcastInSameSessionUnlocksPreviouslyEndedTarget() async throws {
        try seed(); begin()
        enqueue("{\"error\":{\"code\":\"broadcast_not_live\"}}", status: 409); enqueue(snapshot(youtube: "idle"))
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved); XCTAssertFalse(integration.canEditLiveBroadcast(.youtube))
        integration.applyLiveEditingSnapshotForTesting(try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(snapshot().utf8)))
        XCTAssertTrue(integration.canEditLiveBroadcast(.youtube))
    }
    func testAuthenticationRefreshRetriesPatchOnceWithSamePayload() async throws {
        try seed(); begin()
        var access = token, refreshed = 0
        api.configureAuthentication(accessTokenProvider: { access }, refreshSession: { refreshed += 1; access = self.token + "renewed"; return .refreshed }, onInvalidRefresh: {})
        enqueue("{\"error\":{\"code\":\"unauthorized\"}}", status: 401); enqueue(snapshot())
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertTrue(saved); XCTAssertEqual(refreshed, 1); XCTAssertEqual(LiveEditURLProtocol.requests.count, 2)
        XCTAssertEqual(LiveEditURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer " + token + "renewed")
        guard LiveEditURLProtocol.requests.count == 2 else { return }
        XCTAssertEqual(try body(LiveEditURLProtocol.requests[0]) as NSDictionary, try body(LiveEditURLProtocol.requests[1]) as NSDictionary)
    }
}

private final class LiveEditURLProtocol: URLProtocol, @unchecked Sendable {
    struct Response { let body: Data; let status: Int; let delay: TimeInterval }
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var responses: [Response] = []
    private var work: DispatchWorkItem?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let response = Self.responses.isEmpty ? Response(body: Data(), status: 500, delay: 0) : Self.responses.removeFirst()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.client?.urlProtocol(self, didReceive: HTTPURLResponse(url: self.request.url!, statusCode: response.status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: response.body)
            self.client?.urlProtocolDidFinishLoading(self)
        }
        self.work = work
        DispatchQueue.main.asyncAfter(deadline: .now() + response.delay, execute: work)
    }
    override func stopLoading() { work?.cancel() }
}
