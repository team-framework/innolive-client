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

    func testDecodesSharedPrepareAndGoLiveFixtures() throws {
        let prepared = try JSONDecoder().decode(
            YouTubeSessionResponse.self, from: ContractTestFixtures.data(named: "direct-publish-prepare-youtube.v1")
        )
        XCTAssertEqual(prepared.ingestEndpoint?.streamName, "fixture-stream-key")
        XCTAssertTrue(prepared.directIngest.isEmpty)

        let live = try JSONDecoder().decode(
            YouTubeSessionResponse.self, from: ContractTestFixtures.data(named: "direct-publish-golive-chzzk.v1")
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

final class DirectPublishSharedContractTests: XCTestCase {
    private func object(_ name: String) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: ContractTestFixtures.data(named: name)) as? [String: Any])
    }

    private func encodedObject(_ value: some Encodable) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as? [String: Any])
    }

    func testSessionCreateRequestUsesSharedTopLevelProcessingMode() throws {
        let contract = try object("direct-publish-session-request.v1")
        let onDevice = try encodedObject(YouTubeSessionCreateRequest(mode: .onDevice))
        XCTAssertEqual(onDevice["ai_processing"] as? String, contract["ai_processing"] as? String)
        // 처리 방식을 돌려주지 않는 구 서버와의 협상용이다. 서버는 최상위 값을 먼저 쓴다.
        XCTAssertEqual((onDevice["metadata"] as? [String: String])?["ai_processing"], "on_device")
        XCTAssertTrue(try encodedObject(YouTubeSessionCreateRequest(mode: .server)).isEmpty)
        // 서버 처리도 해상도는 보낸다. 처리 방식 필드는 넣지 않는다.
        let server = try encodedObject(YouTubeSessionCreateRequest(mode: .server, broadcastResolution: "fhd"))
        XCTAssertEqual(server as? [String: String], ["broadcast_resolution": "fhd"])
    }

    func testHeartbeatRequestKeysMatchSharedFixture() throws {
        let contract = try object("direct-publish-heartbeat-request.v1")
        let request = DirectHeartbeatRequest(
            sentAt: "2026-10-12T08:30:00Z",
            targets: [.init(provider: "youtube", connected: true, bitrateKbps: 5_200, reconnects: 0)],
            video: .init(fps: 29.8, droppedFrames: 12, width: 1280, height: 720),
            anonymization: .init(enabled: true, inferenceMsP50: 18.2, inferenceMsP95: 31, failedFrames: 0),
            device: .init(thermal: "fair", batteryPct: 64, charging: false),
            model: "iPhone15,4"
        )
        let encoded = try encodedObject(request)
        XCTAssertEqual(Set(encoded.keys), Set(contract.keys))
        for key in ["video", "anonymization"] {
            XCTAssertEqual(Set((encoded[key] as? [String: Any] ?? [:]).keys), Set((contract[key] as? [String: Any] ?? [:]).keys), key)
        }
        let target = { (json: [String: Any]) in Set(((json["targets"] as? [[String: Any]])?.first ?? [:]).keys) }
        XCTAssertEqual(target(encoded), target(contract))
        // iOS는 기기 전체 CPU 사용률을 얻을 공개 API가 없어 선택 필드인 cpu_pct를 보내지 않는다.
        let contractDevice = Set((contract["device"] as? [String: Any] ?? [:]).keys).subtracting(["cpu_pct"])
        XCTAssertEqual(Set((encoded["device"] as? [String: Any] ?? [:]).keys), contractDevice)
    }

    func testHeartbeatResponseDecodesSharedFixture() throws {
        let response = try JSONDecoder().decode(
            DirectHeartbeatResponse.self, from: ContractTestFixtures.data(named: "direct-publish-heartbeat-response.v1")
        )
        XCTAssertEqual(response.stopTargets, ["chzzk"])
        XCTAssertEqual(response.notices.count, 1)
        XCTAssertEqual(response.remainingTime, .seconds(540))
        let refusal = try object("direct-publish-heartbeat-not-applicable.v1")
        let code = (refusal["error"] as? [String: Any])?["code"] as? String
        XCTAssertTrue(YouTubeIntegration.heartbeatTerminalCodes.contains(try XCTUnwrap(code)))
    }
}

final class DirectHeartbeatModelTests: XCTestCase {
    func testBuilderReportsIntervalFPSBitrateAndCumulativeCounters() throws {
        var builder = DirectHeartbeatBuilder()
        let start = Date(timeIntervalSince1970: 1_000)
        let device = DirectHeartbeatRequest.Device(thermal: "fair", batteryPct: 64, charging: false)
        var status = DirectPublishStatus(
            targets: [.init(provider: "youtube", connected: true, sentBytes: 1_000_000, reconnects: 0)],
            metrics: .init(videoFrames: 300, droppedFrames: 1, failedFrames: 0, inferenceMilliseconds: [10, 12, 40],
                           videoWidth: 1280, videoHeight: 720, startedAt: start)
        )

        let first = builder.makeRequest(status: status, anonymizationEnabled: true, device: device,
                                        model: "iPhone15,4", now: start.addingTimeInterval(10))
        XCTAssertEqual(first.video.fps, 30)
        XCTAssertNil(first.targets.first?.bitrateKbps)
        XCTAssertEqual(first.anonymization.inferenceMsP50, 12)
        XCTAssertEqual(first.anonymization.inferenceMsP95, 40)
        XCTAssertEqual(first.sentAt, "1970-01-01T00:16:50Z")

        status.targets = [.init(provider: "youtube", connected: true, sentBytes: 11_000_000, reconnects: 1)]
        status.metrics.videoFrames = 750
        status.metrics.droppedFrames = 3
        status.metrics.inferenceMilliseconds = []
        let second = builder.makeRequest(status: status, anonymizationEnabled: true, device: device,
                                         model: "iPhone15,4", now: start.addingTimeInterval(25))
        XCTAssertEqual(second.video.fps, 30)
        XCTAssertEqual(second.video.droppedFrames, 3)
        XCTAssertEqual(second.targets.first?.bitrateKbps, 5_333)
        XCTAssertEqual(second.targets.first?.reconnects, 1)
        XCTAssertNil(second.anonymization.inferenceMsP50)
    }

    func testRequestEncodesContractKeysAndOmitsUnavailableOptionalFields() throws {
        let request = DirectHeartbeatRequest(
            sentAt: "2026-10-12T08:30:00Z",
            targets: [.init(provider: "chzzk", connected: false, bitrateKbps: nil, reconnects: 2)],
            video: .init(fps: 29.8, droppedFrames: 12, width: 1280, height: 720),
            anonymization: .init(enabled: true, inferenceMsP50: nil, inferenceMsP95: nil, failedFrames: 0),
            device: .init(thermal: "nominal", batteryPct: nil, charging: nil),
            model: "iPhone15,3"
        )
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["sent_at", "targets", "video", "anonymization", "device", "platform", "model"])
        XCTAssertEqual(json["platform"] as? String, "ios")
        let target = try XCTUnwrap((json["targets"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(target.keys), ["provider", "connected", "reconnects"])
        let video = try XCTUnwrap(json["video"] as? [String: Any])
        XCTAssertEqual(video["dropped_frames"] as? Int, 12)
        let anonymization = try XCTUnwrap(json["anonymization"] as? [String: Any])
        XCTAssertEqual(Set(anonymization.keys), ["enabled", "failed_frames"])
        XCTAssertEqual(Set((json["device"] as? [String: Any] ?? [:]).keys), ["thermal"])
    }

    func testResponseDecodesStopTargetsNoticesAndRemainingTime() throws {
        let limited = try JSONDecoder().decode(DirectHeartbeatResponse.self, from: Data(
            #"{"stop_targets":["chzzk"],"notices":[{"code":"broadcast_limit_10m","at":"2026-10-12T08:30:00Z"}],"broadcast_remaining_seconds":540}"#.utf8
        ))
        XCTAssertEqual(limited.stopTargets, ["chzzk"])
        XCTAssertEqual(limited.notices.first?.code, "broadcast_limit_10m")
        XCTAssertEqual(limited.remainingTime, .seconds(540))
        let unlimited = try JSONDecoder().decode(DirectHeartbeatResponse.self, from: Data(
            #"{"stop_targets":[],"notices":[],"broadcast_remaining_seconds":null}"#.utf8
        ))
        XCTAssertEqual(unlimited.remainingTime, .unlimitedOrInactive)
        XCTAssertTrue(unlimited.stopTargets.isEmpty)
    }

    func testThermalStatesMatchContractValues() {
        XCTAssertEqual(DirectHeartbeatDevice.thermal(.nominal), "nominal")
        XCTAssertEqual(DirectHeartbeatDevice.thermal(.fair), "fair")
        XCTAssertEqual(DirectHeartbeatDevice.thermal(.serious), "serious")
        XCTAssertEqual(DirectHeartbeatDevice.thermal(.critical), "critical")
        XCTAssertFalse(DirectHeartbeatDevice.model.isEmpty)
    }

    func testMetricsCountFramesDropsAndDrainInferenceSamples() throws {
        let fanout = DirectMediaFanout()
        fanout.metrics.reset()
        fanout.recordInference(milliseconds: 5)
        XCTAssertTrue(fanout.metrics.drain().inferenceMilliseconds.isEmpty)
        let output = RecordingDirectOutput()
        fanout.attach(output, provider: "youtube")
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, 64, 32, kCVPixelFormatType_32BGRA,
                            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
        fanout.appendVideo(pixelBuffer: try XCTUnwrap(buffer), rotation: 0, timestampNs: 1)
        fanout.recordInference(milliseconds: 5)
        fanout.recordAnonymizationFailure()
        let snapshot = fanout.metrics.drain()
        XCTAssertEqual(snapshot.videoFrames, 1)
        XCTAssertEqual(snapshot.failedFrames, 1)
        XCTAssertEqual(snapshot.inferenceMilliseconds, [5])
        XCTAssertEqual(snapshot.videoWidth, 64)
        XCTAssertTrue(fanout.metrics.drain().inferenceMilliseconds.isEmpty)
        XCTAssertEqual(fanout.metrics.drain().videoFrames, 1)
        fanout.detach(provider: "youtube")
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

        fanout.detach(provider: "youtube")
        fanout.detach(provider: "chzzk")
        XCTAssertFalse(fanout.acceptsMedia)
    }

    func testFanoutKeepsAudioTrackWithRealTimeSilenceWhileMicrophoneIsQuiet() throws {
        // YouTube는 음성 트랙이 없으면 스트림을 활성으로 보지 않는다(2026-10-12 실기기 broadcast_not_ready).
        let fanout = DirectMediaFanout()
        let output = RecordingDirectOutput()
        fanout.attach(output, provider: "youtube")
        let started = Date()
        let filled = expectation(description: "silence")
        DispatchQueue.global().asyncAfter(deadline: .now() + 1.2) { filled.fulfill() }
        wait(for: [filled], timeout: 3)
        let elapsed = Date().timeIntervalSince(started)
        fanout.detach(provider: "youtube")

        XCTAssertEqual(output.audioFormats, [DirectMediaFanout.audioFormat])
        // 마이크 음성이 없으면 연결 직후부터 실제 시간만큼 무음을 채워야 음성 시간축이 밀리지 않는다.
        let seconds = Double(output.silentFrames) / DirectMediaFanout.audioFormat.sampleRate
        XCTAssertEqual(seconds, elapsed, accuracy: 0.15)
    }

    func testFanoutDoesNotAddSilenceWhileMicrophoneDelivers() throws {
        let fanout = DirectMediaFanout()
        let output = RecordingDirectOutput()
        fanout.attach(output, provider: "youtube")
        let format = DirectMediaFanout.audioFormat
        let tone = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 960))
        tone.frameLength = 960
        for channel in 0..<Int(format.channelCount) {
            tone.floatChannelData?[channel].update(repeating: 0.25, count: 960)
        }
        for _ in 0..<40 {
            fanout.appendAudio(tone, when: AVAudioTime(hostTime: mach_absolute_time()))
            Thread.sleep(forTimeInterval: 0.02)
        }
        fanout.detach(provider: "youtube")

        XCTAssertEqual(output.soundAudioBuffers, 40)
        XCTAssertEqual(output.silentAudioBuffers, 0)
    }

    func testResamplerConvertsMicrophoneInputToStereo48kHz() throws {
        let resampler = DirectAudioResampler()
        let input = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 44_100, channels: 1, interleaved: false))
        var frames = 0
        for _ in 0..<10 {
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: input, frameCapacity: 4_410))
            buffer.frameLength = 4_410
            buffer.floatChannelData?[0].update(repeating: 0.5, count: 4_410)
            guard let converted = resampler.convert(buffer) else { continue }
            XCTAssertEqual(converted.format, DirectAudioResampler.outputFormat)
            let channels = try XCTUnwrap(converted.floatChannelData)
            let last = Int(converted.frameLength) - 1
            XCTAssertEqual(channels[0][last], channels[1][last])
            XCTAssertEqual(channels[0][last], 0.5, accuracy: 0.05)
            frames += Int(converted.frameLength)
        }
        // 1초 분량 입력은 48,000프레임 근처가 된다. 리샘플러 지연만큼 조금 모자랄 수 있다.
        XCTAssertEqual(Double(frames), 48_000, accuracy: 600)
    }

    func testPauseSlateUsesServerPauseScreenForFrameOrientation() throws {
        let converter = DirectFrameConverter()
        for (width, height) in [(720, 1280), (1280, 720)] {
            let sample = try XCTUnwrap(converter.pauseSlateSampleBuffer(width: width, height: height, presentationTime: .zero))
            let pixels = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
            XCTAssertEqual(CVPixelBufferGetWidth(pixels), width)
            XCTAssertEqual(CVPixelBufferGetHeight(pixels), height)
            XCTAssertEqual(CVPixelBufferGetPixelFormatType(pixels), kCVPixelFormatType_32BGRA)
            // 서버 화면은 검은 바탕 가운데에 로고와 "스트리머가 방송을 일시 중지했습니다." 문구가 있다.
            XCTAssertEqual(try brightPixels(in: pixels, rows: 0..<Int(Double(height) * 0.3)), 0)
            XCTAssertGreaterThan(try brightPixels(in: pixels, rows: Int(Double(height) * 0.4)..<Int(Double(height) * 0.6)), 500)
        }
    }

    func testPauseSlateCropMatchesServerCenterCrop() {
        typealias Converter = DirectFrameConverter
        XCTAssertEqual(Converter.pauseSlateCropRect(sourceWidth: 1080, sourceHeight: 1920, targetWidth: 720, targetHeight: 1280),
                       CGRect(x: 0, y: 0, width: 1080, height: 1920))
        XCTAssertEqual(Converter.pauseSlateCropRect(sourceWidth: 1080, sourceHeight: 1920, targetWidth: 32, targetHeight: 64),
                       CGRect(x: 60, y: 0, width: 960, height: 1920))
        XCTAssertEqual(Converter.pauseSlateCropRect(sourceWidth: 1920, sourceHeight: 1080, targetWidth: 2560, targetHeight: 1080),
                       CGRect(x: 0, y: 135, width: 1920, height: 810))
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
        // 세션 해상도(720p)에 맞춰 카메라가 내려간 경우 FHD 설정값이어도 720p 비트레이트를 쓴다.
        let synced = DirectBroadcastPublisher.videoSettings(
            quality: .fullHD30, dimensions: CMVideoDimensions(width: 720, height: 1280)
        )
        XCTAssertEqual(synced.bitRate, 3_500_000)
    }

    private func brightPixels(in buffer: CVPixelBuffer, rows: Range<Int>) throws -> Int {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let base = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        var count = 0
        for row in rows {
            for column in 0..<CVPixelBufferGetWidth(buffer) {
                let pixel = base + row * bytesPerRow + column * 4
                if pixel[0] > 200, pixel[1] > 200, pixel[2] > 200 { count += 1 }
            }
        }
        return count
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
    private var silenceFrames = 0
    private var sound = 0
    private var formats: [AVAudioFormat] = []
    private var videoHandler: (() -> Void)?

    var videoSizes: [CGSize] { lock.withLock { sizes } }
    var silentAudioBuffers: Int { lock.withLock { silence } }
    var silentFrames: Int { lock.withLock { silenceFrames } }
    var soundAudioBuffers: Int { lock.withLock { sound } }
    var audioFormats: [AVAudioFormat] { lock.withLock { formats } }
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
        lock.withLock {
            if formats.last != buffer.format { formats.append(buffer.format) }
            if isSilent {
                silence += 1
                silenceFrames += Int(buffer.frameLength)
            } else {
                sound += 1
            }
        }
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
        // Glow has no server time but must still prepare on-device.
        await integration.planStore.refresh(api: try DevicePlanStub(), accessToken: "test")
        // 실제 prepare 응답은 세션 전체 응답(targets 포함)에 ingest_url을 더한 형태다. 공유 fixture는 targets를 생략한다.
        var prepare = try XCTUnwrap(JSONSerialization.jsonObject(
            with: ContractTestFixtures.data(named: "direct-publish-prepare-youtube.v1")
        ) as? [String: Any])
        prepare["targets"] = [["provider": "youtube", "stream": prepare["stream"] as Any]]
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(providers: ["youtube"], phase: "idle", status: "idle").utf8)),
            .init(statusCode: 200, data: try JSONSerialization.data(withJSONObject: prepare)),
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

    func testGlowServerPreparationIsBlockedBeforeSavingSettings() async throws {
        let integration = try makeIntegration(aiProcessing: "server", phase: "idle")
        await integration.planStore.refresh(api: try DevicePlanStub(), accessToken: "test")
        let prepared = await integration.prepareYouTubeStream(accessToken: "access-token")
        XCTAssertFalse(prepared)
        XCTAssertTrue(DirectPublishURLProtocol.requests.isEmpty)
        XCTAssertEqual(integration.errorMessage, String(localized: "이 요금제는 서버 송출을 지원하지 않습니다. 방송 준비를 취소한 뒤 온디바이스 방식을 선택해 주세요."))
        integration.reset()
    }

    func testDirectPublishFailureStopsPreparedTargetWithoutLeakingIngestURL() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "idle")
        publisher.failStart = true
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(providers: ["youtube"], phase: "idle", status: "idle").utf8)),
            .init(statusCode: 200, data: try ContractTestFixtures.data(named: "direct-publish-prepare-youtube.v1")),
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

    func testMissingPrepareIngestStopsTargetInsteadOfWaitingForVideo() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "idle")
        var prepare = try XCTUnwrap(JSONSerialization.jsonObject(
            with: ContractTestFixtures.data(named: "direct-publish-prepare-youtube.v1")
        ) as? [String: Any])
        prepare.removeValue(forKey: "ingest_url")
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(providers: ["youtube"], phase: "idle", status: "idle").utf8)),
            .init(statusCode: 200, data: try JSONSerialization.data(withJSONObject: prepare)),
            .init(statusCode: 200, data: Data(SessionStateFixture.stream(phase: "idle", status: "stopped").utf8)),
        ]

        let prepared = await integration.prepareYouTubeStream(accessToken: "access-token")

        XCTAssertFalse(prepared)
        XCTAssertTrue(publisher.started.isEmpty)
        XCTAssertEqual(DirectPublishURLProtocol.paths.last, "/sessions/session-direct/stream/stop")
        XCTAssertEqual(integration.errorMessage, DirectPublishError.missingIngest(provider: "youtube").errorDescription)
        integration.reset()
    }

    func testGoLiveSkipsFailedCHZZKTarget() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared", providers: ["chzzk"])
        var live = try XCTUnwrap(JSONSerialization.jsonObject(
            with: ContractTestFixtures.data(named: "direct-publish-golive-chzzk.v1")
        ) as? [String: Any])
        live["failed_targets"] = [["provider": "chzzk", "code": "streaming_golive_failed", "message": "failed"]]
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: try JSONSerialization.data(withJSONObject: live)),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")

        XCTAssertTrue(publisher.started.isEmpty)
        XCTAssertFalse(DirectPublishURLProtocol.paths.contains { $0.hasSuffix("/stream/stop") })
        integration.reset()
    }

    func testGoLiveReportsMissingCHZZKIngest() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared", providers: ["chzzk"])
        var live = try XCTUnwrap(JSONSerialization.jsonObject(
            with: ContractTestFixtures.data(named: "direct-publish-golive-chzzk.v1")
        ) as? [String: Any])
        live.removeValue(forKey: "direct_ingest")
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: try JSONSerialization.data(withJSONObject: live)),
            .init(statusCode: 200, data: Data(SessionStateFixture.stream(phase: "idle", status: "stopped").utf8)),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")

        XCTAssertTrue(publisher.started.isEmpty)
        XCTAssertEqual(DirectPublishURLProtocol.requests.last?.url?.query, "provider=chzzk")
        XCTAssertEqual(DirectPublishURLProtocol.paths.last, "/sessions/session-direct/stream/stop")
        let message = try XCTUnwrap(integration.errorMessage)
        XCTAssertTrue(message.contains(try XCTUnwrap(DirectPublishError.missingIngest(provider: "chzzk").errorDescription)))
        integration.reset()
    }

    func testServerSessionIgnoresIngestURL() async throws {
        let integration = try makeIntegration(aiProcessing: "server", phase: "idle")
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(providers: ["youtube"], phase: "idle", status: "idle").utf8)),
            .init(statusCode: 200, data: try ContractTestFixtures.data(named: "direct-publish-prepare-youtube.v1")),
        ]

        let prepared = await integration.prepareYouTubeStream(accessToken: "access-token")

        XCTAssertTrue(prepared)
        XCTAssertTrue(publisher.started.isEmpty)
        integration.reset()
    }

    func testGoLiveConnectsCHZZKDirectIngest() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared", providers: ["chzzk"])
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: try ContractTestFixtures.data(named: "direct-publish-golive-chzzk.v1")),
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
        // 공유 계약 순서: stop 요청을 보낸 뒤 연결을 끊는다.
        XCTAssertEqual(publisher.lastRequestPathAtStop, "/sessions/session-direct/stream/stop")
        integration.reset()
    }

    func testStopClosesDirectConnectionEvenWhenStopRequestFails() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "live")
        publisher.states = ["youtube": .publishing]
        integration.applyLiveEditingSnapshotForTesting(try SessionStateFixture.decode(phase: "live", status: "idle"))
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 502, data: Data(#"{"error":{"code":"streaming_stop_failed","message":"none"}}"#.utf8)),
        ]

        await integration.stopYouTubeStream(accessToken: "access-token")

        XCTAssertEqual(DirectPublishURLProtocol.paths.last, "/sessions/session-direct/stream/stop")
        XCTAssertEqual(publisher.stopped, ["youtube"])
        XCTAssertNotNil(integration.errorMessage)
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

    func testGoLiveSendsFirstHeartbeatAndStopsServerEndedTargets() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared")
        publisher.states = ["youtube": .publishing]
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(phase: "live", status: "idle").utf8)),
            .init(statusCode: 200, data: Data(#"{"stop_targets":["youtube"],"notices":[{"code":"broadcast_limit_10m","at":"2026-10-12T08:30:00Z"}],"broadcast_remaining_seconds":540}"#.utf8)),
            .init(statusCode: 200, data: Data(SessionStateFixture.json(phase: "idle", status: "stopped").utf8)),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")
        try await waitUntil { self.publisher.stopped == ["youtube"] && integration.errorMessage != nil }

        let heartbeat = try XCTUnwrap(DirectPublishURLProtocol.requests.first { $0.url?.path.hasSuffix("/heartbeat") == true })
        XCTAssertEqual(heartbeat.httpMethod, "POST")
        XCTAssertEqual(heartbeat.value(forHTTPHeaderField: "X-Session-Owner-Token"), "owner")
        let body = try XCTUnwrap(DirectPublishURLProtocol.body(heartbeat))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["platform"] as? String, "ios")
        XCTAssertNotNil(json["sent_at"] as? String)
        XCTAssertFalse((json["model"] as? String ?? "").isEmpty)
        let targets = try XCTUnwrap(json["targets"] as? [[String: Any]])
        XCTAssertEqual(targets.first?["provider"] as? String, "youtube")
        XCTAssertEqual(targets.first?["connected"] as? Bool, true)
        let video = try XCTUnwrap(json["video"] as? [String: Any])
        XCTAssertEqual(video["dropped_frames"] as? Int, 2)
        XCTAssertEqual(video["fps"] as? Double ?? 0, 30, accuracy: 1)
        let anonymization = try XCTUnwrap(json["anonymization"] as? [String: Any])
        XCTAssertEqual(anonymization["failed_frames"] as? Int, 0)
        XCTAssertEqual(anonymization["inference_ms_p50"] as? Double, 20)
        XCTAssertNotNil((json["device"] as? [String: Any])?["thermal"])

        XCTAssertTrue(DirectPublishURLProtocol.paths.last?.hasSuffix("/sessions/session-direct") == true)
        integration.reset()
    }

    func testHeartbeatNotApplicableClosesAllDirectConnections() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared")
        publisher.states = ["youtube": .publishing]
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(phase: "live", status: "idle").utf8)),
            .init(statusCode: 409, data: try ContractTestFixtures.data(named: "direct-publish-heartbeat-not-applicable.v1")),
            .init(statusCode: 200, data: Data(SessionStateFixture.json(phase: "idle", status: "stopped").utf8)),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")
        try await waitUntil { self.publisher.stopped == ["youtube"] && integration.errorMessage != nil }
        XCTAssertFalse(integration.isSendingDirectHeartbeat)
        XCTAssertTrue(DirectPublishURLProtocol.paths.last?.hasSuffix("/sessions/session-direct") == true)
        integration.reset()
    }

    func testHeartbeatOwnershipLossClosesAllDirectConnections() async throws {
        for (status, code) in [(403, "forbidden"), (404, "not_found")] {
            DirectPublishURLProtocol.reset()
            publisher = FakeDirectPublisher()
            let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared")
            publisher.states = ["youtube": .publishing]
            DirectPublishURLProtocol.responses = [
                .init(statusCode: 200, data: Data(SessionStateFixture.json(phase: "live", status: "idle").utf8)),
                .init(statusCode: status, data: Data(#"{"error":{"code":"\#(code)","message":"none"}}"#.utf8)),
            ]

            await integration.goLiveYouTubeStream(accessToken: "access-token")
            try await waitUntil { self.publisher.stopped == ["youtube"] && integration.errorMessage != nil }
            XCTAssertFalse(integration.isSendingDirectHeartbeat, code)
            integration.reset()
        }
    }

    func testHeartbeatServerErrorKeepsPublishing() async throws {
        let integration = try makeIntegration(aiProcessing: "on_device", phase: "prepared")
        publisher.states = ["youtube": .publishing]
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(phase: "live", status: "idle").utf8)),
            .init(statusCode: 503, data: Data(#"{"error":{"code":"unavailable","message":"none"}}"#.utf8)),
        ]

        await integration.goLiveYouTubeStream(accessToken: "access-token")
        try await waitUntil { DirectPublishURLProtocol.paths.contains { $0.hasSuffix("/heartbeat") } }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertTrue(publisher.stopped.isEmpty)
        XCTAssertTrue(integration.isSendingDirectHeartbeat)
        integration.reset()
    }

    func testServerSessionNeverSendsHeartbeat() async throws {
        let integration = try makeIntegration(aiProcessing: "server", phase: "prepared")
        DirectPublishURLProtocol.responses = [
            .init(statusCode: 200, data: Data(SessionStateFixture.json(phase: "live", status: "streaming").utf8)),
        ]
        await integration.goLiveYouTubeStream(accessToken: "access-token")
        XCTAssertFalse(integration.isSendingDirectHeartbeat)
        XCTAssertFalse(DirectPublishURLProtocol.paths.contains { $0.hasSuffix("/heartbeat") })
        integration.reset()
    }

    private func waitUntil(timeout: TimeInterval = 3, _ condition: @escaping () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else { return XCTFail("condition not met before timeout") }
            try await Task.sleep(for: .milliseconds(20))
        }
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
            directPublisher: publisher,
            heartbeatInterval: .seconds(3_600)
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
    var isMicrophoneUnavailable = false
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

    private(set) var lastRequestPathAtStop: String?

    func stop(provider: String) async {
        guard states.removeValue(forKey: provider) != nil else { return }
        stopped.append(provider)
        lastRequestPathAtStop = DirectPublishURLProtocol.paths.last
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

    func heartbeatStatus() async -> DirectPublishStatus {
        DirectPublishStatus(
            targets: states.keys.sorted().map { .init(provider: $0, connected: states[$0] == .publishing, sentBytes: 1_000, reconnects: 0) },
            metrics: .init(videoFrames: 300, droppedFrames: 2, failedFrames: 0, inferenceMilliseconds: [10, 20, 30],
                           videoWidth: 1080, videoHeight: 1920, startedAt: Date(timeIntervalSinceNow: -10))
        )
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

    static func body(_ request: URLRequest) -> Data? {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1_024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
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

@MainActor
private final class DevicePlanStub: PlanAPIClient {
    let snapshot: PlanSnapshot
    init() throws {
        struct Fixture: Decodable { let plan: UserPlan; let usage: UserUsage }
        let fixtures = try JSONDecoder().decode([Fixture].self,
            from: ContractTestFixtures.data(named: "plan-usage-processing.v1"))
        let glow = try XCTUnwrap(fixtures.first { $0.plan.plan == "glow" })
        snapshot = PlanSnapshot(plan: glow.plan, usage: glow.usage)
    }
    func userPlan(accessToken: String) async throws -> UserPlan { snapshot.plan }
    func userUsage(accessToken: String) async throws -> UserUsage { snapshot.usage }
}
