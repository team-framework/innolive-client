import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastModeTests: XCTestCase {
    private var api: YouTubeAPI!
    private var integration: YouTubeIntegration!
    private var suite: String!
    private var orientationLock: BroadcastModeOrientationLock!
    private var token: String { "h." + Data("{\"sub\":\"mode-user\"}".utf8).base64EncodedString() + ".s" }

    override func setUp() {
        suite = "broadcast-mode-\(UUID())"
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [BroadcastModeURLProtocol.self]
        api = YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        orientationLock = BroadcastModeOrientationLock()
        integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: suite)!), api: api, orientationLock: orientationLock, consentStore: ConsentAcknowledgementStore(userDefaults: UserDefaults(suiteName: suite)!), pollingInterval: .seconds(60))
        BroadcastModeURLProtocol.requests = []
        BroadcastModeURLProtocol.responses = []
    }

    override func tearDown() {
        integration.reset()
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        BroadcastModeURLProtocol.requests = []
        BroadcastModeURLProtocol.responses = []
    }

    private func snapshot(status: String? = nil, failures: Bool = false, liveProvider: String = "youtube") -> String {
        let stream = "{\"status\":\"streaming\",\"broadcast_phase\":\"live\",\"publisher_active\":true,\"reconnect_attempts\":0}"
        let failure = failures ? ",\"failed_targets\":[{\"provider\":\"chzzk\",\"code\":\"streaming_rate_limited\"}]" : ""
        let switching = status.map { ",\"resolution_switch\":{\"status\":\"\($0)\",\"resolution\":\"fhd\",\"targets\":[\"youtube\"],\"started_at\":\"2026-10-02T00:00:00Z\"\(failure)}" } ?? ""
        return "{\"session_id\":\"session\",\"owner_token\":\"test-owner\",\"broadcast_resolution\":\"fhd\",\"stream\":\(stream),\"targets\":[{\"provider\":\"\(liveProvider)\",\"stream\":\(stream)}],\"media\":{}\(switching)}"
    }

    private func seed(status: String? = nil, liveProvider: String = "youtube") throws {
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(snapshot(status: status, liveProvider: liveProvider).utf8))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "channel", title: "YT")), session: session,
            videoTrack: .init(id: "video", kind: "video", readyState: "live"))
        integration.settingsEditor.editYouTube { $0.title = "broadcast"; $0.audience = .notMadeForKids }
        if status != nil { apply(snapshot(status: status)) }
    }

    private func apply(_ body: String) {
        let response = try! JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(body.utf8))
        integration.applyLiveEditingSnapshotForTesting(response)
    }

    private func enqueue(_ body: String, status: Int = 200, delay: TimeInterval = 0) {
        BroadcastModeURLProtocol.responses.append(.init(body: Data(body.utf8), status: status, delay: delay))
    }

    private func waitForRequest() async {
        for _ in 0..<100 {
            if !BroadcastModeURLProtocol.requests.isEmpty { return }
            try? await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("Request did not start")
    }

    func testModeAPIUsesSortedTargetsAndOwnerAuthentication() async throws {
        try seed()
        enqueue(snapshot(status: "switching"), status: 202)
        _ = try await api.changeBroadcastMode(session: integration.session!, accessToken: token, resolution: "720p", targets: [.youtube, .chzzk])
        let request = try XCTUnwrap(BroadcastModeURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.path, "/sessions/session/broadcast-mode")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "test-owner")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + token)
        let payload = try payload(request)
        XCTAssertEqual(Set(payload.keys), ["resolution", "targets"])
        XCTAssertEqual(payload["resolution"] as? String, "720p")
        XCTAssertEqual(payload["targets"] as? [String], ["chzzk", "youtube"])
    }

    func testPendingModeRequestImmediatelyBlocksLiveEditing() async throws {
        try seed()
        integration.beginLiveEditing(.youtube, accessToken: token)
        enqueue(snapshot(status: "switching"), status: 202, delay: 0.1)
        let task = Task { await integration.changeBroadcastMode(resolution: "fhd", targets: [.youtube], accessToken: token) }
        await waitForRequest()
        XCTAssertTrue(integration.isChangingBroadcastMode)
        XCTAssertFalse(integration.canEditLiveBroadcast(.youtube))
        let saved = await integration.saveLiveSettings(accessToken: token)
        XCTAssertFalse(saved)
        let accepted = await task.value
        XCTAssertTrue(accepted)
        XCTAssertTrue(integration.isChangingBroadcastMode)
        XCTAssertEqual(BroadcastModeURLProtocol.requests.count, 1)
        apply(snapshot(status: "done"))
        XCTAssertFalse(integration.isChangingBroadcastMode)
        XCTAssertTrue(integration.canEditLiveBroadcast(.youtube))
    }

    func testLiveSaveBlocksModeRequest() async throws {
        try seed()
        integration.beginLiveEditing(.youtube, accessToken: token)
        enqueue(snapshot(), delay: 0.1)
        let task = Task { await integration.saveLiveSettings(accessToken: token) }
        await waitForRequest()
        let changed = await integration.changeBroadcastMode(resolution: "720p", targets: [.youtube], accessToken: token)
        XCTAssertFalse(changed)
        XCTAssertFalse(integration.canChangeBroadcastMode)
        let saved = await task.value
        XCTAssertTrue(saved)
        XCTAssertEqual(BroadcastModeURLProtocol.requests.count, 1)
    }

    func testServerSwitchingAfterRestoreKeepsEditingAndModeLocked() throws {
        try seed(status: "switching")
        XCTAssertTrue(integration.isChangingBroadcastMode)
        XCTAssertFalse(integration.canChangeBroadcastMode)
        XCTAssertFalse(integration.canEditLiveBroadcast(.youtube))
    }

    func testResetRejectsLateAcceptedResponse() async throws {
        try seed()
        enqueue(snapshot(status: "switching"), status: 202, delay: 0.1)
        let task = Task { await integration.changeBroadcastMode(resolution: "720p", targets: [.youtube], accessToken: token) }
        await waitForRequest()
        integration.reset()
        let accepted = await task.value
        XCTAssertFalse(accepted)
        XCTAssertNil(integration.session)
        XCTAssertFalse(integration.isChangingBroadcastMode)
    }

    func testPartialFailurePreservesServerResolutionAndUnlocks() throws {
        try seed(status: "switching")
        apply(snapshot(status: "done", failures: true))
        XCTAssertFalse(integration.isChangingBroadcastMode)
        XCTAssertEqual(integration.broadcastResolution, "fhd")
        XCTAssertEqual(integration.broadcastModeFailures.first?.provider, "chzzk")
        XCTAssertNotNil(integration.broadcastModeError)
        XCTAssertTrue(integration.canEditLiveBroadcast(.youtube))
    }

    func testAddedYouTubeRequiresTransmissionConsentBeforeSaving() async throws {
        try seed(liveProvider: "chzzk")
        let saved = await integration.saveBroadcastModeSettings(provider: .youtube, accessToken: token)
        let changed = await integration.changeBroadcastMode(resolution: "fhd", targets: [.youtube, .chzzk], accessToken: token)
        XCTAssertFalse(saved)
        XCTAssertFalse(changed)
        XCTAssertTrue(BroadcastModeURLProtocol.requests.isEmpty)
    }

    func testInvalidModeNeverSendsRequestAndLiveCameraChangeIsBlocked() async throws {
        try seed()
        let invalid = await integration.changeBroadcastMode(resolution: "hd", targets: [.youtube], accessToken: token)
        let empty = await integration.changeBroadcastMode(resolution: "720p", targets: [], accessToken: token)
        let camera = await integration.switchVideoQuality(to: .hd30)
        XCTAssertFalse(invalid)
        XCTAssertFalse(empty)
        XCTAssertFalse(camera)
        XCTAssertTrue(BroadcastModeURLProtocol.requests.isEmpty)
    }

    func testAddedSettingsFailureNeverSendsModeRequest() async throws {
        try seed(liveProvider: "chzzk")
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        XCTAssertTrue(integration.acknowledgeYouTubeTransmission(consent))
        enqueue("{\"error\":{\"code\":\"broadcast_not_live\"}}", status: 409)
        enqueue(snapshot(liveProvider: "chzzk"))
        let changed = await integration.changeBroadcastMode(resolution: "fhd", targets: [.youtube, .chzzk], accessToken: token)
        XCTAssertFalse(changed)
        XCTAssertFalse(BroadcastModeURLProtocol.requests.contains { $0.url?.path.hasSuffix("broadcast-mode") == true })
        XCTAssertEqual(BroadcastModeURLProtocol.requests.first?.url?.path, "/sessions/session/broadcast")
    }

    func testAddedSettingsAreSavedBeforeModeRequest() async throws {
        try seed(liveProvider: "chzzk")
        var consent = SignupConsent()
        consent.observeScroll(contentHeight: 200, visibleHeight: 400, offset: 0)
        consent.accept()
        XCTAssertTrue(integration.acknowledgeYouTubeTransmission(consent))
        enqueue(snapshot(liveProvider: "chzzk"))
        enqueue(snapshot(status: "switching"), status: 202)
        let changed = await integration.changeBroadcastMode(resolution: "fhd", targets: [.youtube, .chzzk], accessToken: token)
        XCTAssertTrue(changed)
        XCTAssertEqual(BroadcastModeURLProtocol.requests.map { $0.url!.path }, ["/sessions/session/broadcast", "/sessions/session/broadcast-mode"])
    }

    func testLostModeAndStatusResponsesStayLockedUntilFreshStatus() async throws {
        try seed()
        enqueue("{\"error\":{\"code\":\"internal_error\"}}", status: 500)
        enqueue("{\"error\":{\"code\":\"internal_error\"}}", status: 500)
        let changed = await integration.changeBroadcastMode(resolution: "720p", targets: [.youtube], accessToken: token)
        XCTAssertFalse(changed)
        XCTAssertTrue(integration.isChangingBroadcastMode)
        XCTAssertFalse(integration.canEditLiveBroadcast(.youtube))
        apply(snapshot(status: "done"))
        XCTAssertFalse(integration.isChangingBroadcastMode)
        XCTAssertTrue(integration.canEditLiveBroadcast(.youtube))
    }

    func testSwitchingGapPreservesBroadcastOrientationAndTimer() async throws {
        let prepared = snapshot().replacingOccurrences(of: "\"broadcast_phase\":\"live\"", with: "\"broadcast_phase\":\"prepared\"")
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(prepared.utf8))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "channel", title: "YT")), session: session,
            videoTrack: .init(id: "video", kind: "video", readyState: "live"))
        enqueue(snapshot())
        await integration.goLiveYouTubeStream(accessToken: token)
        XCTAssertTrue(orientationLock.isLocked)
        let startedAt = try XCTUnwrap(integration.streamStartedAt)
        let gap = snapshot(status: "switching").replacingOccurrences(of: "\"targets\":[{\"provider\":\"youtube\",\"stream\":{\"status\":\"streaming\",\"broadcast_phase\":\"live\",\"publisher_active\":true,\"reconnect_attempts\":0}}]", with: "\"targets\":[]")
        apply(gap)
        XCTAssertTrue(integration.isChangingBroadcastMode)
        XCTAssertTrue(integration.visibleBroadcastTargets.isEmpty)
        XCTAssertTrue(orientationLock.isLocked)
        XCTAssertEqual(integration.streamStartedAt, startedAt)
    }

    func testStopRemainsAvailableDuringServerSwitch() async throws {
        try seed(status: "switching")
        enqueue(snapshot(status: "canceled"))
        await integration.stopYouTubeStream(accessToken: token)
        XCTAssertEqual(BroadcastModeURLProtocol.requests.first?.url?.path, "/sessions/session/stream/stop")
        XCTAssertFalse(integration.isChangingBroadcastMode)
    }

    private func payload(_ request: URLRequest) throws -> [String: Any] {
        if let body = request.httpBody { return try JSONSerialization.jsonObject(with: body) as! [String: Any] }
        let input = try XCTUnwrap(request.httpBodyStream)
        input.open()
        defer { input.close() }
        var data = Data(), buffer = [UInt8](repeating: 0, count: 2048)
        while input.hasBytesAvailable {
            let length = input.read(&buffer, maxLength: buffer.count)
            if length <= 0 { break }
            data.append(buffer, count: length)
        }
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }
}

private final class BroadcastModeURLProtocol: URLProtocol, @unchecked Sendable {
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

@MainActor
private final class BroadcastModeOrientationLock: BroadcastOrientationLocking {
    nonisolated deinit {}
    private(set) var lockedOrientation: BroadcastInterfaceOrientation?
    var isLocked: Bool { lockedOrientation != nil }
    private var generation: UInt = 0
    func lockToCurrentInterfaceOrientation() -> UInt {
        generation &+= 1
        lockedOrientation = .portrait
        return generation
    }
    func unlock(generation expected: UInt) {
        guard generation == expected else { return }
        generation &+= 1
        lockedOrientation = nil
    }
}
