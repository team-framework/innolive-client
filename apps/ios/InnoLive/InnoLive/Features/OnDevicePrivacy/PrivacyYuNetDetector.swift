#if DEBUG
import Foundation
import CoreML
import CoreImage

/// Port of OpenCV FaceDetectorYN's stride decoding and integer-box NMS.
/// Source: opencv/modules/objdetect/src/face_detect.cpp (Apache-2.0).
nonisolated enum PrivacyYuNetDecoding {
    struct Face {
        let box: CGRect // top-left pixel coordinates
        let landmarks: [CGPoint] // right eye, left eye, nose, right mouth, left mouth
        let score: Float
    }

    struct InputLayout {
        let original: CGSize
        let resized: CGSize
        let width: Int
        let height: Int

        init(size: CGSize, enrollment: Bool) {
            original = size
            // Camera ROIs can contain faces much larger than YuNet's training range.
            // Keep registration's existing 500px capture path; bound live query work.
            let scale = enrollment ? 1 : min(1, 320 / max(size.width, size.height))
            resized = CGSize(width: max(1, (size.width * scale).rounded()),
                             height: max(1, (size.height * scale).rounded()))
            width = ((Int(resized.width) + 31) / 32) * 32
            height = ((Int(resized.height) + 31) / 32) * 32
        }

        func restore(_ face: Face) -> Face {
            let x = original.width / resized.width, y = original.height / resized.height
            return Face(box: CGRect(x: face.box.minX * x, y: face.box.minY * y,
                                    width: face.box.width * x, height: face.box.height * y),
                        landmarks: face.landmarks.map { CGPoint(x: $0.x * x, y: $0.y * y) }, score: face.score)
        }
    }

    static func decode(outputs: [String: [Float]], width: Int, height: Int, threshold: Float = 0.6) throws -> [Face] {
        guard width > 0, height > 0, width % 32 == 0, height % 32 == 0 else { throw PrivacyModelError.outputContract }
        var faces: [Face] = []
        for stride in [8, 16, 32] {
            let cols = width / stride, rows = height / stride, count = cols * rows
            guard let cls = outputs["cls_\(stride)"], cls.count == count,
                  let obj = outputs["obj_\(stride)"], obj.count == count,
                  let bbox = outputs["bbox_\(stride)"], bbox.count == count * 4,
                  let kps = outputs["kps_\(stride)"], kps.count == count * 10,
                  cls.allSatisfy(\.isFinite), obj.allSatisfy(\.isFinite), bbox.allSatisfy(\.isFinite), kps.allSatisfy(\.isFinite) else {
                throw PrivacyModelError.outputContract
            }
            for index in 0..<count {
                let score = sqrt(min(1, max(0, cls[index])) * min(1, max(0, obj[index])))
                guard score >= threshold else { continue }
                let row = index / cols, col = index % cols
                let cx = (Float(col) + bbox[index * 4]) * Float(stride)
                let cy = (Float(row) + bbox[index * 4 + 1]) * Float(stride)
                let w = exp(bbox[index * 4 + 2]) * Float(stride)
                let h = exp(bbox[index * 4 + 3]) * Float(stride)
                guard w.isFinite, h.isFinite, w > 0, h > 0, w < 100_000, h < 100_000,
                      abs(cx) < 100_000, abs(cy) < 100_000 else { continue }
                let points = (0..<5).map { n in
                    CGPoint(x: CGFloat((kps[index * 10 + n * 2] + Float(col)) * Float(stride)),
                            y: CGFloat((kps[index * 10 + n * 2 + 1] + Float(row)) * Float(stride)))
                }
                faces.append(.init(box: CGRect(x: CGFloat(cx - w / 2), y: CGFloat(cy - h / 2),
                                               width: CGFloat(w), height: CGFloat(h)), landmarks: points, score: score))
            }
        }
        // OpenCV NMS uses Rect2i, score order and top_k=5000 before suppression.
        func integerBox(_ box: CGRect) -> CGRect {
            CGRect(x: Int(box.minX), y: Int(box.minY), width: Int(box.width), height: Int(box.height))
        }
        var kept: [Face] = []
        for face in faces.sorted(by: { $0.score > $1.score }).prefix(5000) {
            if !kept.contains(where: { PrivacySegmentation.iou(integerBox($0.box), integerBox(face.box)) > 0.3 }) {
                kept.append(face)
            }
        }
        return kept
    }

    static func square(for face: Face) -> CGRect {
        let side = max(face.box.width, face.box.height) * 1.5
        return CGRect(x: face.box.midX - side / 2, y: face.box.midY - side / 2, width: side, height: side)
    }

    static func normalizedLandmarks(_ face: Face) -> [Float] {
        let square = square(for: face)
        return face.landmarks.flatMap { point in
            [Float(min(1, max(0, (point.x - square.minX) / square.width))),
             Float(min(1, max(0, (point.y - square.minY) / square.height)))]
        }
    }
}

nonisolated final class PrivacyYuNetDetector {
    private let model: MLModel
    private let context = CIContext(options: [.cacheIntermediates: false])
    private var input: CVPixelBuffer?

    init() throws {
        guard let url = Bundle.main.url(forResource: "PrivacyYuNet", withExtension: "mlmodelc") else {
            throw PrivacyFaceError.message("YuNet 모델이 없습니다. 모델을 포함해 빌드해 주세요.")
        }
        let config = MLModelConfiguration()
        // Small, variable-size YuNet stays on CPU to avoid growing GPU shape caches.
        config.computeUnits = .cpuOnly
        model = try MLModel(contentsOf: url, configuration: config)
        let metadata = model.modelDescription.metadata[.creatorDefinedKey] as? [String: String]
        guard metadata?["innolive.contract"] == "privacy-yunet-2023mar-v1",
              metadata?["innolive.checkpoint_sha256"] == "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4" else {
            throw PrivacyModelError.outputContract
        }
    }

    func face(in image: CGImage, enrollment: Bool) throws -> PrivacyYuNetDecoding.Face {
        let layout = PrivacyYuNetDecoding.InputLayout(size: CGSize(width: image.width, height: image.height), enrollment: enrollment)
        let width = layout.width, height = layout.height
        guard width <= 2048, height <= 2048 else { throw PrivacyFaceSampleError.size }
        if input == nil || CVPixelBufferGetWidth(input!) != width || CVPixelBufferGetHeight(input!) != height {
            var created: CVPixelBuffer?
            guard CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA,
                                      [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &created) == kCVReturnSuccess else {
                throw PrivacyModelError.imageBuffer
            }
            input = created
        }
        guard let input else { throw PrivacyModelError.imageBuffer }
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let topAligned = CIImage(cgImage: image)
            .transformed(by: CGAffineTransform(scaleX: layout.resized.width / CGFloat(image.width),
                                              y: layout.resized.height / CGFloat(image.height)))
            .transformed(by: CGAffineTransform(translationX: 0, y: CGFloat(height) - layout.resized.height))
        context.render(topAligned.composited(over: CIImage(color: .black).cropped(to: bounds)),
                       to: input, bounds: bounds, colorSpace: CGColorSpaceCreateDeviceRGB())
        let prediction = try model.prediction(from: MLDictionaryFeatureProvider(dictionary: ["image": MLFeatureValue(pixelBuffer: input)]))
        var values: [String: [Float]] = [:]
        for stride in [8, 16, 32] {
            for name in ["cls", "obj", "bbox", "kps"] {
                let key = "\(name)_\(stride)"
                guard let array = prediction.featureValue(for: key)?.multiArrayValue else { throw PrivacyModelError.outputContract }
                values[key] = (0..<array.count).map { array[$0].floatValue }
            }
        }
        // Server runs NMS at 0.6, then applies the higher enrollment score threshold.
        let faces = try PrivacyYuNetDecoding.decode(outputs: values, width: width, height: height)
            .filter { $0.score >= (enrollment ? 0.9 : 0.6) }
        guard faces.count <= 1 else { throw PrivacyFaceSampleError.multipleFaces }
        guard let detected = faces.first else { throw PrivacyFaceSampleError.faceCount }
        let face = layout.restore(detected)
        guard min(face.box.width, face.box.height) >= (enrollment ? 40 : 24) else { throw PrivacyFaceSampleError.size }
        return face
    }
}
#endif
