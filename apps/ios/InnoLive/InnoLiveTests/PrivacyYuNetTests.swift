#if DEBUG
import XCTest
import CoreGraphics
import CoreImage
@testable import InnoLive

final class PrivacyYuNetTests: XCTestCase {
    private func emptyOutputs(width: Int = 32, height: Int = 32) -> [String: [Float]] {
        var output: [String: [Float]] = [:]
        for stride in [8, 16, 32] {
            let count = width / stride * (height / stride)
            for (name, channels) in [("cls", 1), ("obj", 1), ("bbox", 4), ("kps", 10)] {
                output["\(name)_\(stride)"] = [Float](repeating: 0, count: count * channels)
            }
        }
        return output
    }

    func testStrideAndFivePointDecodingMatchOpenCVFormula() throws {
        var output = emptyOutputs()
        let i = 5 // row 1, column 1, stride 8
        output["cls_8"]![i] = 1
        output["obj_8"]![i] = 0.81
        output["bbox_8"]!.replaceSubrange(i * 4..<i * 4 + 4, with: [0.5, 0.5, log(Float(2)), log(Float(3))])
        output["kps_8"]!.replaceSubrange(i * 10..<i * 10 + 10, with: [0,0,1,0,0.5,0.5,0,1,1,1])
        let faces = try PrivacyYuNetDecoding.decode(outputs: output, width: 32, height: 32)
        let face = try XCTUnwrap(faces.first)
        XCTAssertEqual(faces.count, 1)
        XCTAssertEqual(face.score, 0.9, accuracy: 0.0001)
        XCTAssertEqual(face.box.minX, 4, accuracy: 0.0001)
        XCTAssertEqual(face.box.minY, 0, accuracy: 0.0001)
        XCTAssertEqual(face.box.width, 16, accuracy: 0.0001)
        XCTAssertEqual(face.box.height, 24, accuracy: 0.0001)
        XCTAssertEqual(face.landmarks, [CGPoint(x:8,y:8), CGPoint(x:16,y:8), CGPoint(x:12,y:12), CGPoint(x:8,y:16), CGPoint(x:16,y:16)])
    }

    func testIntegerBoxNMSRemovesLowerScoringDuplicate() throws {
        var output = emptyOutputs()
        for (index, score, dx) in [(5, Float(0.99), Float(0.5)), (6, Float(0.95), Float(-0.5))] {
            output["cls_8"]![index] = score
            output["obj_8"]![index] = score
            output["bbox_8"]!.replaceSubrange(index * 4..<index * 4 + 4, with: [dx, 0.5, log(Float(2)), log(Float(2))])
        }
        let faces = try PrivacyYuNetDecoding.decode(outputs: output, width: 32, height: 32)
        XCTAssertEqual(faces.count, 1)
        XCTAssertEqual(faces.first!.score, 0.99, accuracy: 0.0001)
    }

    func testMissingNonFiniteOrWrongShapeOutputsAreRejected() throws {
        var output = emptyOutputs()
        output["bbox_8"]![0] = .nan
        XCTAssertThrowsError(try PrivacyYuNetDecoding.decode(outputs: output, width: 32, height: 32))
        output = emptyOutputs(); output.removeValue(forKey: "obj_16")
        XCTAssertThrowsError(try PrivacyYuNetDecoding.decode(outputs: output, width: 32, height: 32))
        XCTAssertThrowsError(try PrivacyYuNetDecoding.decode(outputs: emptyOutputs(), width: 31, height: 32))
    }

    func testServerSquareMarginAndTopLeftLandmarkNormalization() {
        let face = PrivacyYuNetDecoding.Face(box: CGRect(x: 100, y: 200, width: 80, height: 100),
                                             landmarks: [CGPoint(x: 140, y: 250), CGPoint(x: -10, y: 500)], score: 1)
        XCTAssertEqual(PrivacyYuNetDecoding.square(for: face), CGRect(x: 65, y: 175, width: 150, height: 150))
        XCTAssertEqual(PrivacyYuNetDecoding.normalizedLandmarks(face), [0.5, 0.5, 0, 1])
    }

    func testReusableCaptureServiceRejectsUndersizedImage() throws {
        let context = CIContext()
        let image = try XCTUnwrap(context.createCGImage(CIImage(color: .black), from: CGRect(x: 0, y: 0, width: 100, height: 100)))
        if case .failed = FaceDetectionService().analyzeUpright(image: image) {} else { XCTFail("Small input must fail") }
    }
}
#endif
