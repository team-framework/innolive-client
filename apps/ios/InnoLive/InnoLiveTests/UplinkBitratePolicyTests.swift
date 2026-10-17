import XCTest

@testable import InnoLive

final class UplinkBitratePolicyTests: XCTestCase {
    func testServerProcessingMatchesAndroidBudgetPerResolution() {
        for quality in [CameraQualityPreset.fullHD30, .fullHD24] {
            XCTAssertEqual(
                UplinkBitratePolicy.forUplink(processingMode: .server, quality: quality),
                UplinkBitratePolicy(startBps: 4_000_000, maxBps: 6_000_000)
            )
        }
        for quality in [CameraQualityPreset.hd30, .hd24] {
            XCTAssertEqual(
                UplinkBitratePolicy.forUplink(processingMode: .server, quality: quality),
                UplinkBitratePolicy(startBps: 2_000_000, maxBps: 3_000_000)
            )
        }
    }

    func testOnDeviceKeepsDirectPublishLimitUntouched() {
        for quality in CameraQualityPreset.allCases {
            XCTAssertNil(UplinkBitratePolicy.forUplink(processingMode: .onDevice, quality: quality))
        }
    }

    func testMissingCaptureQualityKeepsLibraryDefaults() {
        XCTAssertNil(UplinkBitratePolicy.forUplink(processingMode: .server, quality: nil))
    }
}
