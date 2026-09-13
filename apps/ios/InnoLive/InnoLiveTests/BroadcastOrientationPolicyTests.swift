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

    func testPreviewRotationAnglesAreCameraAware() {
        XCTAssertEqual(
            BroadcastOrientationPolicy.previewRotationAngle(for: .portrait, cameraPosition: .front),
            90
        )
        XCTAssertEqual(
            BroadcastOrientationPolicy.previewRotationAngle(for: .portrait, cameraPosition: .back),
            90
        )
        XCTAssertEqual(
            BroadcastOrientationPolicy.previewRotationAngle(for: .portraitUpsideDown, cameraPosition: .back),
            270
        )
        XCTAssertEqual(
            BroadcastOrientationPolicy.previewRotationAngle(for: .landscapeLeft, cameraPosition: .front),
            0
        )
        XCTAssertEqual(
            BroadcastOrientationPolicy.previewRotationAngle(for: .landscapeLeft, cameraPosition: .back),
            180
        )
        XCTAssertEqual(
            BroadcastOrientationPolicy.previewRotationAngle(for: .landscapeRight, cameraPosition: .front),
            180
        )
        XCTAssertEqual(
            BroadcastOrientationPolicy.previewRotationAngle(for: .landscapeRight, cameraPosition: .back),
            0
        )
    }
}
