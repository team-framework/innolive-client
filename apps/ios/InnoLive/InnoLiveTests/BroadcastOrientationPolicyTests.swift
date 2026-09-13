import AVFoundation
import UIKit
import XCTest

@testable import InnoLive

final class BroadcastOrientationPolicyTests: XCTestCase {
    func testPortraitPreviewSizeUsesShortWidth() {
        let size = BroadcastOrientationPolicy.previewSize(isLandscape: false)

        XCTAssertEqual(size.width, 120)
        XCTAssertEqual(size.height, 120 / (9.0 / 16.0), accuracy: 0.01)
    }

    func testLandscapePreviewSizeSwapsSides() {
        let size = BroadcastOrientationPolicy.previewSize(isLandscape: true)

        XCTAssertEqual(size.width, 120 / (9.0 / 16.0), accuracy: 0.01)
        XCTAssertEqual(size.height, 120)
    }

    func testUnlockedPreviewFollowsContainerAspect() {
        let portrait = BroadcastOrientationPolicy.previewSize(
            containerSize: CGSize(width: 390, height: 844),
            lockedOrientation: nil
        )
        let landscape = BroadcastOrientationPolicy.previewSize(
            containerSize: CGSize(width: 844, height: 390),
            lockedOrientation: nil
        )

        XCTAssertEqual(portrait.width, 120)
        XCTAssertEqual(landscape.height, 120)
        XCTAssertGreaterThan(landscape.width, landscape.height)
    }

    func testLockedPreviewKeepsLockedAspectWhenContainerDisagrees() {
        let size = BroadcastOrientationPolicy.previewSize(
            containerSize: CGSize(width: 844, height: 390),
            lockedOrientation: .portrait
        )

        XCTAssertEqual(size.width, 120)
        XCTAssertGreaterThan(size.height, size.width)
    }

    func testIPhoneSupportedMaskOmitsUpsideDownUntilLocked() {
        let unlocked = BroadcastOrientationPolicy.supportedMask(
            lockedOrientation: nil,
            idiom: .phone
        )
        let lockedUpsideDown = BroadcastOrientationPolicy.supportedMask(
            lockedOrientation: .portraitUpsideDown,
            idiom: .phone
        )

        XCTAssertEqual(unlocked, [.portrait, .landscapeLeft, .landscapeRight])
        XCTAssertEqual(lockedUpsideDown, .portrait)
    }

    func testIPadSupportedMaskIncludesUpsideDown() {
        let unlocked = BroadcastOrientationPolicy.supportedMask(
            lockedOrientation: nil,
            idiom: .pad
        )
        let locked = BroadcastOrientationPolicy.supportedMask(
            lockedOrientation: .portraitUpsideDown,
            idiom: .pad
        )

        XCTAssertTrue(unlocked.contains(.portraitUpsideDown))
        XCTAssertEqual(locked, .portraitUpsideDown)
    }

    func testPrefersInterfaceOrientationLockedOnlyWhileHeld() {
        XCTAssertFalse(BroadcastOrientationPolicy.prefersInterfaceOrientationLocked(nil))
        XCTAssertTrue(BroadcastOrientationPolicy.prefersInterfaceOrientationLocked(.landscapeLeft))
    }

    func testWebRTCRotationMatchesCameraCapturerMapping() {
        let cases: [(BroadcastInterfaceOrientation, AVCaptureDevice.Position, BroadcastVideoRotation)] = [
            (.portrait, .front, .rotation90),
            (.portrait, .back, .rotation90),
            (.portraitUpsideDown, .front, .rotation270),
            (.portraitUpsideDown, .back, .rotation270),
            (.landscapeLeft, .front, .rotation0),
            (.landscapeLeft, .back, .rotation180),
            (.landscapeRight, .front, .rotation180),
            (.landscapeRight, .back, .rotation0),
        ]

        for item in cases {
            XCTAssertEqual(
                BroadcastOrientationPolicy.videoRotation(
                    interfaceOrientation: item.0,
                    cameraPosition: item.1
                ),
                item.2,
                "\(item.0) \(item.1.rawValue)"
            )
        }
    }

    func testPreviewRotationAnglesFollowInterfaceOrientation() {
        XCTAssertEqual(BroadcastOrientationPolicy.previewRotationAngle(for: .portrait), 90)
        XCTAssertEqual(BroadcastOrientationPolicy.previewRotationAngle(for: .portraitUpsideDown), 270)
        XCTAssertEqual(BroadcastOrientationPolicy.previewRotationAngle(for: .landscapeLeft), 0)
        XCTAssertEqual(BroadcastOrientationPolicy.previewRotationAngle(for: .landscapeRight), 180)
    }

    func testLifecycleKeepsPreparedUnlockedAndLocksAtGoLive() {
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .homeAppeared), .none)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .cameraUplinkConnected), .none)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .prepareRequested), .none)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .preparedWaiting), .none)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .goLiveRequested), .lockToCurrentIfUnlocked)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .goLiveRetryInOngoingOperation), .keep)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .live), .keep)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .paused), .keep)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .reconnecting), .keep)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .stopping), .keep)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .stopFailedWhileBroadcasting), .keep)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .goLiveFailedBackToPrepared), .release)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .stopSucceeded), .release)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .reset), .release)
        XCTAssertEqual(BroadcastOrientationPolicy.action(for: .finalFailure), .release)
    }
}
