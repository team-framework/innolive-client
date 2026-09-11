import XCTest

@testable import InnoLive

final class CameraDeviceCatalogTests: XCTestCase {
    func testHidesPhysicalCamerasCoveredByVirtualDevice() {
        let visible = CameraDeviceCatalog.visibleIDs(
            ["triple", "wide", "ultra", "tele", "front"],
            constituents: ["triple": ["wide", "ultra", "tele"]]
        )

        XCTAssertEqual(visible, ["triple", "front"])
    }

    func testKeepsStandaloneCamerasWhenNoVirtualParentExists() {
        let visible = CameraDeviceCatalog.visibleIDs(
            ["wide", "front", "external"],
            constituents: [:]
        )

        XCTAssertEqual(visible, ["wide", "front", "external"])
    }

    func testResolvesSavedConstituentToVirtualParent() {
        let resolved = CameraDeviceCatalog.resolvedCameraID(
            savedID: "wide",
            availableIDs: ["triple", "front"],
            constituents: ["triple": ["wide", "ultra", "tele"]]
        )

        XCTAssertEqual(resolved, "triple")
    }

    func testKeepsSavedIDWhenItIsAlreadyVisible() {
        let resolved = CameraDeviceCatalog.resolvedCameraID(
            savedID: "front",
            availableIDs: ["triple", "front"],
            constituents: ["triple": ["wide", "ultra", "tele"]]
        )

        XCTAssertEqual(resolved, "front")
    }
}
