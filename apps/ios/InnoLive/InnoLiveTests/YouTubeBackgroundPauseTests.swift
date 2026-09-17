import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeBackgroundPauseTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!
    private var locker: BackgroundPauseOrientationLock!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.youtube-background-pause.\(UUID().uuidString)"
        userDefaults = UserDefaults(suiteName: suiteName)
        locker = BackgroundPauseOrientationLock()
        YouTubeBackgroundPauseURLProtocol.responses = []
        YouTubeBackgroundPauseURLProtocol.requests = []
        YouTubeBackgroundPauseURLProtocol.onRequest = nil
    }

    override func tearDown() {
        YouTubeBackgroundPauseURLProtocol.responses = []
        YouTubeBackgroundPauseURLProtocol.requests = []
        YouTubeBackgroundPauseURLProtocol.onRequest = nil
        if let suiteName {
            userDefaults.removePersistentDomain(forName: suiteName)
        }
        userDefaults = nil
        suiteName = nil
        locker = nil
        super.tearDown()
    }

    func testBackgroundWhileLivePausesYouTubeStream() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(status: "paused")),
        ]

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/pause"), 1)
        XCTAssertTrue(integration.isYouTubeBroadcastPaused)
        XCTAssertNil(integration.errorMessage)
    }

    func testForegroundAfterBackgroundPauseResumesYouTubeStream() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(status: "paused")),
            .init(statusCode: 200, data: streamState(status: "streaming")),
        ]

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")
        await integration.handleAppBecameActive(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/pause"), 1)
        XCTAssertEqual(requestCount(suffix: "/stream/resume"), 1)
        XCTAssertFalse(integration.isYouTubeBroadcastPaused)
    }

    func testManualPauseIsNotResumedAfterBackground() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(status: "paused")),
        ]

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.pauseYouTubeStream(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")
        await integration.handleAppBecameActive(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/pause"), 1)
        XCTAssertEqual(requestCount(suffix: "/stream/resume"), 0)
        XCTAssertTrue(integration.isYouTubeBroadcastPaused)
    }

    func testBackgroundBeforeGoLiveDoesNotPauseOrShowError() async throws {
        let integration = try makePreparedIntegration()

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")
        await integration.handleAppBecameActive(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/pause"), 0)
        XCTAssertEqual(requestCount(suffix: "/stream/resume"), 0)
        XCTAssertNil(integration.errorMessage)
    }

    func testInitialBackgroundBeforeForegroundDoesNotPauseOrShowError() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 500, data: Data("{\"error\":{\"message\":\"unavailable\"}}".utf8)),
        ]

        await integration.handleAppMovedToBackground(accessToken: "access-token")
        await integration.handleAppBecameActive(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/pause"), 0)
        XCTAssertEqual(requestCount(suffix: "/stream/resume"), 0)
        XCTAssertFalse(integration.isYouTubeBroadcastPaused)
        XCTAssertNil(integration.errorMessage)
    }

    func testBackgroundPauseFailureDoesNotShowError() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 500, data: Data("{\"error\":{\"message\":\"unavailable\"}}".utf8)),
        ]

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/pause"), 1)
        XCTAssertFalse(integration.isYouTubeBroadcastPaused)
        XCTAssertNil(integration.errorMessage)
    }

    func testForegroundDuringBackgroundPauseWaitsThenResumes() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(status: "paused"), delay: .milliseconds(200)),
            .init(statusCode: 200, data: streamState(status: "streaming")),
        ]
        await integration.handleAppBecameActive(accessToken: "access-token")
        let pauseStarted = expectation(description: "pause started")
        YouTubeBackgroundPauseURLProtocol.onRequest = {
            pauseStarted.fulfill()
        }

        let backgroundTask = Task { @MainActor in
            await integration.handleAppMovedToBackground(accessToken: "access-token")
        }
        await fulfillment(of: [pauseStarted], timeout: 1)
        await integration.handleAppBecameActive(accessToken: "access-token")
        await backgroundTask.value

        XCTAssertEqual(requestCount(suffix: "/stream/pause"), 1)
        XCTAssertEqual(requestCount(suffix: "/stream/resume"), 1)
        XCTAssertFalse(integration.isYouTubeBroadcastPaused)
    }

    func testRecoverClearsBackgroundPauseSoForegroundDoesNotResume() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(status: "paused")),
            .init(statusCode: 204, data: Data()),
        ]

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")
        XCTAssertTrue(integration.isYouTubeBroadcastPaused)

        integration.videoUplink.fail("uplink failed")
        await integration.recoverFromVideoUplinkFailure(accessToken: "access-token")
        await integration.handleAppBecameActive(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/resume"), 0)
        XCTAssertNil(integration.session)
    }

    func testResetClearsBackgroundPauseSoForegroundDoesNotResume() async throws {
        let integration = try await makeLiveIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(status: "paused")),
        ]

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")
        integration.reset()
        await integration.handleAppBecameActive(accessToken: "access-token")

        XCTAssertEqual(requestCount(suffix: "/stream/resume"), 0)
    }

    private func makeLiveIntegration() async throws -> YouTubeIntegration {
        let integration = try makePreparedIntegration()
        YouTubeBackgroundPauseURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(status: "streaming")),
        ]
        await integration.goLiveYouTubeStream(accessToken: "access-token")
        YouTubeBackgroundPauseURLProtocol.requests.removeAll {
            $0.url?.path.hasSuffix("/stream/golive") == true
        }
        return integration
    }

    private func makePreparedIntegration() throws -> YouTubeIntegration {
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [YouTubeBackgroundPauseURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let api = YouTubeAPI(
            urlSession: session,
            serverURLProvider: { path in URL(string: "https://example.invalid\(path)") }
        )
        let integration = YouTubeIntegration(
            preferencesStore: store,
            api: api,
            orientationLock: locker
        )
        let broadcastSession = try decodeSession()
        integration.seedPreparedVideoSessionForTesting(
            connection: YouTubeConnection(
                provider: "youtube",
                channel: YouTubeChannel(id: "channel", title: "Channel")
            ),
            session: broadcastSession,
            videoTrack: YouTubeVideoTrackState(id: "track", kind: "video", readyState: "live")
        )
        return integration
    }

    private func decodeSession() throws -> YouTubeBroadcastSession {
        let data = Data(
            """
            {
              "session_id": "session-123",
              "owner_token": "owner-token",
              "stream": {
                "status": "idle",
                "started_at": null,
                "stopped_at": null,
                "publisher_active": true,
                "last_error": null,
                "reconnect_attempts": 0,
                "stop_reason": null,
                "paused_at": null,
                "broadcast_phase": "prepared"
              }
            }
            """.utf8
        )
        return try JSONDecoder().decode(YouTubeBroadcastSession.self, from: data)
    }

    private func streamState(status: String) -> Data {
        Data(
            """
            {
              "status": "\(status)",
              "started_at": "2026-09-13T00:00:00Z",
              "stopped_at": null,
              "publisher_active": true,
              "last_error": null,
              "reconnect_attempts": 0,
              "stop_reason": null,
              "paused_at": null,
              "broadcast_phase": "live"
            }
            """.utf8
        )
    }

    private func requestCount(suffix: String) -> Int {
        YouTubeBackgroundPauseURLProtocol.requests.filter {
            $0.url?.path.hasSuffix(suffix) == true
        }.count
    }
}

@MainActor
private final class BackgroundPauseOrientationLock: BroadcastOrientationLocking {
    var current: BroadcastInterfaceOrientation = .portrait
    private(set) var lockedOrientation: BroadcastInterfaceOrientation?
    private var generation: UInt = 0

    var isLocked: Bool { lockedOrientation != nil }

    func lockToCurrentInterfaceOrientation() -> UInt {
        if lockedOrientation != nil {
            return generation
        }
        generation &+= 1
        lockedOrientation = current
        return generation
    }

    func unlock(generation expected: UInt) {
        guard expected == generation else { return }
        generation &+= 1
        lockedOrientation = nil
    }
}

private final class YouTubeBackgroundPauseURLProtocol: URLProtocol {
    struct Response {
        let statusCode: Int
        let data: Data
        var delay: Duration = .zero
    }

    nonisolated(unsafe) static var responses: [Response] = []
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var onRequest: (() -> Void)?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let onRequest = Self.onRequest
        Self.onRequest = nil
        onRequest?()
        let response = Self.responses.isEmpty
            ? Response(statusCode: 500, data: Data())
            : Self.responses.removeFirst()
        let deliver: @Sendable () -> Void = { [weak self] in
            guard let self,
                  let url = self.request.url,
                  let httpResponse = HTTPURLResponse(
                      url: url,
                      statusCode: response.statusCode,
                      httpVersion: nil,
                      headerFields: ["Content-Type": "application/json"]
                  ) else { return }
            self.client?.urlProtocol(self, didReceive: httpResponse, cacheStoragePolicy: .notAllowed)
            if !response.data.isEmpty {
                self.client?.urlProtocol(self, didLoad: response.data)
            }
            self.client?.urlProtocolDidFinishLoading(self)
        }

        if response.delay == .zero {
            deliver()
        } else {
            DispatchQueue.global().asyncAfter(deadline: .now() + response.delay.timeInterval, execute: deliver)
        }
    }

    override func stopLoading() {}
}

private extension Duration {
    var timeInterval: TimeInterval {
        let components = self.components
        return TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1_000_000_000_000_000_000
    }
}
