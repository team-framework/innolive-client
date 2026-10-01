import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastSettingsTests: XCTestCase {
    private var suite: String!
    private var preferences: YouTubePreferencesStore!
    private var api: YouTubeAPI!
    private var editor: BroadcastSettingsEditor!
    private let snapshot = """
    {"stream":{"status":"idle","publisher_active":false,"reconnect_attempts":0,"broadcast_phase":"idle"},"media":{}}
    """
    override func setUp() {
        suite = "broadcast-settings-tests-\(UUID())"
        preferences = YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: suite)!)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [SettingsURLProtocol.self]
        api = YouTubeAPI(urlSession: URLSession(configuration: config), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        editor = BroadcastSettingsEditor(api: api, preferences: preferences)
        SettingsURLProtocol.responses = []
        SettingsURLProtocol.requests = []
        editor.configure(scope: scope("A"), channels: ["youtube": "yt-a", "chzzk": "cz-a"])
    }
    override func tearDown() {
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        SettingsURLProtocol.responses = []
        SettingsURLProtocol.requests = []
    }
    private func scope(_ user: String) -> BroadcastSessionScope {
        let payload = Data("{\"sub\":\"\(user)\"}".utf8).base64EncodedString()
        return try! BroadcastSessionScope(server: URL(string: "https://example.invalid")!, accessToken: "h.\(payload).s")
    }
    private func session() throws -> YouTubeBroadcastSession {
        try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data("""
        {"session_id":"session","owner_token":"owner","stream":{"status":"idle","publisher_active":false,"reconnect_attempts":0}}
        """.utf8))
    }
    private func enqueue(_ body: String, status: Int = 200, delay: TimeInterval = 0) {
        SettingsURLProtocol.responses.append(.init(status: status, body: Data(body.utf8), delay: delay))
    }
    private func body(_ request: URLRequest) throws -> [String: Any] {
        if let data = request.httpBody { return try JSONSerialization.jsonObject(with: data) as! [String: Any] }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open(); defer { stream.close() }
        var data = Data(); var buffer = [UInt8](repeating: 0, count: 2048)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }; data.append(buffer, count: n)
        }
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }
    func testCHZZKNoCategoryPayloadAndProvider() async throws {
        enqueue(snapshot)
        _ = try await api.saveCHZZKBroadcastSettings(session: try session(), accessToken: "access", settings: CHZZKBroadcastSettings(title: "방송", tags: ["개발"]))
        let request = try XCTUnwrap(SettingsURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.query, "provider=chzzk")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "owner")
        let data = try body(request)
        XCTAssertEqual(data["category_type"] as? String, "")
        XCTAssertEqual(data["category_id"] as? String, "")
        XCTAssertNil(data["privacy"])
        XCTAssertEqual(data["tags"] as? [String], ["개발"])
    }
    func testYouTubeCategoryPayloadAndDefaultsOwnerHeader() async throws {
        enqueue(snapshot)
        var settings = YouTubeBroadcastSettings.defaultValue
        settings.categoryID = "20"
        _ = try await api.saveBroadcastSettings(session: try session(), accessToken: "access", settings: settings)
        let request = try XCTUnwrap(SettingsURLProtocol.requests.first)
        XCTAssertEqual(request.url?.query, "provider=youtube")
        XCTAssertEqual(try body(request)["category_id"] as? String, "20")
        enqueue("{\"title\":\"last\",\"made_for_kids\":false,\"category_id\":\"20\"}")
        _ = try await api.broadcastDefaults(session: try session(), accessToken: "access", provider: .youtube)
        XCTAssertEqual(SettingsURLProtocol.requests.last?.url?.path, "/sessions/session/broadcast/defaults")
        XCTAssertEqual(SettingsURLProtocol.requests.last?.value(forHTTPHeaderField: "X-Session-Owner-Token"), "owner")
    }
    func testDraftsAndAccountChannelServerIsolation() {
        editor.editYouTube { $0.title = "YT A" }
        editor.editCHZZK { $0.title = "CZ A" }
        editor.persist(provider: .youtube); editor.persist(provider: .chzzk)
        editor.select(.chzzk)
        XCTAssertEqual(editor.youtube.title, "YT A")
        editor.configure(scope: scope("B"), channels: ["youtube": "yt-a", "chzzk": "cz-a"])
        XCTAssertNotEqual(editor.youtube.title, "YT A")
        XCTAssertNotEqual(editor.chzzk.title, "CZ A")
        editor.configure(scope: scope("A"), channels: ["youtube": "yt-new", "chzzk": "cz-a"])
        XCTAssertNotEqual(editor.youtube.title, "YT A")
        XCTAssertEqual(editor.chzzk.title, "CZ A")
        editor.configure(scope: scope("A"), channels: ["youtube": "yt-a", "chzzk": "cz-a"])
        XCTAssertEqual(editor.youtube.title, "YT A")
        XCTAssertEqual(editor.chzzk.title, "CZ A")
    }
    func testDefaultsOffRestoresBaselineAndDoesNotRemoveTypedValues() async throws {
        let original = editor.youtube.title
        enqueue("{\"title\":\"last\",\"category_id\":\"20\"}")
        await editor.loadDefaults(session: try session(), accessToken: "access", provider: .youtube)
        XCTAssertEqual(editor.youtube.title, "last")
        editor.setUsesDefaults(false, provider: .youtube)
        XCTAssertEqual(editor.youtube.title, original)
        XCTAssertNil(editor.youtube.categoryID)
        editor.editYouTube { $0.title = "typed" }
        editor.setUsesDefaults(false, provider: .youtube)
        XCTAssertEqual(editor.youtube.title, "typed")
    }
    func testLateDefaultsNeverOverwriteManualInput() async throws {
        enqueue("{\"title\":\"last\"}", delay: 0.1)
        let session = try session()
        let task = Task { await editor.loadDefaults(session: session, accessToken: "access", provider: .youtube) }
        await waitForRequest()
        editor.editYouTube { $0.title = "typed" }
        await task.value
        XCTAssertEqual(editor.youtube.title, "typed")
    }
    func testLateDefaultsIgnoredAfterDisableAndAccountSwitch() async throws {
        for switchAccount in [false, true] {
            SettingsURLProtocol.requests = []
            enqueue("{\"title\":\"old account\"}", delay: 0.1)
            let session = try session()
            let task = Task { await editor.loadDefaults(session: session, accessToken: "access", provider: .youtube) }
            await waitForRequest()
            if switchAccount { editor.configure(scope: scope("B"), channels: [:]) }
            else { editor.setUsesDefaults(false, provider: .youtube) }
            await task.value
            XCTAssertNotEqual(editor.youtube.title, "old account")
            editor.setUsesDefaults(true, provider: .youtube)
        }
    }
    func testFailedOrMissingDefaultsAllowInputAndCHZZKIgnoresYouTubeFallback() async throws {
        enqueue("{}", status: 503)
        await editor.loadDefaults(session: try session(), accessToken: "access", provider: .youtube)
        XCTAssertNotNil(editor.defaultsMessages[.youtube])
        editor.editYouTube { $0.title = "typed" }
        XCTAssertEqual(editor.youtube.title, "typed")
        enqueue("{\"category_id\":\"22\"}")
        await editor.loadDefaults(session: try session(), accessToken: "access", provider: .chzzk)
        XCTAssertEqual(editor.chzzk.categoryID, "")
        XCTAssertEqual(editor.chzzk.categoryType, "")
    }
    func testCHZZKCategorySearchEncodesQueryAndDistinguishesEmptyFailure() async throws {
        editor.select(.chzzk)
        enqueue("{\"categories\":[]}")
        await editor.searchCategories(query: "게임 & 스포츠", accessToken: "access")
        let url = try XCTUnwrap(SettingsURLProtocol.requests.last?.url)
        XCTAssertEqual(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "게임 & 스포츠")
        let emptyMessage = editor.categoryMessage
        enqueue("{}", status: 502)
        await editor.searchCategories(query: "게임", accessToken: "access")
        XCTAssertNotEqual(editor.categoryMessage, emptyMessage)
        enqueue("{\"categories\":[{\"category_type\":\"GAME\",\"category_id\":\"game1\",\"category_value\":\"게임\"}]}")
        await editor.searchCategories(query: "게임", accessToken: "access")
        XCTAssertEqual(editor.chzzkCategories.first?.categoryType, "GAME")
        XCTAssertEqual(editor.chzzkCategories.first?.categoryID, "game1")
    }
    func testLateSearchIgnoredAfterPlatformChange() async {
        editor.select(.chzzk)
        enqueue("{\"categories\":[{\"category_type\":\"GAME\",\"category_id\":\"old\",\"category_value\":\"old\"}]}", delay: 0.1)
        let task = Task { await editor.searchCategories(query: "old", accessToken: "access") }
        await waitForRequest()
        editor.select(.youtube)
        await task.value
        XCTAssertTrue(editor.chzzkCategories.isEmpty)
        XCTAssertNil(editor.categoryMessage)
    }
    func testPlatformValidationAndLegacyCategoryDecoding() throws {
        var chzzk = CHZZKBroadcastSettings()
        chzzk.categoryID = "one"
        XCTAssertNotNil(chzzk.validation["category_id"])
        chzzk.categoryType = "GAME"; chzzk.tags = ["has space"]
        XCTAssertNotNil(chzzk.validation["tags"])
        chzzk.tags = ["개발123"]
        XCTAssertTrue(chzzk.validation.isEmpty)
        let legacy = try JSONDecoder().decode(YouTubeBroadcastSettings.self, from: Data("{\"title\":\"legacy\",\"description\":\"\",\"privacy\":\"private\"}".utf8))
        XCTAssertNil(legacy.categoryID)
    }
    func testSaveFailureDoesNotPrepareAndShowsFieldError() async throws {
        let integration = YouTubeIntegration(preferencesStore: preferences, api: api)
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "yt", title: "YT")), session: try session(), videoTrack: .init(id: "v", kind: "video", readyState: "live"))
        integration.settingsEditor.configure(scope: scope("A"), channels: ["youtube": "yt"])
        integration.settingsEditor.editYouTube { $0.audience = .notMadeForKids }
        enqueue("{\"error\":{\"code\":\"bad_request\",\"details\":{\"field\":\"title\",\"reason\":\"invalid\"}}}", status: 400)
        await integration.prepareYouTubeStream(accessToken: "access", useEditor: true)
        XCTAssertEqual(SettingsURLProtocol.requests.count, 1)
        XCTAssertTrue(SettingsURLProtocol.requests.allSatisfy { $0.url?.path.hasSuffix("/broadcast") == true })
        XCTAssertNotNil(integration.settingsEditor.fieldErrors["title"])
    }
    func testSaveThenPrepareNeverGoesLive() async throws {
        let integration = YouTubeIntegration(preferencesStore: preferences, api: api)
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "yt", title: "YT")), session: try session(), videoTrack: .init(id: "v", kind: "video", readyState: "live"))
        integration.settingsEditor.editYouTube { $0.audience = .notMadeForKids }
        enqueue(snapshot); enqueue(snapshot)
        await integration.prepareYouTubeStream(accessToken: "access", useEditor: true)
        XCTAssertEqual(SettingsURLProtocol.requests.map { $0.url!.path }, ["/sessions/session/broadcast", "/sessions/session/stream/prepare"])
        XCTAssertEqual(try body(SettingsURLProtocol.requests[1])["provider"] as? String, "youtube")
    }
    func testCHZZKSaveThenPrepareWithConnectedAccount() async throws {
        let integration = YouTubeIntegration(preferencesStore: preferences, api: api)
        enqueue("[{\"provider\":\"chzzk\",\"channel_id\":\"cz\",\"channel_title\":\"CZ\",\"reconnect_required\":false}]")
        await integration.refreshConnection(accessToken: "access")
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "yt", title: "YT")), session: try session(), videoTrack: .init(id: "v", kind: "video", readyState: "live"))
        integration.settingsEditor.editCHZZK { $0.title = "CZ live" }
        SettingsURLProtocol.requests = []
        enqueue(snapshot); enqueue(snapshot)
        await integration.prepareYouTubeStream(accessToken: "access", provider: .chzzk, useEditor: true)
        XCTAssertEqual(SettingsURLProtocol.requests.count, 2)
        XCTAssertEqual(SettingsURLProtocol.requests[0].url?.query, "provider=chzzk")
        XCTAssertEqual(try body(SettingsURLProtocol.requests[1])["provider"] as? String, "chzzk")
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "yt", title: "YT")), session: try session(), videoTrack: .init(id: "v", kind: "video", readyState: "live"))
        SettingsURLProtocol.requests = []
        enqueue("{\"error\":{\"code\":\"streaming_prepare_failed\"}}", status: 502)
        await integration.prepareYouTubeStream(accessToken: "access", provider: .chzzk, useEditor: true)
        XCTAssertEqual(SettingsURLProtocol.requests.count, 1)
        XCTAssertTrue(integration.errorMessage?.contains("치지직") == true)
        XCTAssertFalse(integration.errorMessage?.contains("YouTube") == true)
    }
    func testAccountChangeDuringSaveDoesNotPrepare() async throws {
        let integration = YouTubeIntegration(preferencesStore: preferences, api: api)
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "yt", title: "YT")), session: try session(), videoTrack: .init(id: "v", kind: "video", readyState: "live"))
        integration.settingsEditor.configure(scope: scope("A"), channels: [:])
        integration.settingsEditor.editYouTube { $0.audience = .notMadeForKids }
        enqueue(snapshot, delay: 0.1)
        let task = Task { await integration.prepareYouTubeStream(accessToken: "access", useEditor: true) }
        await waitForRequest()
        integration.settingsEditor.configure(scope: scope("B"), channels: [:])
        await task.value
        XCTAssertEqual(SettingsURLProtocol.requests.count, 1)
    }
    func testEmptySearchInvalidatesPendingResponse() async {
        editor.select(.chzzk)
        enqueue("{\"categories\":[]}", delay: 0.1)
        let task = Task { await editor.searchCategories(query: "old", accessToken: "access") }
        await waitForRequest()
        await editor.searchCategories(query: "", accessToken: "access")
        await task.value
        XCTAssertFalse(editor.isSearching)
        XCTAssertEqual(editor.categoryMessage, String(localized: "검색어를 입력해 주세요."))
    }

    private func waitForRequest() async {
        for _ in 0..<100 {
            if !SettingsURLProtocol.requests.isEmpty { return }
            try? await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("request did not start")
    }
}

private final class SettingsURLProtocol: URLProtocol {
    struct Response { let status: Int; let body: Data; let delay: TimeInterval }
    nonisolated(unsafe) static var responses: [Response] = []
    nonisolated(unsafe) static var requests: [URLRequest] = []
    private var work: DispatchWorkItem?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let response = Self.responses.isEmpty ? Response(status: 500, body: Data(), delay: 0) : Self.responses.removeFirst()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.client?.urlProtocol(self, didReceive: HTTPURLResponse(url: self.request.url!, statusCode: response.status, httpVersion: nil, headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: response.body)
            self.client?.urlProtocolDidFinishLoading(self)
        }
        self.work = work
        DispatchQueue.main.asyncAfter(deadline: .now() + response.delay, execute: work)
    }
    override func stopLoading() { work?.cancel() }
}
