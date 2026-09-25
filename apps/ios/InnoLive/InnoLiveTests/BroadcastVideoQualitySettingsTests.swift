import AVFoundation
import XCTest

@testable import InnoLive

final class BroadcastVideoQualitySettingsTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "BroadcastVideoQualitySettingsTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults?.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testNewInstallationUsesStableDefaultSettings() {
        let settings = BroadcastVideoQualitySettings.load(from: defaults)

        XCTAssertTrue(settings.stabilizationEnabled)
        XCTAssertEqual(settings.exposureEV, 0)
        XCTAssertEqual(settings.warmth, 0)
        XCTAssertEqual(settings.saturation, 1)
    }

    func testSavedQualitySettingsSurviveReload() {
        let settings = BroadcastVideoQualitySettings(
            stabilizationEnabled: false,
            exposureEV: 1.25,
            warmth: -0.4,
            saturation: 1.6
        )
        settings.save(to: defaults)

        XCTAssertEqual(BroadcastVideoQualitySettings.load(from: defaults), settings)
    }

    func testCorruptAndOutOfRangePreferencesAreNormalized() {
        BroadcastVideoQualitySettings(
            stabilizationEnabled: true,
            exposureEV: 9,
            warmth: -9,
            saturation: 9
        ).save(to: defaults)

        let settings = BroadcastVideoQualitySettings.load(from: defaults)
        XCTAssertEqual(settings.exposureEV, 2)
        XCTAssertEqual(settings.warmth, -1)
        XCTAssertEqual(settings.saturation, 2)
    }

    func testStabilizationPrefersLowLatencyThenStandard() {
        XCTAssertEqual(
            VideoQualityCapturePolicy.stabilizationMode(
                enabled: true, supportsLowLatency: true, supportsStandard: true
            ),
            .lowLatency
        )
        XCTAssertEqual(
            VideoQualityCapturePolicy.stabilizationMode(
                enabled: true, supportsLowLatency: false, supportsStandard: true
            ),
            .standard
        )
    }

    func testStabilizationOffAndUnsupportedDoNotSelectAnUnavailableMode() {
        XCTAssertEqual(
            VideoQualityCapturePolicy.stabilizationMode(
                enabled: false, supportsLowLatency: true, supportsStandard: true
            ),
            .off
        )
        XCTAssertNil(
            VideoQualityCapturePolicy.stabilizationMode(
                enabled: true, supportsLowLatency: false, supportsStandard: false
            )
        )
    }

    func testStabilizationStatusUsesActiveModeRatherThanRequestedMode() {
        XCTAssertEqual(
            VideoQualityCapturePolicy.stabilizationStatus(
                enabled: true, requestedMode: .lowLatency, activeMode: .standard
            ),
            .active(.standard)
        )
        XCTAssertEqual(
            VideoQualityCapturePolicy.stabilizationStatus(
                enabled: true, requestedMode: .lowLatency, activeMode: .off
            ),
            .unsupported
        )
        XCTAssertEqual(
            VideoQualityCapturePolicy.stabilizationStatus(
                enabled: false, requestedMode: .off, activeMode: .off
            ),
            .inactive
        )
    }

    func testExposureClampsToDeviceAndAppRange() {
        XCTAssertEqual(VideoQualityCapturePolicy.clampedExposureEV(1.5, min: -1, max: 1), 1)
        XCTAssertEqual(VideoQualityCapturePolicy.clampedExposureEV(-3, min: -4, max: 4), -2)
        XCTAssertEqual(VideoQualityCapturePolicy.clampedExposureEV(0.5, min: 0, max: 0), 0)
    }
}
