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
}
