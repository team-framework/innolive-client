import XCTest
@preconcurrency import LiveKitWebRTC
@testable import InnoLive

@MainActor
final class VideoUplinkSendPolicyTests: XCTestCase {
    func testServerAIPolicyMatchesAndroidValuesPerResolution() throws {
        let hd = try XCTUnwrap(VideoUplinkSendPolicy.forSession(.serverAI, resolution: .hd720))
        XCTAssertEqual(hd.startBitrateBps, 2_000_000)
        XCTAssertEqual(hd.minBitrateBps, 1_500_000)
        XCTAssertEqual(hd.maxBitrateBps, 3_000_000)
        XCTAssertEqual(hd.maxFramerate, 30)
        XCTAssertEqual(hd.degradationPreference, .maintainResolution)

        let fhd = try XCTUnwrap(VideoUplinkSendPolicy.forSession(.serverAI, resolution: .fhd))
        XCTAssertEqual(fhd.startBitrateBps, 4_000_000)
        XCTAssertEqual(fhd.minBitrateBps, 3_000_000)
        XCTAssertEqual(fhd.maxBitrateBps, 6_000_000)
        XCTAssertEqual(fhd.maxFramerate, 30)
        XCTAssertEqual(fhd.degradationPreference, .maintainResolution)
    }

    func testOnDeviceServerEgressUsesServerAIValues() {
        for resolution in [VideoUplinkResolution.hd720, .fhd] {
            XCTAssertEqual(VideoUplinkSendPolicy.forSession(.onDeviceServerEgress, resolution: resolution),
                           VideoUplinkSendPolicy.forSession(.serverAI, resolution: resolution))
        }
    }

    func testDirectPublishSessionKeepsLibraryDefaults() {
        XCTAssertNil(VideoUplinkSendPolicy.forSession(.onDeviceDirectPublish, resolution: .hd720))
        XCTAssertNil(VideoUplinkSendPolicy.forSession(.onDeviceDirectPublish, resolution: .fhd))
    }

    func testResolutionAcceptsOnlyServerValues() {
        XCTAssertEqual(VideoUplinkResolution(serverValue: "720p"), .hd720)
        XCTAssertEqual(VideoUplinkResolution(serverValue: "fhd"), .fhd)
        XCTAssertNil(VideoUplinkResolution(serverValue: nil))
        XCTAssertNil(VideoUplinkResolution(serverValue: "1080p"))
    }

    func testCredentialsMapToSessionMode() {
        var credentials = WebRTCSessionCredentials(sessionID: "s", ownerToken: "o")
        XCTAssertEqual(credentials.uplinkSessionMode, .serverAI)
        credentials.processingMode = .onDevice
        XCTAssertEqual(credentials.uplinkSessionMode, .onDeviceServerEgress)
        credentials.isDirectPublish = true
        XCTAssertEqual(credentials.uplinkSessionMode, .onDeviceDirectPublish)
    }

    func testConfigureWritesPolicyToEveryEncoding() {
        let parameters = parametersWithEncodings(2)
        VideoUplinkSendPolicy.configure(parameters, policy: VideoUplinkSendPolicy.forSession(.serverAI, resolution: .fhd),
                                        directPublishLimited: false)
        XCTAssertEqual(parameters.degradationPreference?.intValue, LKRTCDegradationPreference.maintainResolution.rawValue)
        for encoding in parameters.encodings {
            XCTAssertEqual(encoding.minBitrateBps?.intValue, 3_000_000)
            XCTAssertEqual(encoding.maxBitrateBps?.intValue, 6_000_000)
            XCTAssertEqual(encoding.maxFramerate?.intValue, 30)
            XCTAssertEqual(encoding.scaleResolutionDownBy?.doubleValue, 1.0)
        }
    }

    func testDirectPublishLimitKeepsExistingValuesAndOverridesPolicy() throws {
        let parameters = parametersWithEncodings(1)
        let policy = VideoUplinkSendPolicy.forSession(.serverAI, resolution: .fhd)
        VideoUplinkSendPolicy.configure(parameters, policy: policy, directPublishLimited: false)
        VideoUplinkSendPolicy.configure(parameters, policy: policy, directPublishLimited: true)
        let encoding = try XCTUnwrap(parameters.encodings.first)
        XCTAssertEqual(encoding.maxBitrateBps?.intValue, 300_000)
        XCTAssertEqual(encoding.maxFramerate?.intValue, 15)
        // 최소값이 남으면 상한 300kbps보다 커져 sender가 파라미터를 거부한다.
        XCTAssertNil(encoding.minBitrateBps)
        XCTAssertNil(encoding.scaleResolutionDownBy)
        XCTAssertNil(parameters.degradationPreference)
    }

    func testReleasingDirectPublishLimitRestoresPolicyOrDefaults() throws {
        let parameters = parametersWithEncodings(1)
        let encoding = try XCTUnwrap(parameters.encodings.first)
        VideoUplinkSendPolicy.configure(parameters, policy: nil, directPublishLimited: true)
        VideoUplinkSendPolicy.configure(parameters, policy: nil, directPublishLimited: false)
        XCTAssertNil(encoding.maxBitrateBps)
        XCTAssertNil(encoding.maxFramerate)
        XCTAssertNil(encoding.minBitrateBps)
        XCTAssertNil(parameters.degradationPreference)

        VideoUplinkSendPolicy.configure(parameters, policy: nil, directPublishLimited: true)
        VideoUplinkSendPolicy.configure(parameters, policy: VideoUplinkSendPolicy.forSession(.serverAI, resolution: .hd720),
                                        directPublishLimited: false)
        XCTAssertEqual(encoding.minBitrateBps?.intValue, 1_500_000)
        XCTAssertEqual(encoding.maxBitrateBps?.intValue, 3_000_000)
        XCTAssertEqual(encoding.maxFramerate?.intValue, 30)
    }

    /// 헤더가 아니라 실제 libwebrtc sender가 값을 받아들이는지, offer/answer 뒤에도 남는지 확인한다.
    func testLiveKitSenderKeepsPolicyThroughNegotiationAndResolutionChange() async throws {
        _ = LKRTCInitializeSSL()
        let uplink = WebRTCVideoUplink()
        let factory = uplink.peerConnectionFactory
        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        let constraints = LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let sender = try XCTUnwrap(factory.peerConnection(with: configuration, constraints: constraints, delegate: nil))
        let receiver = try XCTUnwrap(factory.peerConnection(with: configuration, constraints: constraints, delegate: nil))
        defer { sender.close(); receiver.close() }
        let track = factory.videoTrack(with: factory.videoSource(), trackId: "policy-probe")
        let settings = LKRTCRtpTransceiverInit()
        settings.direction = .sendOnly
        let transceiver = try XCTUnwrap(sender.addTransceiver(with: track, init: settings))

        uplink.peerConnection = sender
        uplink.videoSender = transceiver.sender
        uplink.credentials = WebRTCSessionCredentials(sessionID: "s", ownerToken: "o", processingMode: .onDevice,
                                                      broadcastResolution: .hd720)
        uplink.applyVideoSendPolicy(startingConnection: true)
        XCTAssertEqual(uplink.applyBandwidthEstimate(startingConnection: true), true)
        assertSender(transceiver.sender, minBps: 1_500_000, maxBps: 3_000_000, maxFps: 30, maintainsResolution: true)

        let offer = try await Self.description(sender, answer: false, constraints: constraints)
        try await Self.setDescription(sender, description: offer, local: true)
        try await Self.setDescription(receiver, description: offer, local: false)
        let answer = try await Self.description(receiver, answer: true, constraints: constraints)
        try await Self.setDescription(receiver, description: answer, local: true)
        try await Self.setDescription(sender, description: answer, local: false)
        assertSender(transceiver.sender, minBps: 1_500_000, maxBps: 3_000_000, maxFps: 30, maintainsResolution: true)

        uplink.setBroadcastResolution(.fhd)
        XCTAssertEqual(uplink.applyBandwidthEstimate(startingConnection: false), true)
        assertSender(transceiver.sender, minBps: 3_000_000, maxBps: 6_000_000, maxFps: 30, maintainsResolution: true)

        uplink.setDirectPublishBitrateLimited(true)
        assertSender(transceiver.sender, minBps: nil, maxBps: 300_000, maxFps: 15, maintainsResolution: false)
        uplink.setDirectPublishBitrateLimited(false)
        assertSender(transceiver.sender, minBps: 3_000_000, maxBps: 6_000_000, maxFps: 30, maintainsResolution: true)

        uplink.peerConnection = nil
        uplink.videoSender = nil
        withExtendedLifetime((factory, track)) {}
    }

    private func parametersWithEncodings(_ count: Int) -> LKRTCRtpParameters {
        let parameters = LKRTCRtpParameters()
        parameters.encodings = (0..<count).map { _ in LKRTCRtpEncodingParameters() }
        return parameters
    }

    private func assertSender(_ sender: LKRTCRtpSender, minBps: Int?, maxBps: Int, maxFps: Int, maintainsResolution: Bool,
                              file: StaticString = #filePath, line: UInt = #line) {
        let parameters = sender.parameters
        guard let encoding = parameters.encodings.first else {
            return XCTFail("video sender has no encoding", file: file, line: line)
        }
        XCTAssertEqual(encoding.minBitrateBps?.intValue, minBps, file: file, line: line)
        XCTAssertEqual(encoding.maxBitrateBps?.intValue, maxBps, file: file, line: line)
        XCTAssertEqual(encoding.maxFramerate?.intValue, maxFps, file: file, line: line)
        XCTAssertEqual(parameters.degradationPreference?.intValue == LKRTCDegradationPreference.maintainResolution.rawValue,
                       maintainsResolution, file: file, line: line)
    }

    private static func description(_ peer: LKRTCPeerConnection, answer: Bool, constraints: LKRTCMediaConstraints) async throws -> LKRTCSessionDescription {
        try await withCheckedThrowingContinuation { continuation in
            let completion: @Sendable (LKRTCSessionDescription?, Error?) -> Void = { description, error in
                if let error { continuation.resume(throwing: error) }
                else if let description { continuation.resume(returning: description) }
                else { continuation.resume(throwing: NSError(domain: "VideoUplinkSendPolicyTests", code: 1)) }
            }
            if answer { peer.answer(for: constraints, completionHandler: completion) }
            else { peer.offer(for: constraints, completionHandler: completion) }
        }
    }

    private static func setDescription(_ peer: LKRTCPeerConnection, description: LKRTCSessionDescription, local: Bool) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let completion: @Sendable (Error?) -> Void = { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
            if local { peer.setLocalDescription(description, completionHandler: completion) }
            else { peer.setRemoteDescription(description, completionHandler: completion) }
        }
    }
}
