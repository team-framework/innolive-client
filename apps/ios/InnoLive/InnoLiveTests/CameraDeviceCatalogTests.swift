import XCTest

@testable import InnoLive

final class CameraDeviceCatalogTests: XCTestCase {
    func testHidesVirtualCamerasSoWideRemainsTheBackCamera() {
        let visible = CameraDeviceCatalog.visibleIDs(
            ["triple", "dual-wide", "wide", "ultra", "tele", "front"],
            hiding: ["triple", "dual-wide"]
        )

        XCTAssertEqual(visible, ["wide", "ultra", "tele", "front"])
    }

    func testKeepsStandaloneCamerasWhenNoVirtualCameraExists() {
        let visible = CameraDeviceCatalog.visibleIDs(
            ["wide", "front", "external"],
            hiding: ["triple", "dual-wide"]
        )

        XCTAssertEqual(visible, ["wide", "front", "external"])
    }

    func testMapsSavedVirtualCameraBackToWide() {
        let resolved = CameraDeviceCatalog.resolvedCameraID(
            savedID: "dual-wide",
            availableIDs: ["wide", "front"],
            virtualIDs: ["dual-wide": "wide"]
        )

        XCTAssertEqual(resolved, "wide")
    }

    func testKeepsSavedWideCamera() {
        let resolved = CameraDeviceCatalog.resolvedCameraID(
            savedID: "wide",
            availableIDs: ["wide", "front"],
            virtualIDs: ["dual-wide": "wide"]
        )

        XCTAssertEqual(resolved, "wide")
    }

    func testNextCameraIsNilWhenNoSourcesExist() {
        XCTAssertNil(CameraDeviceCatalog.nextCameraID(after: nil, in: []))
        XCTAssertNil(CameraDeviceCatalog.nextCameraID(after: "front", in: []))
    }

    func testNextCameraIsNilForASoleSourceIncludingNilAndStaleIDs() {
        let onlyFront = [frontWide]

        XCTAssertNil(CameraDeviceCatalog.nextCameraID(after: "front", in: onlyFront))
        XCTAssertNil(CameraDeviceCatalog.nextCameraID(after: nil, in: onlyFront))
        XCTAssertNil(CameraDeviceCatalog.nextCameraID(after: "missing", in: onlyFront))
    }

    func testNextCameraIsNilWhenCurrentIDIsUnknownEvenIfAlternativesExist() {
        XCTAssertNil(CameraDeviceCatalog.nextCameraID(after: nil, in: [backWide, frontWide]))
    }

    func testStaleCurrentIDSelectsADistinctAvailableSource() {
        XCTAssertEqual(
            CameraDeviceCatalog.nextCameraID(after: "missing", in: [backWide, frontWide]),
            "back-wide"
        )
    }

    func testPrefersOppositeBuiltInWideAngleAmongRearLenses() {
        let sources = [backUltra, backTele, backWide, frontWide]

        XCTAssertEqual(
            CameraDeviceCatalog.nextCameraID(after: "front", in: sources),
            "back-wide"
        )
        XCTAssertEqual(
            CameraDeviceCatalog.nextCameraID(after: "back-wide", in: sources),
            "front"
        )
        XCTAssertEqual(
            CameraDeviceCatalog.nextCameraID(after: "back-ultra", in: sources),
            "front"
        )
    }

    func testFallsBackToOppositeSideWhenWideAngleIsAbsent() {
        XCTAssertEqual(
            CameraDeviceCatalog.nextCameraID(after: "front", in: [backUltra, backTele, frontWide]),
            "back-ultra"
        )
    }

    func testFallsBackToExternalWhenOppositeSideIsAbsent() {
        XCTAssertEqual(
            CameraDeviceCatalog.nextCameraID(after: "front", in: [frontWide, external]),
            "external"
        )
        XCTAssertEqual(
            CameraDeviceCatalog.nextCameraID(after: "external", in: [frontWide, external]),
            "front"
        )
    }

    func testResolvedVirtualCameraCanMoveToOppositeWideAngle() {
        let sources = [backWide, backUltra, frontWide]
        let resolved = CameraDeviceCatalog.resolvedCameraID(
            savedID: "dual-wide",
            availableIDs: sources.map(\.id),
            virtualIDs: ["dual-wide": "back-wide"]
        )

        XCTAssertEqual(resolved, "back-wide")
        XCTAssertEqual(CameraDeviceCatalog.nextCameraID(after: resolved, in: sources), "front")
    }

    private var backWide: CameraDeviceCatalog.Source {
        .init(id: "back-wide", position: .back, isBuiltInWideAngle: true)
    }

    private var backUltra: CameraDeviceCatalog.Source {
        .init(id: "back-ultra", position: .back, isBuiltInWideAngle: false)
    }

    private var backTele: CameraDeviceCatalog.Source {
        .init(id: "back-tele", position: .back, isBuiltInWideAngle: false)
    }

    private var frontWide: CameraDeviceCatalog.Source {
        .init(id: "front", position: .front, isBuiltInWideAngle: true)
    }

    private var external: CameraDeviceCatalog.Source {
        .init(id: "external", position: .unspecified, isBuiltInWideAngle: false)
    }
}
