import XCTest

@testable import InnoLive

final class VideoUplinkCodecPreferenceTests: XCTestCase {
    private nonisolated struct Codec: Equatable {
        let name: String
        var parameters: [String: String] = [:]

        static func h264(_ profileLevelID: String, packetizationMode: String = "1") -> Codec {
            Codec(name: "H264", parameters: [
                "level-asymmetry-allowed": "1",
                "packetization-mode": packetizationMode,
                "profile-level-id": profileLevelID
            ])
        }
    }

    private let constrainedHigh = Codec.h264("640c34")
    private let baseline = Codec.h264("42e034")
    private let vp8 = Codec(name: "VP8")
    private let vp9 = Codec(name: "VP9")
    private let av1 = Codec(name: "AV1")

    private func ordered(_ codecs: [Codec], _ mode: VideoUplinkSessionMode) -> [Codec]? {
        VideoUplinkCodecPreference.ordered(codecs, for: mode, name: \.name, parameters: \.parameters)
    }

    private func isServerBaseline(_ codec: Codec) -> Bool {
        VideoUplinkCodecPreference.isServerH264Baseline(name: codec.name, parameters: codec.parameters)
    }

    func testServerAIPutsBaselineBeforeVP8AndConstrainedHigh() {
        // LiveKitWebRTC 기본 순서: Constrained High, Constrained Baseline, VP8, VP9, AV1.
        XCTAssertEqual(ordered([constrainedHigh, baseline, vp8, vp9, av1], .serverAI),
                       [baseline, vp8, constrainedHigh, vp9, av1])
    }

    func testOnDeviceSessionsKeepVP8First() {
        for mode in [VideoUplinkSessionMode.onDeviceServerEgress, .onDeviceDirectPublish] {
            XCTAssertEqual(ordered([constrainedHigh, baseline, vp8, vp9], mode), [vp8, constrainedHigh, baseline, vp9])
        }
    }

    func testBaselineMustMatchServerProfileAndCarryFHD30() {
        XCTAssertTrue(isServerBaseline(.h264("42e028")))
        XCTAssertTrue(isServerBaseline(.h264("42E029")))
        XCTAssertTrue(isServerBaseline(baseline))
        XCTAssertFalse(isServerBaseline(.h264("42e01f")))
        XCTAssertFalse(isServerBaseline(constrainedHigh))
        XCTAssertFalse(isServerBaseline(.h264("420034")))
        XCTAssertFalse(isServerBaseline(.h264("42e034", packetizationMode: "0")))
        XCTAssertFalse(isServerBaseline(Codec(name: "H264")))
        XCTAssertFalse(isServerBaseline(Codec(name: "VP8", parameters: baseline.parameters)))
    }

    func testServerAIFallsBackToVP8WithoutUsableBaseline() {
        let lowLevel = Codec.h264("42e01f")
        XCTAssertEqual(ordered([constrainedHigh, lowLevel, vp8, vp9], .serverAI), [vp8, constrainedHigh, lowLevel, vp9])
    }

    func testRequiresBaselineOrVP8() {
        XCTAssertEqual(ordered([constrainedHigh, baseline, vp9], .serverAI), [baseline, constrainedHigh, vp9])
        XCTAssertNil(ordered([constrainedHigh, baseline, vp9], .onDeviceServerEgress))
        XCTAssertNil(ordered([constrainedHigh, vp9], .serverAI))
        XCTAssertNil(ordered([], .serverAI))
    }
}
