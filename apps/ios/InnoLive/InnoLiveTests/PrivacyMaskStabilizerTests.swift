#if DEBUG
import XCTest
import CoreGraphics
@testable import InnoLive

final class PrivacyMaskStabilizerTests: XCTestCase {
    func testNewProtectionIsImmediateAndOldBoundaryFades() throws {
        let stabilizer = PrivacyMaskStabilizer()
        let box = CGRect(x: 160, y: 160, width: 160, height: 160)
        _ = try stabilizer.apply([instance(box: box, pixels: [(50, 50)])], timestamp: 1)
        let result = try stabilizer.apply([instance(box: box, pixels: [(60, 60)])], timestamp: 1 + 1 / 30)
        XCTAssertEqual(result[60 * 160 + 60], 255)
        XCTAssertGreaterThan(result[50 * 160 + 50], 150)
        XCTAssertLessThan(result[50 * 160 + 50], 255)
    }

    func testHistoryFollowsTranslationInsteadOfStayingAtOldLocation() throws {
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([instance(box: CGRect(x: 160, y: 160, width: 160, height: 160),
                                            pixels: [(50, 50)])], timestamp: 1)
        let moved = try stabilizer.apply([instance(box: CGRect(x: 176, y: 160, width: 160, height: 160),
                                                   pixels: [])], timestamp: 1 + 1 / 30)
        XCTAssertGreaterThan(moved[50 * 160 + 54], 150)
        XCTAssertEqual(moved[50 * 160 + 50], 0)
    }

    func testUnmatchedClassAndLongGapDoNotReuseHistory() throws {
        let box = CGRect(x: 160, y: 160, width: 160, height: 160)
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([instance(box: box, pixels: [(50, 50)])], timestamp: 1)
        let plate = try stabilizer.apply([instance(box: box, pixels: [], classID: 1)], timestamp: 1 + 1 / 30)
        XCTAssertEqual(plate.max(), 0)
        _ = try stabilizer.apply([instance(box: box, pixels: [(50, 50)])], timestamp: 2)
        let delayed = try stabilizer.apply([instance(box: box, pixels: [])], timestamp: 2.5)
        XCTAssertEqual(delayed.max(), 0)
    }

    func testDisappearanceAndCameraResetClearHistory() throws {
        let box = CGRect(x: 160, y: 160, width: 160, height: 160)
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([instance(box: box, pixels: [(50, 50)])], timestamp: 1)
        XCTAssertEqual(try stabilizer.apply([], timestamp: 1.03).max(), 0)
        XCTAssertEqual(try stabilizer.apply([instance(box: box, pixels: [])], timestamp: 1.06).max(), 0)
        _ = try stabilizer.apply([instance(box: box, pixels: [(50, 50)])], timestamp: 2)
        stabilizer.reset()
        XCTAssertEqual(try stabilizer.apply([instance(box: box, pixels: [])], timestamp: 2.03).max(), 0)
    }

    func testOldEdgeEventuallyDisappearsWithoutQuantizedResidue() throws {
        let box = CGRect(x: 160, y: 160, width: 160, height: 160)
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([instance(box: box, pixels: [(50, 50)])], timestamp: 1)
        var result: [UInt8] = []
        for frame in 1...40 {
            result = try stabilizer.apply([instance(box: box, pixels: [])], timestamp: 1 + Double(frame) / 30)
        }
        XCTAssertEqual(result.max(), 0)
    }

    private func instance(box: CGRect, pixels: [(Int, Int)], classID: Int = 0) -> PrivacySegmentation.InstanceMask {
        var bytes = [UInt8](repeating: 0, count: 160 * 160)
        for (x, y) in pixels { bytes[y * 160 + x] = 255 }
        return .init(detection: .init(box: box, score: 0.9, classID: classID, coefficients: []), bytes: bytes)
    }
}
#endif
