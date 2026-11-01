import CoreGraphics
import XCTest

@testable import InnoLive

final class OnDeviceMosaicSettingsTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "OnDeviceMosaicSettingsTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults?.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testDefaults() {
        let settings = OnDeviceMosaicSettings()
        XCTAssertEqual(settings.blurStrength, 24)
        XCTAssertEqual(settings.maskMargin, 8)
    }

    func testNormalizedSnapsAndClamps() {
        XCTAssertEqual(OnDeviceMosaicSettings(blurStrength: 1, maskMargin: 0).normalized(),
                       OnDeviceMosaicSettings(blurStrength: 24, maskMargin: 8))
        XCTAssertEqual(OnDeviceMosaicSettings(blurStrength: 99, maskMargin: 99).normalized(),
                       OnDeviceMosaicSettings(blurStrength: 48, maskMargin: 64))
        XCTAssertEqual(OnDeviceMosaicSettings(blurStrength: 36, maskMargin: 20).normalized(),
                       OnDeviceMosaicSettings(blurStrength: 36, maskMargin: 20))
    }

    func testFrom() {
        XCTAssertEqual(OnDeviceMosaicSettings.from(blurRadius: 40, maskMargin: 32),
                       OnDeviceMosaicSettings(blurStrength: 40, maskMargin: 32))
        XCTAssertEqual(OnDeviceMosaicSettings.from(blurRadius: nil, maskMargin: nil),
                       OnDeviceMosaicSettings(blurStrength: 24, maskMargin: 8))
        XCTAssertEqual(OnDeviceMosaicSettings.from(blurRadius: 64, maskMargin: 0),
                       OnDeviceMosaicSettings(blurStrength: 48, maskMargin: 8))
    }

    func testNewInstallationUsesDefaults() {
        XCTAssertEqual(OnDeviceMosaicSettings.load(from: defaults), OnDeviceMosaicSettings())
    }

    func testSavedSettingsSurviveReload() {
        OnDeviceMosaicSettings(blurStrength: 42, maskMargin: 48).save(to: defaults)
        XCTAssertEqual(OnDeviceMosaicSettings.load(from: defaults),
                       OnDeviceMosaicSettings(blurStrength: 42, maskMargin: 48))
    }

    func testOutOfRangeStoredValuesAreNormalizedOnLoad() {
        defaults.set(99, forKey: "com.framework.innolive.on-device-mosaic.blur-strength")
        defaults.set(0, forKey: "com.framework.innolive.on-device-mosaic.mask-margin")
        XCTAssertEqual(OnDeviceMosaicSettings.load(from: defaults),
                       OnDeviceMosaicSettings(blurStrength: 48, maskMargin: 8))
    }

    func testStrengthLevelSnapsToDiscreteSteps() {
        XCTAssertEqual(PrivacyModel.strengthLevel(1), 24)
        XCTAssertEqual(PrivacyModel.strengthLevel(24), 24)
        XCTAssertEqual(PrivacyModel.strengthLevel(36), 36)
        XCTAssertEqual(PrivacyModel.strengthLevel(48), 48)
        XCTAssertEqual(PrivacyModel.strengthLevel(64), 48)
    }

    func testBroadcastBlurSigmaScalesWithStrength() {
        let landscape = CGRect(x: 0, y: 0, width: 1920, height: 1080)
        XCTAssertEqual(PrivacyModel.broadcastBlurSigma(for: landscape, strength: 24), 72)
        XCTAssertEqual(PrivacyModel.broadcastBlurSigma(for: landscape, strength: 36), 108)
        XCTAssertEqual(PrivacyModel.broadcastBlurSigma(for: landscape, strength: 48), 144)
        XCTAssertEqual(PrivacyModel.broadcastBlurSigma(for: landscape, strength: 1), 72)
        XCTAssertEqual(PrivacyModel.broadcastBlurSigma(for: landscape, strength: 64), 144)
        XCTAssertEqual(PrivacyModel.broadcastBlurSigma(
            for: CGRect(x: 0, y: 0, width: 1280, height: 720), strength: 48), 96)
    }

    func testEdgeCellsMapsMarginToCells() {
        XCTAssertEqual(PrivacyMask.edgeCells(width: 1280, height: 720, marginPx: 8), 1)
        XCTAssertEqual(PrivacyMask.edgeCells(width: 1920, height: 1080, marginPx: 8), 1)
        XCTAssertEqual(PrivacyMask.edgeCells(width: 1080, height: 1920, marginPx: 8), 1)
        XCTAssertEqual(PrivacyMask.edgeCells(width: 1920, height: 1080, marginPx: 0), 1)
        XCTAssertEqual(PrivacyMask.edgeCells(width: 1280, height: 720, marginPx: 24), 3)
        XCTAssertEqual(PrivacyMask.edgeCells(width: 1280, height: 720, marginPx: 64), 8)
        XCTAssertEqual(PrivacyMask.edgeCells(width: 1920, height: 1080, marginPx: 64), 5)
        XCTAssertEqual(PrivacyMask.edgeCells(width: 640, height: 360, marginPx: 64), 12)
    }
}
