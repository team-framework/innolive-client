import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class YouTubeBroadcastOrientationLifecycleTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!
    private var locker: FakeBroadcastOrientationLock!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.youtube-orientation.\(UUID().uuidString)"
        userDefaults = UserDefaults(suiteName: suiteName)
        locker = FakeBroadcastOrientationLock()
        YouTubeBroadcastOrientationURLProtocol.responses = []
        YouTubeBroadcastOrientationURLProtocol.requests = []
        YouTubeBroadcastOrientationURLProtocol.onRequest = nil
    }

    override func tearDown() {
        YouTubeBroadcastOrientationURLProtocol.responses = []
        YouTubeBroadcastOrientationURLProtocol.requests = []
        YouTubeBroadcastOrientationURLProtocol.onRequest = nil
        if let suiteName {
            userDefaults.removePersistentDomain(forName: suiteName)
        }
        userDefaults = nil
        suiteName = nil
        locker = nil
        super.tearDown()
    }

    func testPrepareAndPreparedWaitingDoNotLock() async throws {
        let integration = try makePreparedIntegration(phase: "idle")
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: sessionSnapshot(phase: "preparing", status: "idle")),
            .init(statusCode: 200, data: sessionSnapshot(phase: "prepared", status: "idle")),
        ]

        await integration.prepareYouTubeStream(accessToken: "access-token")

        XCTAssertEqual(integration.broadcastPhase, "prepared")
        XCTAssertFalse(locker.isLocked)
        XCTAssertEqual(locker.lockCallCount, 0)
    }

    func testGoLiveLocksCurrentOrientationBeforeNetwork() async throws {
        locker.current = .landscapeLeft
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")

        XCTAssertEqual(
            YouTubeBroadcastOrientationURLProtocol.requests.filter {
                $0.url?.path.hasSuffix("/stream/golive") == true
            }.count,
            1
        )
        XCTAssertTrue(
            YouTubeBroadcastOrientationURLProtocol.requests.first?.url?.path.contains("golive") == true
        )
        XCTAssertEqual(integration.broadcastPhase, "live")
        XCTAssertTrue(locker.isLocked)
        XCTAssertEqual(locker.lockCallCount, 1)
        XCTAssertEqual(locker.lockedOrientation, .landscapeLeft)
        XCTAssertEqual(integration.videoUplink.lockedBroadcastOrientation, .landscapeLeft)
    }

    func testGoLiveRetryKeepsOriginalLock() async throws {
        locker.current = .landscapeRight
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(
                statusCode: 409,
                data: Data("{\"error\":{\"code\":\"broadcast_not_ready\",\"message\":\"not ready\"}}".utf8)
            ),
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
        ]

        let task = Task { @MainActor in
            await integration.goLiveYouTubeStream(accessToken: "access-token")
        }
        try await Task.sleep(for: .milliseconds(50))
        locker.current = .portrait
        await task.value

        XCTAssertEqual(integration.broadcastPhase, "live")
        XCTAssertEqual(locker.lockCallCount, 1)
        XCTAssertEqual(locker.lockedOrientation, .landscapeRight)
    }

    func testGoLiveFailureReleasesLockBackToPrepared() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 503, data: Data("{\"error\":{\"message\":\"unavailable\"}}".utf8)),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")

        XCTAssertEqual(integration.broadcastPhase, "prepared")
        XCTAssertFalse(locker.isLocked)
        XCTAssertEqual(locker.unlockCallCount, 1)
        XCTAssertNil(integration.videoUplink.lockedBroadcastOrientation)
    }

    func testSuccessfulStopReleasesLock() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
            .init(statusCode: 200, data: streamState(phase: "idle", status: "stopped")),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")
        XCTAssertTrue(locker.isLocked)
        await integration.stopYouTubeStream(accessToken: "access-token")

        XCTAssertFalse(locker.isLocked)
        XCTAssertNil(integration.videoUplink.lockedBroadcastOrientation)
    }

    func testFailedStopKeepsLock() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
            .init(statusCode: 503, data: Data("{\"error\":{\"message\":\"unavailable\"}}".utf8)),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")
        await integration.stopYouTubeStream(accessToken: "access-token")

        XCTAssertEqual(integration.broadcastPhase, "live")
        XCTAssertTrue(locker.isLocked)
        XCTAssertEqual(integration.videoUplink.lockedBroadcastOrientation, .portrait)
    }

    func testResetClearsLockAndIgnoresStaleGoLive() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(
                statusCode: 200,
                data: streamState(phase: "live", status: "streaming"),
                delay: .milliseconds(200)
            ),
        ]
        let requestStarted = expectation(description: "golive started")
        YouTubeBroadcastOrientationURLProtocol.onRequest = {
            requestStarted.fulfill()
        }

        let goLiveTask = Task { @MainActor in
            await integration.goLiveYouTubeStream(accessToken: "access-token")
        }
        await fulfillment(of: [requestStarted], timeout: 1)
        XCTAssertTrue(locker.isLocked)
        XCTAssertEqual(integration.broadcastPhase, "prepared")
        integration.reset()
        await goLiveTask.value

        XCTAssertFalse(locker.isLocked)
        XCTAssertNil(integration.session)
        XCTAssertNotEqual(integration.broadcastPhase, "live")
        XCTAssertNil(integration.videoUplink.lockedBroadcastOrientation)
    }

    func testEndBroadcastDuringGoLiveStopsFurtherRetries() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(
                statusCode: 409,
                data: Data("{\"error\":{\"code\":\"broadcast_not_ready\",\"message\":\"not ready\"}}".utf8),
                delay: .milliseconds(250)
            ),
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
        ]
        let requestStarted = expectation(description: "golive started")
        YouTubeBroadcastOrientationURLProtocol.onRequest = {
            requestStarted.fulfill()
        }

        let goLiveTask = Task { @MainActor in
            await integration.goLiveYouTubeStream(accessToken: "access-token")
        }
        await fulfillment(of: [requestStarted], timeout: 1)
        XCTAssertTrue(locker.isLocked)
        XCTAssertEqual(integration.broadcastPhase, "prepared")
        await integration.endBroadcast(accessToken: "access-token")
        await goLiveTask.value

        XCTAssertFalse(locker.isLocked)
        XCTAssertNil(integration.session)
        XCTAssertNotEqual(integration.broadcastPhase, "live")
        XCTAssertNil(integration.videoUplink.lockedBroadcastOrientation)
        XCTAssertEqual(
            YouTubeBroadcastOrientationURLProtocol.requests.filter {
                $0.url?.path.hasSuffix("/stream/golive") == true
            }.count,
            1
        )
        XCTAssertEqual(
            YouTubeBroadcastOrientationURLProtocol.requests.filter {
                $0.url?.path.hasSuffix("/stream/stop") == true
            }.count,
            1
        )
    }

    func testRecoverFromUplinkFailureDuringGoLiveDoesNotRestoreLive() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(
                statusCode: 200,
                data: streamState(phase: "live", status: "streaming"),
                delay: .milliseconds(250)
            ),
        ]
        let requestStarted = expectation(description: "golive started")
        YouTubeBroadcastOrientationURLProtocol.onRequest = {
            requestStarted.fulfill()
        }

        let goLiveTask = Task { @MainActor in
            await integration.goLiveYouTubeStream(accessToken: "access-token")
        }
        await fulfillment(of: [requestStarted], timeout: 1)
        XCTAssertTrue(locker.isLocked)
        integration.videoUplink.fail("uplink failed")
        await integration.recoverFromVideoUplinkFailure(accessToken: "access-token")
        await goLiveTask.value

        XCTAssertFalse(locker.isLocked)
        XCTAssertNil(integration.session)
        XCTAssertNotEqual(integration.broadcastPhase, "live")
        XCTAssertNil(integration.videoUplink.lockedBroadcastOrientation)
    }

    func testUnownedIntegrationDoesNotReleaseSharedLock() async throws {
        let sharedLocker = FakeBroadcastOrientationLock()
        sharedLocker.current = .landscapeLeft
        let owner = try makePreparedIntegration(locker: sharedLocker)
        let other = try makePreparedIntegration(locker: sharedLocker)
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
        ]

        await owner.goLiveYouTubeStream(accessToken: "access-token")
        XCTAssertTrue(sharedLocker.isLocked)
        XCTAssertEqual(sharedLocker.lockCallCount, 1)
        XCTAssertEqual(owner.videoUplink.lockedBroadcastOrientation, .landscapeLeft)

        other.reset()
        XCTAssertTrue(sharedLocker.isLocked)
        XCTAssertEqual(sharedLocker.unlockCallCount, 0)
        XCTAssertEqual(sharedLocker.lockedOrientation, .landscapeLeft)
        XCTAssertEqual(owner.videoUplink.lockedBroadcastOrientation, .landscapeLeft)
        XCTAssertNil(other.videoUplink.lockedBroadcastOrientation)
    }

    func testGoLiveFailureOfUnownedIntegrationLeavesOwnerLock() async throws {
        let sharedLocker = FakeBroadcastOrientationLock()
        sharedLocker.current = .portrait
        let owner = try makePreparedIntegration(locker: sharedLocker)
        let other = try makePreparedIntegration(locker: sharedLocker)
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
            .init(statusCode: 503, data: Data("{\"error\":{\"message\":\"unavailable\"}}".utf8)),
        ]

        await owner.goLiveYouTubeStream(accessToken: "access-token")
        await other.goLiveYouTubeStream(accessToken: "access-token")

        XCTAssertTrue(sharedLocker.isLocked)
        XCTAssertEqual(sharedLocker.unlockCallCount, 0)
        XCTAssertEqual(owner.videoUplink.lockedBroadcastOrientation, .portrait)
    }

    func testFinalUplinkFailureClearsLock() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
        ]
        await integration.goLiveYouTubeStream(accessToken: "access-token")
        XCTAssertTrue(locker.isLocked)

        integration.videoUplink.fail("uplink failed")
        await integration.recoverFromVideoUplinkFailure(accessToken: "access-token")

        XCTAssertFalse(locker.isLocked)
        XCTAssertNil(integration.videoUplink.lockedBroadcastOrientation)
    }

    func testPauseKeepsLock() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
            .init(statusCode: 200, data: streamState(phase: "live", status: "paused")),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")
        await integration.pauseYouTubeStream(accessToken: "access-token")

        XCTAssertTrue(integration.isYouTubeBroadcastPaused)
        XCTAssertTrue(locker.isLocked)
        XCTAssertEqual(integration.videoUplink.lockedBroadcastOrientation, .portrait)
    }

    func testReconnectKeepsLock() async throws {
        let integration = try makePreparedIntegration()
        YouTubeBroadcastOrientationURLProtocol.responses = [
            .init(statusCode: 200, data: streamState(phase: "live", status: "streaming")),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")
        integration.videoUplink.isReconnectInProgress = true
        integration.videoUplink.applyBroadcastOrientationLock(
            locker.lockedOrientation ?? .portrait
        )

        XCTAssertTrue(locker.isLocked)
        XCTAssertEqual(integration.videoUplink.lockedBroadcastOrientation, .portrait)
        XCTAssertTrue(integration.videoUplink.isReconnectInProgress)
    }

    private func makePreparedIntegration(
        phase: String = "prepared",
        locker: FakeBroadcastOrientationLock? = nil
    ) throws -> YouTubeIntegration {
        let store = YouTubePreferencesStore(userDefaults: userDefaults)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [YouTubeBroadcastOrientationURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let api = YouTubeAPI(
            urlSession: session,
            serverURLProvider: { path in URL(string: "https://example.invalid\(path)") }
        )
        let integration = YouTubeIntegration(
            preferencesStore: store,
            api: api,
            orientationLock: locker ?? self.locker
        )
        let broadcastSession = try decodeSession(phase: phase)
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

    private func decodeSession(phase: String) throws -> YouTubeBroadcastSession {
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
                "broadcast_phase": "\(phase)"
              }
            }
            """.utf8
        )
        return try JSONDecoder().decode(YouTubeBroadcastSession.self, from: data)
    }

    private func streamState(phase: String, status: String) -> Data {
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
              "broadcast_phase": "\(phase)"
            }
            """.utf8
        )
    }

    private func sessionSnapshot(phase: String, status: String) -> Data {
        Data(
            """
            {
              "stream": {
                "status": "\(status)",
                "started_at": null,
                "stopped_at": null,
                "publisher_active": true,
                "last_error": null,
                "reconnect_attempts": 0,
                "stop_reason": null,
                "paused_at": null,
                "broadcast_phase": "\(phase)"
              },
              "media": {
                "anonymization_enabled": false,
                "raw_video_track": {
                  "id": "track",
                  "kind": "video",
                  "ready_state": "live"
                }
              }
            }
            """.utf8
        )
    }
}

@MainActor
private final class FakeBroadcastOrientationLock: BroadcastOrientationLocking {
    var current: BroadcastInterfaceOrientation = .portrait
    private(set) var lockedOrientation: BroadcastInterfaceOrientation?
    private(set) var lockCallCount = 0
    private(set) var unlockCallCount = 0
    private var generation: UInt = 0

    var isLocked: Bool { lockedOrientation != nil }

    func lockToCurrentInterfaceOrientation() -> UInt {
        if lockedOrientation != nil {
            return generation
        }
        generation &+= 1
        lockCallCount += 1
        lockedOrientation = current
        return generation
    }

    func unlock(generation expected: UInt) {
        guard expected == generation else { return }
        generation &+= 1
        guard lockedOrientation != nil else { return }
        unlockCallCount += 1
        lockedOrientation = nil
    }
}

private final class YouTubeBroadcastOrientationURLProtocol: URLProtocol {
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
