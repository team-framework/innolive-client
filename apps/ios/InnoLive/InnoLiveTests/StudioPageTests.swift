import XCTest
@testable import InnoLive

final class StudioPageTests: XCTestCase {
    func testPageOrderAndEdgesForAccessibilityNavigation() {
        XCTAssertEqual(StudioPage.allCases, [.preview, .basic, .details])
        XCTAssertEqual(StudioPage.basic.previous, .preview)
        XCTAssertEqual(StudioPage.basic.next, .details)
        XCTAssertEqual(StudioPage.preview.previous, .preview)
        XCTAssertEqual(StudioPage.details.next, .details)
    }

    func testInteractionLockRejectsEveryPageChange() {
        for current in StudioPage.allCases {
            for requested in StudioPage.allCases {
                XCTAssertEqual(StudioPage.destination(from: current, requested: requested, isLocked: true), current)
                XCTAssertEqual(StudioPage.destination(from: current, requested: requested, isLocked: false), requested)
            }
        }
    }
}
