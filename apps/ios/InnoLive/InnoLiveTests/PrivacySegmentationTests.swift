#if DEBUG
import CoreML
import XCTest
@testable import InnoLive

final class PrivacySegmentationTests: XCTestCase {
    func testClassAwareSuppressionKeepsPlateOverlappingFace() throws {
        let count = 3
        var raw = [Float](repeating: 0, count: 38 * count)
        for i in 0..<count {
            raw[i] = 320; raw[count + i] = 320
            raw[2 * count + i] = 80; raw[3 * count + i] = 80
        }
        raw[4 * count] = 0.9
        raw[4 * count + 1] = 0.8
        raw[5 * count + 2] = 0.7
        let found = try PrivacySegmentation.detections(raw, count: count)
        XCTAssertEqual(found.map(\.classID), [0, 1])
        XCTAssertEqual(found[0].box, CGRect(x: 280, y: 280, width: 80, height: 80))
    }

    func testInvalidOutputFailsInsteadOfShowingOriginal() {
        XCTAssertThrowsError(try PrivacySegmentation.detections([0], count: 1))
        var raw = [Float](repeating: 0, count: 38)
        raw[4] = 0.9; raw[0] = .nan; raw[2] = 80; raw[3] = 80
        XCTAssertThrowsError(try PrivacySegmentation.detections(raw, count: 1))
    }

    func testEmptyInstanceMaskProtectsDetectionBox() throws {
        let detection = PrivacySegmentation.Detection(box: CGRect(x: 40, y: 80, width: 16, height: 16),
                                                      score: 0.9, classID: 0,
                                                      coefficients: [Float](repeating: 0, count: 32))
        let mask = try PrivacySegmentation.unionMask(detections: [detection],
                                                     prototypes: [Float](repeating: 0, count: 32 * 160 * 160))
        XCTAssertEqual(mask.filter { $0 == 255 }.count, 16)
        XCTAssertEqual(mask[20 * 160 + 10], 255)
        XCTAssertEqual(mask[10 * 160 + 20], 0, "Mask coordinates must not be transposed")
    }

    func testLetterboxPreservesPortraitAndLandscapeGeometry() {
        let portrait = PrivacySegmentation.Letterbox(size: CGSize(width: 1080, height: 1920))
        XCTAssertEqual(portrait.resized, CGSize(width: 360, height: 640))
        XCTAssertEqual(portrait.left, 140)
        XCTAssertEqual(portrait.bottom, 0)
        let landscape = PrivacySegmentation.Letterbox(size: CGSize(width: 1920, height: 1080))
        XCTAssertEqual(landscape.resized, CGSize(width: 640, height: 360))
        XCTAssertEqual(landscape.left, 0)
        XCTAssertEqual(landscape.bottom, 140)
    }
}
#endif
