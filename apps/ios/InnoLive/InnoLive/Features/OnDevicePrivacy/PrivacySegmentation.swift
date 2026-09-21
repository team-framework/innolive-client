#if DEBUG
import Accelerate
import CoreML
import CoreImage

/// Fixed contract produced by scripts/export-ios-privacy-model.py.
nonisolated enum PrivacySegmentation {
    static let inputSize = 640
    static let maskSize = 160
    static let channels = 32
    static let candidates = 8400
    static let confidence: Float = 0.25

    struct Detection {
        let box: CGRect // top-left origin, 640-pixel model coordinates
        let score: Float
        let classID: Int
        let coefficients: [Float]
    }

    struct Letterbox {
        let resized: CGSize
        let left: CGFloat
        let bottom: CGFloat

        init(size: CGSize) {
            let scale = CGFloat(inputSize) / max(size.width, size.height)
            resized = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
            left = floor((CGFloat(inputSize) - resized.width) / 2)
            let top = floor((CGFloat(inputSize) - resized.height) / 2)
            bottom = CGFloat(inputSize) - resized.height - top
        }
    }

    static func detections(_ values: [Float], count: Int = candidates) throws -> [Detection] {
        guard count > 0, values.count == 38 * count else { throw PrivacyModelError.outputContract }
        var found: [Detection] = []
        for i in 0..<count {
            let face = values[4 * count + i]
            let plate = values[5 * count + i]
            guard face.isFinite, plate.isFinite else { throw PrivacyModelError.outputContract }
            let score = max(face, plate)
            guard score >= confidence else { continue }
            let x = values[i], y = values[count + i]
            let w = values[2 * count + i], h = values[3 * count + i]
            guard x.isFinite, y.isFinite, w.isFinite, h.isFinite, w > 0, h > 0 else {
                throw PrivacyModelError.outputContract
            }
            let box = CGRect(x: CGFloat(x - w / 2), y: CGFloat(y - h / 2), width: CGFloat(w), height: CGFloat(h))
                .intersection(CGRect(x: 0, y: 0, width: inputSize, height: inputSize))
            guard !box.isNull, !box.isEmpty else { continue }
            let coefficients = (0..<channels).map { values[(6 + $0) * count + i] }
            guard coefficients.allSatisfy(\.isFinite) else { throw PrivacyModelError.outputContract }
            found.append(Detection(box: box, score: score, classID: face >= plate ? 0 : 1, coefficients: coefficients))
        }
        var kept: [Detection] = []
        for candidate in found.sorted(by: { $0.score > $1.score }) {
            if !kept.contains(where: { $0.classID == candidate.classID && iou($0.box, candidate.box) > 0.45 }) {
                kept.append(candidate)
            }
            // Do not silently leave excess detections unprotected in crowded scenes.
            if kept.count > 100 { throw PrivacyModelError.tooManyObjects }
        }
        return kept
    }

    static func iou(_ a: CGRect, _ b: CGRect) -> CGFloat {
        let intersection = a.intersection(b)
        if intersection.isNull { return 0 }
        let area = intersection.width * intersection.height
        return area / max(a.width * a.height + b.width * b.height - area, 0.0001)
    }

    static func unionMask(detections: [Detection], prototypes: [Float]) throws -> [UInt8] {
        let pixels = maskSize * maskSize
        guard prototypes.count == channels * pixels, prototypes.allSatisfy(\.isFinite) else {
            throw PrivacyModelError.outputContract
        }
        var union = [UInt8](repeating: 0, count: pixels)
        var logits = [Float](repeating: 0, count: pixels)
        for object in detections {
            guard object.coefficients.count == channels else { throw PrivacyModelError.outputContract }
            vDSP_mmul(object.coefficients, 1, prototypes, 1, &logits, 1, 1, vDSP_Length(pixels), vDSP_Length(channels))
            let scale = CGFloat(maskSize) / CGFloat(inputSize)
            let x0 = max(0, Int(floor(object.box.minX * scale)))
            let x1 = min(maskSize, Int(ceil(object.box.maxX * scale)))
            let y0 = max(0, Int(floor(object.box.minY * scale)))
            let y1 = min(maskSize, Int(ceil(object.box.maxY * scale)))
            guard x0 < x1, y0 < y1 else { continue }
            var covered = false
            for y in y0..<y1 {
                for x in x0..<x1 where logits[y * maskSize + x] > 0 {
                    union[y * maskSize + x] = 255
                    covered = true
                }
            }
            // A valid detection with an empty mask still needs protection.
            if !covered {
                for y in y0..<y1 { for x in x0..<x1 { union[y * maskSize + x] = 255 } }
            }
        }
        return union
    }

    static func floats(_ tensor: MLMultiArray, shape: [Int]) throws -> [Float] {
        guard tensor.shape.map(\.intValue) == shape else { throw PrivacyModelError.outputContract }
        // Verify contiguous output before using its pointer rather than assuming strides.
        var stride = 1
        for axis in shape.indices.reversed() {
            guard tensor.strides[axis].intValue == stride else { throw PrivacyModelError.outputContract }
            stride *= shape[axis]
        }
        switch tensor.dataType {
        case .float32:
            return Array(UnsafeBufferPointer(start: tensor.dataPointer.assumingMemoryBound(to: Float.self), count: tensor.count))
        case .float16:
            return UnsafeBufferPointer(start: tensor.dataPointer.assumingMemoryBound(to: Float16.self), count: tensor.count).map(Float.init)
        default: throw PrivacyModelError.outputContract
        }
    }
}

nonisolated enum PrivacyModelError: LocalizedError {
    case missingModel, outputContract, imageBuffer, tooManyObjects
    var errorDescription: String? {
        switch self {
        case .missingModel: "테스트 모델이 없습니다. 모델 준비 후 앱을 다시 빌드해 주세요."
        case .outputContract: "모델 출력 형식이 맞지 않아 미리보기를 중단했습니다."
        case .imageBuffer: "영상 처리에 실패해 미리보기를 중단했습니다."
        case .tooManyObjects: "탐지 대상이 너무 많아 미리보기를 중단했습니다."
        }
    }
}
#endif
