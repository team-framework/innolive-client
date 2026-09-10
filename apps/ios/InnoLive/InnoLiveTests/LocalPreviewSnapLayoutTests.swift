import XCTest

@testable import InnoLive

final class LocalPreviewSnapLayoutTests: XCTestCase {
    func testTopLeadingOriginUsesPadding() {
        let layout = makeLayout()
        let origin = layout.origin(for: .topLeading)

        XCTAssertEqual(origin.x, 24)
        XCTAssertEqual(origin.y, 8)
    }

    func testTopTrailingOriginSitsLeftOfReservedCameraCluster() {
        let layout = makeLayout()
        let origin = layout.origin(for: .topTrailing)
        let previewRight = origin.x + layout.previewSize.width
        let cameraLeft = layout.containerSize.width
            - layout.horizontalPadding
            - layout.topTrailingReserved.width

        XCTAssertEqual(origin.y, 8)
        XCTAssertEqual(previewRight, cameraLeft - layout.cornerSpacing)
        XCTAssertGreaterThanOrEqual(origin.x, layout.horizontalPadding)
    }

    func testBottomOriginsSitAboveReservedControls() {
        let layout = makeLayout()
        let leading = layout.origin(for: .bottomLeading)
        let trailing = layout.origin(for: .bottomTrailing)
        let maxY = layout.containerSize.height
            - layout.bottomPadding
            - layout.bottomReservedHeight
            - layout.previewSize.height

        XCTAssertEqual(leading.x, 24)
        XCTAssertEqual(trailing.x, layout.containerSize.width - 24 - layout.previewSize.width)
        XCTAssertEqual(leading.y, maxY)
        XCTAssertEqual(trailing.y, maxY)
    }

    func testNearestCornerFromTopLeftIsTopLeading() {
        let layout = makeLayout()
        XCTAssertEqual(layout.nearestCorner(toPreviewOrigin: .zero), .topLeading)
    }

    func testNearestCornerFromTopRightIsTopTrailing() {
        let layout = makeLayout()
        let origin = CGPoint(x: layout.containerSize.width, y: 0)
        XCTAssertEqual(layout.nearestCorner(toPreviewOrigin: origin), .topTrailing)
    }

    func testNearestCornerFromBottomLeftIsBottomLeading() {
        let layout = makeLayout()
        let origin = CGPoint(x: 0, y: layout.containerSize.height)
        XCTAssertEqual(layout.nearestCorner(toPreviewOrigin: origin), .bottomLeading)
    }

    func testNearestCornerFromBottomRightIsBottomTrailing() {
        let layout = makeLayout()
        let origin = CGPoint(
            x: layout.containerSize.width,
            y: layout.containerSize.height
        )
        XCTAssertEqual(layout.nearestCorner(toPreviewOrigin: origin), .bottomTrailing)
    }

    func testEqualDistancePrefersTopLeading() {
        let layout = makeLayout()
        let topLeading = layout.origin(for: .topLeading)
        let topTrailing = layout.origin(for: .topTrailing)
        let midpoint = CGPoint(
            x: (topLeading.x + topTrailing.x) / 2,
            y: topLeading.y
        )

        XCTAssertEqual(layout.nearestCorner(toPreviewOrigin: midpoint), .topLeading)
    }

    func testOriginsStayNonNegativeOnNarrowContainer() {
        let layout = makeLayout(
            containerSize: CGSize(width: 160, height: 400),
            previewSize: CGSize(width: 120, height: 213)
        )

        for corner in LocalPreviewCorner.allCases {
            let origin = layout.origin(for: corner)
            XCTAssertGreaterThanOrEqual(origin.x, 0, "\(corner) x")
            XCTAssertGreaterThanOrEqual(origin.y, 0, "\(corner) y")
            XCTAssertLessThanOrEqual(
                origin.x + layout.previewSize.width,
                layout.containerSize.width,
                "\(corner) right edge"
            )
            XCTAssertLessThanOrEqual(
                origin.y + layout.previewSize.height,
                layout.containerSize.height,
                "\(corner) bottom edge"
            )
        }
    }

    func testIdleHomeShowsCameraSessionPreview() {
        XCTAssertEqual(
            LocalPreviewPresentation.current(
                isHomeVisible: true,
                previewTransition: .none,
                isCapturingMedia: false,
                isReleasingCamera: false
            ),
            .cameraSession
        )
    }

    func testCapturingMediaShowsWebRTCLocalPreview() {
        XCTAssertEqual(
            LocalPreviewPresentation.current(
                isHomeVisible: true,
                previewTransition: .none,
                isCapturingMedia: true,
                isReleasingCamera: false
            ),
            .webrtcLocal
        )
    }

    func testPreviewIsHiddenWhenHomeIsNotVisible() {
        XCTAssertEqual(
            LocalPreviewPresentation.current(
                isHomeVisible: false,
                previewTransition: .none,
                isCapturingMedia: true,
                isReleasingCamera: false
            ),
            .hidden
        )
    }

    func testPreviewIsHiddenDuringConnectionTransition() {
        XCTAssertEqual(
            LocalPreviewPresentation.current(
                isHomeVisible: true,
                previewTransition: .starting,
                isCapturingMedia: false,
                isReleasingCamera: false
            ),
            .hidden
        )
    }

    func testPreviewIsHiddenWhileReleasingCamera() {
        XCTAssertEqual(
            LocalPreviewPresentation.current(
                isHomeVisible: true,
                previewTransition: .none,
                isCapturingMedia: true,
                isReleasingCamera: true
            ),
            .hidden
        )
    }

    func testClampedOriginStaysInsideContainer() {
        let layout = makeLayout()
        let clamped = layout.clampedOrigin(CGPoint(x: -40, y: 2000))

        XCTAssertEqual(clamped.x, 0)
        XCTAssertEqual(
            clamped.y,
            layout.containerSize.height - layout.previewSize.height
        )
    }

    private func makeLayout(
        containerSize: CGSize = CGSize(width: 390, height: 844),
        previewSize: CGSize = CGSize(width: 120, height: 120 / (9.0 / 16.0))
    ) -> LocalPreviewSnapLayout {
        LocalPreviewSnapLayout(
            containerSize: containerSize,
            previewSize: previewSize,
            horizontalPadding: 24,
            topPadding: 8,
            bottomPadding: 12,
            topTrailingReserved: CGSize(width: 44, height: 44),
            bottomReservedHeight: 56,
            cornerSpacing: 12
        )
    }
}
