import XCTest

@testable import InnoLive

final class CameraZoomTests: XCTestCase {
    func testClampedFactorStaysInsideDeviceRange() {
        XCTAssertEqual(CameraZoom.clamped(3, min: 1, max: 8), 3)
        XCTAssertEqual(CameraZoom.clamped(0.2, min: 0.5, max: 8), 0.5)
        XCTAssertEqual(CameraZoom.clamped(12, min: 1, max: 8), 8)
    }

    func testSwitchResetUsesOneTimesInsideDeviceRange() {
        XCTAssertEqual(CameraZoom.factorAfterSwitchReset(min: 0.5, max: 8), 1)
        XCTAssertEqual(CameraZoom.factorAfterSwitchReset(min: 1, max: 6), 1)
    }

    func testDefaultZoomStaysOneTimesWhenHalfTimesIsAvailable() {
        let factor = CameraZoom.factorAfterSwitchReset(min: 0.5, max: 16)
        XCTAssertEqual(factor, 1)
        XCTAssertNotEqual(factor, 0.5)
    }

    func testDisplayRangeAllowsHalfTimesWithoutUsingCollapsedVirtualMaximum() {
        XCTAssertEqual(
            CameraZoom.displayRange(hasHalfTimes: true, activeMin: 1, activeMax: 8),
            0.5...8
        )
        XCTAssertEqual(
            CameraZoom.displayRange(hasHalfTimes: true, activeMin: 0.5, activeMax: 0.5),
            0.5...16
        )
        XCTAssertEqual(
            CameraZoom.displayRange(hasHalfTimes: false, activeMin: 1, activeMax: 6),
            1...6
        )
    }

    func testPinchRequestCanGoToHalfTimesEvenWhenCurrentDeviceMinimumIsOne() {
        XCTAssertEqual(
            CameraZoom.requestedPinchFactor(fromPinchStart: 1, magnification: 0.5),
            0.5
        )
        XCTAssertEqual(
            CameraZoom.requestedPinchFactor(fromPinchStart: 1, magnification: 2),
            2
        )
    }

    func testOneTimesUsesWideCameraNotVirtualCamera() {
        let plan = CameraZoom.plan(
            requestedFactor: 1,
            currentDeviceID: "wide",
            wide: .init(id: "wide", min: 1, max: 8),
            virtual: .init(id: "dual-wide", min: 0.5, max: 8)
        )

        XCTAssertEqual(plan.deviceID, "wide")
        XCTAssertEqual(plan.factor, 1)
    }

    func testHalfTimesUsesVirtualCamera() {
        let plan = CameraZoom.plan(
            requestedFactor: 0.5,
            currentDeviceID: "wide",
            wide: .init(id: "wide", min: 1, max: 8),
            virtual: .init(id: "dual-wide", min: 0.5, max: 8)
        )

        XCTAssertEqual(plan.deviceID, "dual-wide")
        XCTAssertEqual(plan.factor, 0.5)
    }

    func testReturningToOneTimesLeavesVirtualCamera() {
        let plan = CameraZoom.plan(
            requestedFactor: 1,
            currentDeviceID: "dual-wide",
            wide: .init(id: "wide", min: 1, max: 8),
            virtual: .init(id: "dual-wide", min: 0.5, max: 8)
        )

        XCTAssertEqual(plan.deviceID, "wide")
        XCTAssertEqual(plan.factor, 1)
    }

    func testSwitchResetClampsOneTimesToDeviceLimits() {
        XCTAssertEqual(CameraZoom.factorAfterSwitchReset(min: 1.2, max: 5), 1.2)
        XCTAssertEqual(CameraZoom.factorAfterSwitchReset(min: 0.5, max: 0.8), 0.8)
    }

    func testPinchCanReachHalfTimesWhenDeviceMinimumAllowsIt() {
        XCTAssertEqual(
            CameraZoom.factor(fromPinchStart: 1, magnification: 0.5, min: 0.5, max: 8),
            0.5
        )
        XCTAssertEqual(CameraZoom.steppedDown(from: 1, min: 0.5, max: 8), 0.5)
    }

    func testPinchFactorMultipliesStartZoomThenClamps() {
        XCTAssertEqual(
            CameraZoom.factor(fromPinchStart: 2, magnification: 1.5, min: 1, max: 8),
            3
        )
        XCTAssertEqual(
            CameraZoom.factor(fromPinchStart: 2, magnification: 0.1, min: 1, max: 8),
            1
        )
        XCTAssertEqual(
            CameraZoom.factor(fromPinchStart: 2, magnification: 10, min: 1, max: 8),
            8
        )
    }

    func testAccessibilityStepMovesHalfTimesAndClamps() {
        XCTAssertEqual(CameraZoom.steppedUp(from: 1, min: 1, max: 8), 1.5)
        XCTAssertEqual(CameraZoom.steppedDown(from: 1.5, min: 1, max: 8), 1)
        XCTAssertEqual(CameraZoom.steppedUp(from: 8, min: 1, max: 8), 8)
        XCTAssertEqual(CameraZoom.steppedDown(from: 1, min: 1, max: 8), 1)
    }
}
