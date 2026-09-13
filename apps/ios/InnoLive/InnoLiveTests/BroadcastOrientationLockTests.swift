import XCTest

@testable import InnoLive

@MainActor
final class BroadcastOrientationLockTests: XCTestCase {
    func testLockCapturesCurrentInterfaceOrientationNotLaterChanges() {
        var current = BroadcastInterfaceOrientation.landscapeRight
        let controller = BroadcastOrientationController(
            currentOrientationProvider: { current },
            appliesSceneUpdates: false
        )

        let first = controller.lockToCurrentInterfaceOrientation()
        current = .portrait
        let second = controller.lockToCurrentInterfaceOrientation()

        XCTAssertEqual(first, second)
        XCTAssertEqual(controller.lockedOrientation, .landscapeRight)
        XCTAssertTrue(controller.isLocked)
        XCTAssertTrue(controller.prefersInterfaceOrientationLocked)
        XCTAssertEqual(controller.supportedInterfaceOrientations, .landscapeRight)
    }

    func testUnlockRestoresDefaultMask() {
        let controller = BroadcastOrientationController(
            currentOrientationProvider: { .landscapeLeft },
            appliesSceneUpdates: false
        )

        let generation = controller.lockToCurrentInterfaceOrientation()
        controller.unlock(generation: generation)

        XCTAssertNil(controller.lockedOrientation)
        XCTAssertFalse(controller.isLocked)
        XCTAssertFalse(controller.prefersInterfaceOrientationLocked)
    }

    func testStaleUnlockDoesNotClearANewerLock() {
        var current = BroadcastInterfaceOrientation.portrait
        let controller = BroadcastOrientationController(
            currentOrientationProvider: { current },
            appliesSceneUpdates: false
        )

        let first = controller.lockToCurrentInterfaceOrientation()
        controller.unlock(generation: first)
        current = .landscapeLeft
        let second = controller.lockToCurrentInterfaceOrientation()
        controller.unlock(generation: first)

        XCTAssertEqual(second, controller.lockToCurrentInterfaceOrientation())
        XCTAssertEqual(controller.lockedOrientation, .landscapeLeft)
    }
}
