#if DEBUG
import CoreML
import CoreImage
import Vision
import Foundation
import Darwin

nonisolated enum PrivacyFaceSampleError: Int, LocalizedError {
    case faceCount = 1, confidence, pose, landmarks, size
    var errorDescription: String? {
        switch self {
        case .faceCount: return "얼굴 한 명이 보이도록 맞춰 주세요."
        case .confidence: return "얼굴이 선명하게 보이도록 맞춰 주세요."
        case .pose: return "얼굴을 정면으로 맞춰 주세요."
        case .landmarks: return "눈·코·입이 보이도록 맞춰 주세요."
        case .size: return "얼굴을 카메라에 더 가까이 맞춰 주세요."
        }
    }
}

nonisolated final class PrivacyFaceEmbeddingModel {
    private let model: MLModel
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()
    private let buffer: CVPixelBuffer
    let loadMilliseconds: Double

    init(computeUnits: MLComputeUnits = .all) throws {
        let start = ProcessInfo.processInfo.systemUptime
        guard let url = Bundle.main.url(forResource: "PrivacyFaceRecognizer", withExtension: "mlmodelc") else {
            throw PrivacyFaceError.message("얼굴 인식 모델이 없습니다. 모델을 포함해 빌드해 주세요.")
        }
        let config = MLModelConfiguration()
        config.computeUnits = computeUnits
        model = try MLModel(contentsOf: url, configuration: config)
        let metadata = model.modelDescription.metadata[.creatorDefinedKey] as? [String: String]
        guard metadata?["innolive.contract"] == "privacy-face-vit-kprpe-v1",
              metadata?["innolive.checkpoint_sha256"] == "04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9",
              model.modelDescription.inputDescriptionsByName["image"]?.imageConstraint?.pixelsWide == 112,
              model.modelDescription.inputDescriptionsByName["landmarks"]?.multiArrayConstraint?.shape.map(\.intValue) == [1, 5, 2],
              model.modelDescription.outputDescriptionsByName["embedding"]?.multiArrayConstraint?.shape.map(\.intValue) == [1, 512] else {
            throw PrivacyFaceError.message("얼굴 인식 모델 규격이 맞지 않습니다.")
        }
        var created: CVPixelBuffer?
        let result = CVPixelBufferCreate(kCFAllocatorDefault, 112, 112, kCVPixelFormatType_32BGRA,
                                         [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &created)
        guard result == kCVReturnSuccess, let created else { throw PrivacyModelError.imageBuffer }
        buffer = created
        loadMilliseconds = (ProcessInfo.processInfo.systemUptime - start) * 1000
    }

    func embedding(image: CGImage, enrollment: Bool) throws -> [Float] {
        let request = VNDetectFaceLandmarksRequest()
        try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        guard let faces = request.results, faces.count == 1, let face = faces.first else {
            throw PrivacyFaceSampleError.faceCount
        }
        guard face.confidence >= 0.8 else { throw PrivacyFaceSampleError.confidence }
        guard abs(face.yaw?.doubleValue ?? 0) < (enrollment ? 0.4 : 0.7) else { throw PrivacyFaceSampleError.pose }
        guard let landmarks = face.landmarks, let leftEye = landmarks.leftEye, let rightEye = landmarks.rightEye,
              let nose = landmarks.noseCrest, let lips = landmarks.outerLips,
              leftEye.pointCount > 0, rightEye.pointCount > 0, nose.pointCount > 0, lips.pointCount > 1 else {
            throw PrivacyFaceSampleError.landmarks
        }
        let input = CIImage(cgImage: image)
        let extent = input.extent
        let box = CGRect(x: face.boundingBox.minX * extent.width, y: face.boundingBox.minY * extent.height,
                         width: face.boundingBox.width * extent.width, height: face.boundingBox.height * extent.height)
        guard min(box.width, box.height) >= (enrollment ? 100 : 48) else {
            throw PrivacyFaceSampleError.size
        }
        let side = max(box.width, box.height) * 1.5
        let square = CGRect(x: box.midX - side / 2, y: box.midY - side / 2, width: side, height: side)
        let bounds = CGRect(x: 0, y: 0, width: 112, height: 112)
        let scaled = input.transformed(by: CGAffineTransform(translationX: -square.minX, y: -square.minY))
            .transformed(by: CGAffineTransform(scaleX: 112 / side, y: 112 / side))
        let black = CIImage(color: .black).cropped(to: bounds)
        context.render(scaled.composited(over: black).cropped(to: bounds), to: buffer, bounds: bounds, colorSpace: colorSpace)
        func center(_ region: VNFaceLandmarkRegion2D) -> CGPoint {
            let points = region.normalizedPoints
            return CGPoint(x: points.map(\.x).reduce(0, +) / CGFloat(points.count),
                           y: points.map(\.y).reduce(0, +) / CGFloat(points.count))
        }
        let eyes = [center(leftEye), center(rightEye)].sorted { $0.x < $1.x }
        let noseTip = nose.normalizedPoints.min { $0.y < $1.y }!
        let mouthLeft = lips.normalizedPoints.min { $0.x < $1.x }!
        let mouthRight = lips.normalizedPoints.max { $0.x < $1.x }!
        let points = [eyes[0], eyes[1], noseTip, mouthLeft, mouthRight].flatMap { point -> [Float] in
            let x = (box.minX + point.x * box.width - square.minX) / side
            let y = 1 - (box.minY + point.y * box.height - square.minY) / side
            return [Float(min(1, max(0, x))), Float(min(1, max(0, y)))]
        }
        return try predict(points: points)
    }

    private func predict(points: [Float]) throws -> [Float] {
        let landmarks = try MLMultiArray(shape: [1, 5, 2], dataType: .float32)
        for i in points.indices { landmarks[i] = NSNumber(value: points[i]) }
        let result = try model.prediction(from: MLDictionaryFeatureProvider(dictionary: [
            "image": MLFeatureValue(pixelBuffer: buffer), "landmarks": MLFeatureValue(multiArray: landmarks)
        ]))
        guard let output = result.featureValue(for: "embedding")?.multiArrayValue,
              output.count == 512,
              let embedding = PrivacyFaceMath.normalized((0..<512).map { output[$0].floatValue }) else {
            throw PrivacyFaceError.message("얼굴 인식 결과가 유효하지 않습니다.")
        }
        return embedding
    }

    /// Synthetic smoke test: exercises the real weights and runtime without capturing a person's image.
    func benchmarkPrediction() throws {
        let bounds = CGRect(x: 0, y: 0, width: 112, height: 112)
        context.render(CIImage(color: CIColor(red: 0.4, green: 0.5, blue: 0.6)).cropped(to: bounds),
                       to: buffer, bounds: bounds, colorSpace: colorSpace)
        _ = try predict(points: [0.34, 0.46, 0.66, 0.46, 0.50, 0.64, 0.37, 0.82, 0.63, 0.82])
    }

    static func memoryMegabytes() -> Double {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return status == KERN_SUCCESS ? Double(info.phys_footprint) / 1_000_000 : -1
    }
}

/// One in-flight job and one result slot; no growing image queue.
nonisolated final class PrivacyFaceWorker: @unchecked Sendable {
    struct Job: @unchecked Sendable {
        let id: UUID
        let generation: Int
        let capturedAt: Double
        let enrollment: Bool
        let image: CGImage
    }
    struct Output: Sendable {
        let id: UUID
        let generation: Int
        let capturedAt: Double
        let enrollment: Bool
        let embedding: [Float]?
        let message: String?
        let fatal: Bool
        let failureCode: Int
        let milliseconds: Double
    }
    private let queue = DispatchQueue(label: "com.innolive.privacy-face", qos: .userInitiated)
    private let lock = NSLock()
    private var working = false
    private var result: Output?
    private var recognizer: PrivacyFaceEmbeddingModel?
    private var metrics: [[String: Double]] = []

    var busy: Bool { lock.withLock { working || result != nil } }
    func takeResult() -> Output? { lock.withLock { let value = result; result = nil; return value } }

    func submit(_ job: Job) {
        let accepted = lock.withLock { () -> Bool in
            guard !working, result == nil else { return false }
            working = true
            return true
        }
        guard accepted else { return }
        queue.async { [self] in
            autoreleasepool {
                let start = ProcessInfo.processInfo.systemUptime
                var embedding: [Float]?
                var message: String?
                var fatal = false
                var failureCode = 0
                do {
                    if recognizer == nil { recognizer = try PrivacyFaceEmbeddingModel() }
                    embedding = try recognizer!.embedding(image: job.image, enrollment: job.enrollment)
                } catch {
                    message = error.localizedDescription
                    failureCode = (error as? PrivacyFaceSampleError)?.rawValue ?? 9
                    fatal = recognizer == nil
                }
                let milliseconds = (ProcessInfo.processInfo.systemUptime - start) * 1000
                metrics.append(["uptime": start, "recognition_ms": milliseconds,
                                "load_ms": recognizer?.loadMilliseconds ?? 0,
                                "success": embedding == nil ? 0 : 1, "failure_code": Double(failureCode),
                                "memory_mb": PrivacyFaceEmbeddingModel.memoryMegabytes()])
                if metrics.count > 900 { metrics.removeFirst() }
                Self.saveMetrics(metrics, filename: "privacy-face-metrics.json")
                lock.withLock {
                    result = Output(id: job.id, generation: job.generation, capturedAt: job.capturedAt,
                                    enrollment: job.enrollment, embedding: embedding, message: message,
                                    fatal: fatal, failureCode: failureCode, milliseconds: milliseconds)
                    working = false
                }
            }
        }
    }

    static func saveMetrics(_ rows: [[String: Double]], filename: String) {
        if let data = try? JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys]),
           let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first {
            try? data.write(to: directory.appendingPathComponent(filename), options: .atomic)
        }
    }
}
#endif
