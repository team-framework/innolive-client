import UIKit
import XCTest

@testable import InnoLive

@MainActor
final class BroadcastOrientationLockTests: XCTestCase {
    func testLockCapturesCurrentInterfaceOrientationNotLaterChanges() {
        let current = OrientationSample(.landscapeRight)
        let controller = BroadcastOrientationController(
            currentOrientationProvider: { current.value },
            appliesSceneUpdates: false
        )

        let first = controller.lockToCurrentInterfaceOrientation()
        current.value = .portrait
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
        let current = OrientationSample(.portrait)
        let controller = BroadcastOrientationController(
            currentOrientationProvider: { current.value },
            appliesSceneUpdates: false
        )

        let first = controller.lockToCurrentInterfaceOrientation()
        controller.unlock(generation: first)
        current.value = .landscapeLeft
        let second = controller.lockToCurrentInterfaceOrientation()
        controller.unlock(generation: first)

        XCTAssertEqual(second, controller.lockToCurrentInterfaceOrientation())
        XCTAssertEqual(controller.lockedOrientation, .landscapeLeft)
    }

    func testIsolatedControllerDoesNotChangeHostedWindowMask() {
        guard let window = hostedKeyWindow() else {
            XCTFail("Test host window is required for UIKit orientation integration")
            return
        }
        let before = applicationSupportedMask(for: window)
        let isolated = BroadcastOrientationController(
            currentOrientationProvider: { .landscapeRight },
            appliesSceneUpdates: false
        )

        _ = isolated.lockToCurrentInterfaceOrientation()

        XCTAssertEqual(isolated.supportedInterfaceOrientations, .landscapeRight)
        XCTAssertEqual(applicationSupportedMask(for: window), before)
    }

    func testBridgeControllerUsesAppOwnedLockPreference() {
        let controller = BroadcastOrientationController.shared
        if controller.isLocked {
            controller.unlock()
        }
        let bridge = BroadcastOrientationBridgeController()
        XCTAssertFalse(bridge.prefersInterfaceOrientationLocked)

        let generation = controller.lockToCurrentInterfaceOrientation()
        defer { controller.unlock(generation: generation) }

        XCTAssertEqual(bridge.prefersInterfaceOrientationLocked, controller.isLocked)
        XCTAssertEqual(
            bridge.supportedInterfaceOrientations,
            controller.supportedInterfaceOrientations
        )
        XCTAssertEqual(
            bridge.supportedInterfaceOrientations,
            BroadcastOrientationPolicy.supportedMask(
                lockedOrientation: controller.lockedOrientation,
                idiom: UIDevice.current.userInterfaceIdiom
            )
        )
    }

    func testHostedWindowMaskFollowsSharedControllerLockAndRelease() async throws {
        let controller = BroadcastOrientationController.shared
        if controller.isLocked {
            controller.unlock()
        }

        guard let window = hostedKeyWindow(), let scene = window.windowScene else {
            XCTFail("Test host window is required for UIKit orientation integration")
            return
        }

        let unlockedMask = applicationSupportedMask(for: window)
        XCTAssertTrue(unlockedMask.contains(.portrait))
        XCTAssertTrue(unlockedMask.contains(.landscapeLeft) || unlockedMask.contains(.landscapeRight))

        let generation = controller.lockToCurrentInterfaceOrientation()
        defer { controller.unlock(generation: generation) }

        try await Task.sleep(for: .milliseconds(200))
        let locked = try XCTUnwrap(controller.lockedOrientation)
        let expectedMask = BroadcastOrientationPolicy.supportedMask(
            lockedOrientation: locked,
            idiom: UIDevice.current.userInterfaceIdiom
        )
        XCTAssertEqual(
            applicationSupportedMask(for: window),
            expectedMask
        )

        let oppositeMask: UIInterfaceOrientationMask = locked.isLandscape
            ? .portrait
            : .landscapeRight
        let geometryError = GeometryErrorBox()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: oppositeMask)) { error in
            geometryError.value = error
        }
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertTrue(
            geometryError.value != nil
                || scene.effectiveGeometry.interfaceOrientation == locked.uiInterfaceOrientation,
            "Locked scene must reject or ignore a different orientation"
        )

        controller.unlock(generation: generation)
        XCTAssertEqual(
            applicationSupportedMask(for: window),
            unlockedMask
        )
    }

    private func applicationSupportedMask(for window: UIWindow) -> UIInterfaceOrientationMask {
        let application = UIApplication.shared
        // UIKit uses the delegate override first. UIApplication's method returns
        // the Info.plist default and is only the fallback when no override exists.
        return application.delegate?.application?(
            application,
            supportedInterfaceOrientationsFor: window
        ) ?? application.supportedInterfaceOrientations(for: window)
    }

    private func hostedKeyWindow() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? windows.first
    }
}

@MainActor
private final class OrientationSample {
    var value: BroadcastInterfaceOrientation

    init(_ value: BroadcastInterfaceOrientation) {
        self.value = value
    }
}

private final class GeometryErrorBox: @unchecked Sendable {
    var value: Error?
}
