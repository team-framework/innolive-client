import XCTest

@testable import InnoLive

final class CameraInputSwipeTests: XCTestCase {
    func testLeftAndRightSwipesBeyondThresholdSwitch() {
        XCTAssertTrue(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 60, height: 0)))
        XCTAssertTrue(CameraInputSwipe.shouldSwitch(translation: CGSize(width: -60, height: 10)))
        XCTAssertTrue(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 80, height: 79)))
    }

    func testBelowThresholdOrVerticalDominantDoesNotSwitch() {
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 59, height: 0)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: -59.9, height: 0)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 80, height: 80)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: CGSize(width: 80, height: 100)))
        XCTAssertFalse(CameraInputSwipe.shouldSwitch(translation: .zero))
    }
}
