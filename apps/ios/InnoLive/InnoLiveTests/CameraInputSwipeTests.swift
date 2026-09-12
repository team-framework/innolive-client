import XCTest

@testable import InnoLive

final class CameraInputSwipeTests: XCTestCase {
    func testUpAndDownSwipesBeyondThresholdSwitch() {
        XCTAssertTrue(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 0, height: 60)))
        XCTAssertTrue(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 10, height: -60)))
        XCTAssertTrue(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 79, height: 80)))
    }

    func testBelowThresholdEqualityDiagonalOrHorizontalDoesNotSwitch() {
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 0, height: 59)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 0, height: -59.9)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 80, height: 80)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 80, height: 0)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 100, height: 80)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: .zero))
    }
}
