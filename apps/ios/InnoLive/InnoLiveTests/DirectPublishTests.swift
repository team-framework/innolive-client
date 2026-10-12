import AVFoundation
import CoreMedia
import HaishinKit
import XCTest

@testable import InnoLive

final class DirectIngestEndpointTests: XCTestCase {
    func testParsesYouTubeRTMPSAndCHZZKRTMPIngestURLs() throws {
        let youtube = try XCTUnwrap(DirectIngestEndpoint(ingestURL: "rtmps://a.rtmp.youtube.com/live2/abcd-efgh"))
        XCTAssertEqual(youtube.connectURL, "rtmps://a.rtmp.youtube.com/live2")
        XCTAssertEqual(youtube.streamName, "abcd-efgh")

        let chzzk = try XCTUnwrap(DirectIngestEndpoint(ingestURL: "rtmp://ingest.example.com:8080/relay/live/key?x=1"))
        XCTAssertEqual(chzzk.connectURL, "rtmp://ingest.example.com:8080/relay/live")
        XCTAssertEqual(chzzk.streamName, "key?x=1")
    }

    func testRejectsNonRTMPOrIncompleteIngestURLs() {
        for value in ["https://a.rtmp.youtube.com/live2/key", "rtmp://host/key", "rtmp://host/live2/", "rtmp:///live2/key", "", "key"] {
            XCTAssertNil(DirectIngestEndpoint(ingestURL: value), value)
        }
    }

    func testStringRepresentationsNeverContainStreamKey() throws {
        let endpoint = try XCTUnwrap(DirectIngestEndpoint(ingestURL: "rtmps://a.rtmp.youtube.com/live2/secret-key"))
        var dumped = ""
        dump(endpoint, to: &dumped)
        let target = DirectIngestTarget(provider: "chzzk", endpoint: endpoint)
        var dumpedTarget = ""
        dump(target, to: &dumpedTarget)
        for text in [String(describing: endpoint), String(reflecting: endpoint), "\(endpoint)", dumped, dumpedTarget] {
            XCTAssertFalse(text.contains("secret-key"), text)
            XCTAssertFalse(text.contains("youtube.com"), text)
        }
    }

    func testDecodesPrepareIngestURLAndGoLiveDirectIngest() throws {
        let prepared = try JSONDecoder().decode(
            YouTubeSessionResponse.self, from: ContractTestFixtures.data(named: "broadcast-session-direct-prepare.v1")
        )
        XCTAssertEqual(prepared.ingestEndpoint?.streamName, "fixture-stream-key")
        XCTAssertTrue(prepared.directIngest.isEmpty)

        let live = try JSONDecoder().decode(
            YouTubeSessionResponse.self, from: ContractTestFixtures.data(named: "broadcast-session-direct-golive.v1")
        )
        XCTAssertFalse(live.isFullSnapshot)
        XCTAssertNil(live.ingestEndpoint)
        XCTAssertEqual(live.directIngest.map(\.provider), ["chzzk"])
        XCTAssertEqual(live.directIngest.first?.endpoint?.streamName, "fixture-stream-key")

        let server = try SessionStateFixture.decode()
        XCTAssertNil(server.ingestEndpoint)
        XCTAssertTrue(server.directIngest.isEmpty)
    }
}

final class DirectPublishOverlayTests: XCTestCase {
    func testDirectTargetStatusFollowsLocalPublisherWhilePhaseStaysServerOwned() throws {
        var snapshot = BroadcastSessionSnapshot()
        snapshot.apply(try SessionStateFixture.decode(providers: ["youtube", "chzzk"], phase: "live", status: "idle"))

        snapshot.applyDirectPublishStates(["youtube": .publishing])
        var policy = YouTubeBroadcastStatePolicy(stream: snapshot.stream, isChangingStreamState: false, targets: snapshot.details.targets)
        XCTAssertEqual(snapshot.details.targets?.first { $0.provider == "youtube" }?.stream.status, "streaming")
        XCTAssertEqual(snapshot.details.targets?.first { $0.provider == "chzzk" }?.stream.status, "idle")
        XCTAssertEqual(snapshot.details.targets?.first { $0.provider == "youtube" }?.stream.broadcastPhase, "live")
        XCTAssertTrue(policy.canPauseBroadcast)

        snapshot.applyDirectPublishStates(["youtube": .paused])
        policy = YouTubeBroadcastStatePolicy(stream: snapshot.stream, isChangingStreamState: false, targets: snapshot.details.targets)
        XCTAssertTrue(policy.canResumeBroadcast)
        XCTAssertFalse(policy.canPauseBroadcast)
    }

    func testEndedTargetIsNotOverridden() throws {
        var snapshot = BroadcastSessionSnapshot()
        snapshot.apply(try SessionStateFixture.decode(providers: ["youtube"], phase: "idle", status: "stopped"))
        snapshot.applyDirectPublishStates(["youtube": .publishing])
        XCTAssertEqual(snapshot.details.targets?.first?.stream.status, "stopped")
        XCTAssertEqual(snapshot.stream?.status, "stopped")
    }
}

final class DirectMediaPipelineTests: XCTestCase {
    func testRotationProducesUprightFrameDimensions() throws {
        let converter = DirectFrameConverter()
        let source = try makePixelBuffer(width: 64, height: 32)
        let time = CMTime(value: 1, timescale: 30)

        let rotated = try XCTUnwrap(converter.sampleBuffer(from: source, rotation: 90, presentationTime: time))
        let dimensions = try XCTUnwrap(rotated.formatDescription.map(CMVideoFormatDescriptionGetDimensions))
        XCTAssertEqual(dimensions.width, 32)
        XCTAssertEqual(dimensions.height, 64)
        XCTAssertEqual(rotated.presentationTimeStamp, time)

        let upright = try XCTUnwrap(converter.sampleBuffer(from: source, rotation: 0, presentationTime: time))
        XCTAssertTrue(CMSampleBufferGetImageBuffer(upright) === source)
    }

    func testFanoutSendsFramesOnlyToActiveTargetsAndSlateToPausedTargets() throws {
        let fanout = DirectMediaFanout()
        let active = RecordingDirectOutput()
        let paused = RecordingDirectOutput()
        XCTAssertFalse(fanout.acceptsMedia)
        fanout.attach(active, provider: "youtube")
        fanout.attach(paused, provider: "chzzk")
        fanout.setPaused(true, provider: "chzzk")
        XCTAssertTrue(fanout.acceptsMedia)

        let buffer = try makePixelBuffer(width: 64, height: 32)
        fanout.appendVideo(pixelBuffer: buffer, rotation: 90, timestampNs: 1_000_000_000)
        XCTAssertEqual(active.videoSizes, [CGSize(width: 32, height: 64)])
        XCTAssertEqual(fanout.latestVideoDimensions?.width, 32)

        let slate = expectation(description: "slate")
        paused.onVideo = { slate.fulfill() }
        wait(for: [slate], timeout: 2)
        paused.onVideo = nil
        XCTAssertEqual(paused.videoSizes.first, CGSize(width: 32, height: 64))
        XCTAssertGreaterThan(paused.silentAudioBuffers, 0)
        XCTAssertEqual(active.silentAudioBuffers, 0)

        fanout.detach(provider: "youtube")
        fanout.detach(provider: "chzzk")
        XCTAssertFalse(fanout.acceptsMedia)
    }

    func testSilenceBufferMatchesSlateDuration() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let buffer = try XCTUnwrap(DirectMediaFanout.silence(format: format, duration: 1.0 / 15))
        XCTAssertEqual(buffer.frameLength, 3_200)
        XCTAssertEqual(buffer.floatChannelData?[0][0], 0)
    }

    func testVideoSettingsUseFrameOrientationAndPresetRate() {
        let portrait = DirectBroadcastPublisher.videoSettings(
            quality: .fullHD30, dimensions: CMVideoDimensions(width: 1080, height: 1920)
        )
        XCTAssertEqual(portrait.videoSize, CGSize(width: 1080, height: 1920))
        XCTAssertEqual(portrait.bitRate, 6_000_000)
        XCTAssertEqual(portrait.maxKeyFrameIntervalDuration, 2)
        XCTAssertEqual(portrait.expectedFrameRate, 30)
        let hd = DirectBroadcastPublisher.videoSettings(quality: .hd24, dimensions: nil)
        XCTAssertEqual(hd.videoSize, CGSize(width: 1280, height: 720))
        XCTAssertEqual(hd.bitRate, 3_500_000)
    }

    private func makePixelBuffer(width: Int, height: Int) throws -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        let status = CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attributes, &buffer)
        XCTAssertEqual(status, kCVReturnSuccess)
        return try XCTUnwrap(buffer)
    }
}

private final class RecordingDirectOutput: DirectMediaOutput, @unchecked Sendable {
    private let lock = NSLock()
    private var sizes: [CGSize] = []
    private var silence = 0
    private var videoHandler: (() -> Void)?

    var videoSizes: [CGSize] { lock.withLock { sizes } }
    var silentAudioBuffers: Int { lock.withLock { silence } }
    var onVideo: (() -> Void)? {
        get { lock.withLock { videoHandler } }
        set { lock.withLock { videoHandler = newValue } }
    }

    func appendVideo(_ sampleBuffer: CMSampleBuffer) {
        guard let format = sampleBuffer.formatDescription else { return }
        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
        let handler = lock.withLock {
            sizes.append(CGSize(width: Int(dimensions.width), height: Int(dimensions.height)))
            let handler = videoHandler
            videoHandler = nil
            return handler
        }
        handler?()
    }

    func appendAudio(_ buffer: AVAudioPCMBuffer, when: AVAudioTime) {
        let isSilent = buffer.floatChannelData.map { channels in
            (0..<Int(buffer.frameLength)).allSatisfy { channels[0][$0] == 0 }
        } ?? false
        if isSilent { lock.withLock { silence += 1 } }
    }
}

@MainActor
final class DirectPublishIntegrationTests: XCTestCase {
    private var suiteName: String!
    private var userDefaults: UserDefaults!
    private var publisher: FakeDirectPublisher!

    override func setUp() {
        super.setUp()
        suiteName = "com.framework.innolive.tests.direct-publish.\(UUID().uuidString)"
        userDefaults = UserDefaults(suiteName: suiteName)
        publisher = FakeDirectPublisher()
        DirectPublishURLProtocol.reset()
    }

    override func tearDown() {
        DirectPublishURLProtocol.reset()
        userDefaults.removePersistentDomain(forName: suiteName)
        userDefaults = nil
        publisher = nil
        super.tearDown()
    }

    func testOnDeviceSessionPrepareStartsYouTubeDirectPublish() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "idle")
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(providers: ["youtube"], phase: "idle", status: "idle").utf8)),
            .init(statusCode: 200, data: try ContractTestFixtures.data(named: "broadcast-session-direct-prepare.v1")),
        ]

        let prepared = await integration.prepareYouTubeStream(accessToken: "access-token")

        XCTAssertTrue(prepared)
        XCTAssertNil(integration.errorMessage)
        XCTAssertEqual(publisher.started.map(\.provider), ["youtube"])
        XCTAssertEqual(publisher.started.first?.endpoint.connectURL, "rtmps://a.rtmp.youtube.com/live2")
        XCTAssertEqual(integration.responseState.details.targets?.first?.stream.status, "streaming")
        XCTAssertEqual(DirectPublishURLProtocol.paths, ["/sessions/session-direct/broadcast", "/sessions/session-direct/stream/prepare"])
        integration.reset()
    }

    func testDirectPublishFailureStopsPreparedTargetWithoutLeakingIngestURL() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "idle")
        publisher.failStart = true
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(providers: ["youtube"], phase: "idle", status: "idle").utf8)),
            .init(statusCode: 200, data: try ContractTestFixtures.data(named: "broadcast-session-direct-prepare.v1")),
            .init(statusCode: 200, data: Data(SessionStateFixture.stream(phase: "idle", status: "stopped").utf8)),
        ]

        let prepared = await integration.prepareYouTubeStream(accessToken: "access-token")

        XCTAssertFalse(prepared)
        XCTAssertEqual(DirectPublishURLProtocol.paths.last, "/sessions/session-direct/stream/stop")
        XCTAssertEqual(DirectPublishURLProtocol.requests.last?.url?.query, "provider=youtube")
        let message = try XCTUnwrap(integration.errorMessage)
        XCTAssertFalse(message.contains("fixture-stream-key"))
        XCTAssertFalse(message.contains("rtmp"))
        integration.reset()
    }

    func testServerSessionIgnoresIngestURL() async throws {
        let integration = try makeIntegration(aiProcessing: "server", phase: "idle")
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(providers: ["youtube"], phase: "idle", status: "idle").utf8)),
            .init(statusCode: 200, data: try ContractTestFixtures.data(named: "broadcast-session-direct-prepare.v1")),
        ]

        let prepared = await integration.prepareYouTubeStream(accessToken: "access-token")

        XCTAssertTrue(prepared)
        XCTAssertTrue(publisher.started.isEmpty)
        integration.reset()
    }

    func testGoLiveConnectsCHZZKDirectIngest() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared", providers: ["chzzk"])
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: try ContractTestFixtures.data(named: "broadcast-session-direct-golive.v1")),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")

        XCTAssertEqual(publisher.started.map(\.provider), ["chzzk"])
        XCTAssertEqual(publisher.started.first?.endpoint.streamName, "fixture-stream-key")
        XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
        XCTAssertNil(integration.errorMessage)
        integration.reset()
    }

    func testPauseAndResumeStayLocalAndStopDisconnects() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "live")
        publisher.states = ["youtube": .publishing]
        integration.applyLiveEditingSnapshotForTesting(try SessionStateFixture.decode(phase: "live", status: "idle"))
        XCTAssertTrue(integration.canPauseYouTubeBroadcast)

        await integration.pauseYouTubeStream(accessToken: "access-token")
        XCTAssertEqual(publisher.pausedCalls, [true])
        XCTAssertTrue(integration.isYouTubeBroadcastPaused)

        await integration.resumeYouTubeStream(accessToken: "access-token")
        XCTAssertEqual(publisher.pausedCalls, [true, false])
        XCTAssertFalse(integration.isYouTubeBroadcastPaused)
        XCTAssertFalse(DirectPublishURLProtocol.paths.contains { $0.hasSuffix("/pause") || $0.hasSuffix("/resume") })

        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.stream(phase: "idle", status: "stopped").utf8)),
        ]
        await integration.stopYouTubeStream(accessToken: "access-token")
        XCTAssertEqual(publisher.stopped, ["youtube"])
        XCTAssertEqual(DirectPublishURLProtocol.paths.last, "/sessions/session-direct/stream/stop")
        integration.reset()
    }

    func testBackgroundPausesDirectTargetLocally() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "live")
        publisher.states = ["youtube": .publishing]
        integration.applyLiveEditingSnapshotForTesting(try SessionStateFixture.decode(phase: "live", status: "idle"))

        await integration.handleAppBecameActive(accessToken: "access-token")
        await integration.handleAppMovedToBackground(accessToken: "access-token")
        XCTAssertEqual(publisher.pausedCalls, [true])
        await integration.handleAppBecameActive(accessToken: "access-token")
        XCTAssertEqual(publisher.pausedCalls, [true, false])
        XCTAssertFalse(DirectPublishURLProtocol.paths.contains { $0.hasSuffix("/pause") || $0.hasSuffix("/resume") })
        XCTAssertNil(integration.errorMessage)
        integration.reset()
    }

    func testDirectSessionBlocksInPlaceModeChangeAndBroadcastModeSwitch() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "live")
        integration.applyLiveEditingSnapshotForTesting(try SessionStateFixture.decode(phase: "live", status: "idle"))

        XCTAssertFalse(integration.canChangeBroadcastMode)
        let changed = await integration.changeAIProcessingMode(.server, accessToken: "access-token")
        XCTAssertFalse(changed)
        XCTAssertTrue(DirectPublishURLProtocol.requests.isEmpty)
        integration.reset()
    }

    private func makeIntegration(aiProcessing: String, phase: String, providers: [String] = ["youtube"]) throws -> YouTubeIntegration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [DirectPublishURLProtocol.self]
        let api = YouTubeAPI(
            urlSession: URLSession(configuration: configuration),
            serverURLProvider: { URL(string: "https://example.invalid\($0)") }
        )
        let integration = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: userDefaults),
            api: api,
            localModelsAvailable: { true },
            persistAIProcessingMode: { _ in },
            directPublisher: publisher
        )
        let targets = providers.map {
            "{\"provider\":\"\($0)\",\"stream\":\(SessionStateFixture.stream(phase: phase, status: "idle"))}"
        }.joined(separator: ",")
        let json = """
        {"session_id":"session-direct","owner_token":"owner","provider":"\(providers[0])","ai_processing":"\(aiProcessing)",\
        "stream":\(SessionStateFixture.stream(phase: phase, status: "idle")),"targets":[\(targets)]}
        """
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(json.utf8))
        integration.seedPreparedVideoSessionForTesting(
            connection: YouTubeConnection(provider: "youtube", channel: YouTubeChannel(id: "channel", title: "Channel")),
            session: session,
            videoTrack: YouTubeVideoTrackState(id: "track", kind: "video", readyState: "live")
        )
        return integration
    }
}

@MainActor
private final class FakeDirectPublisher: DirectBroadcastPublishing {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    var states: [String: DirectPublishState] = [:]
    var mediaSink: (any DirectMediaSink)? { nil }
    var onStateChange: (() -> Void)?
    var onPermanentFailure: ((String) -> Void)?
    var failStart = false
    private(set) var started: [(provider: String, endpoint: DirectIngestEndpoint)] = []
    private(set) var stopped: [String] = []
    private(set) var pausedCalls: [Bool] = []

    func start(provider: String, endpoint: DirectIngestEndpoint, quality: CameraQualityPreset) async throws {
        if failStart { throw DirectPublishError.connectionFailed(provider: provider) }
        started.append((provider, endpoint))
        states[provider] = .publishing
        onStateChange?()
    }

    func stop(provider: String) async {
        guard states.removeValue(forKey: provider) != nil else { return }
        stopped.append(provider)
        onStateChange?()
    }

    func stopAll() async {
        for provider in states.keys { await stop(provider: provider) }
    }

    func setPaused(_ paused: Bool, provider: String) {
        pausedCalls.append(paused)
        states[provider] = paused ? .paused : .publishing
        onStateChange?()
    }
}

private final class DirectPublishURLProtocol: URLProtocol {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    struct Response {
        let statusCode: Int
        let data: Data
    }

    nonisolated(unsafe) static var responses: [Response] = []
    nonisolated(unsafe) static var requests: [URLRequest] = []
    static var paths: [String] { requests.compactMap { $0.url?.path } }

    static func reset() {
        responses = []
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let response = Self.responses.isEmpty ? Response(statusCode: 500, data: Data()) : Self.responses.removeFirst()
        guard let url = request.url,
              let http = HTTPURLResponse(url: url, statusCode: response.statusCode, httpVersion: nil,
                                         headerFields: ["Content-Type": "application/json"]) else { return }
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        if !response.data.isEmpty { client?.urlProtocol(self, didLoad: response.data) }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
