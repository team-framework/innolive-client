#if DEBUG
import CoreML
import CoreImage
import ImageIO
import Foundation
import Darwin

nonisolated enum PrivacyFaceSampleError: Int, LocalizedError {
    case faceCount = 1, confidence, pose, landmarks, size, multipleFaces
    var errorDescription: String? {
        switch self {
        case .faceCount, .multipleFaces: return "얼굴 한 명이 보이도록 맞춰 주세요."
        case .confidence: return "얼굴이 선명하게 보이도록 맞춰 주세요."
        case .pose: return "얼굴을 정면으로 맞춰 주세요."
        case .landmarks: return "눈·코·입이 보이도록 맞춰 주세요."
        case .size: return "얼굴을 카메라에 더 가까이 맞춰 주세요."
        }
    }
}

nonisolated final class PrivacyFaceEmbeddingModel {
    private let model: MLModel
    private let detector: PrivacyYuNetDetector
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()
    private let buffer: CVPixelBuffer
    let loadMilliseconds: Double
    let recognizerLoadMilliseconds: Double
    let detectorLoadMilliseconds: Double

    // iPhone 16 measurements: CPU/GPU loads and predicts faster for this ViT graph.
    // Keep YuNet's separate CPU-only setting.
    init(computeUnits: MLComputeUnits = .cpuAndGPU) throws {
        let start = ProcessInfo.processInfo.systemUptime
        guard let url = Bundle.main.url(forResource: "PrivacyFaceRecognizer", withExtension: "mlmodelc") else {
            throw PrivacyFaceError.message("얼굴 인식 모델이 없습니다. 모델을 포함해 빌드해 주세요.")
        }
        let config = MLModelConfiguration()
        config.computeUnits = computeUnits
        model = try MLModel(contentsOf: url, configuration: config)
        recognizerLoadMilliseconds = (ProcessInfo.processInfo.systemUptime - start) * 1000
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
        let detectorStart = ProcessInfo.processInfo.systemUptime
        detector = try PrivacyYuNetDetector()
        detectorLoadMilliseconds = (ProcessInfo.processInfo.systemUptime - detectorStart) * 1000
        loadMilliseconds = (ProcessInfo.processInfo.systemUptime - start) * 1000
    }

    func embedding(image: CGImage, enrollment: Bool) throws -> [Float] {
        let face = try detector.face(in: image, enrollment: enrollment)
        let square = PrivacyYuNetDecoding.square(for: face)
        let input = CIImage(cgImage: image)
        let bounds = CGRect(x: 0, y: 0, width: 112, height: 112)
        let bottom = CGFloat(image.height) - square.maxY
        let scaled = input.transformed(by: CGAffineTransform(translationX: -square.minX, y: -bottom))
            .transformed(by: CGAffineTransform(scaleX: 112 / square.width, y: 112 / square.height))
        context.render(scaled.composited(over: CIImage(color: .black).cropped(to: bounds)).cropped(to: bounds),
                       to: buffer, bounds: bounds, colorSpace: colorSpace)
        let points = PrivacyYuNetDecoding.normalizedLandmarks(face)
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
    @discardableResult
    func benchmarkPrediction(sample: Int = 0) throws -> [Float] {
        let bounds = CGRect(x: 0, y: 0, width: 112, height: 112)
        let image: CIImage
        switch sample {
        case 1:
            image = CIFilter(name: "CILinearGradient", parameters: [
                "inputPoint0": CIVector(x: 0, y: 0), "inputPoint1": CIVector(x: 112, y: 112),
                "inputColor0": CIColor(red: 0.1, green: 0.8, blue: 0.2),
                "inputColor1": CIColor(red: 0.9, green: 0.2, blue: 0.7)
            ])!.outputImage!
        case 2:
            image = CIFilter(name: "CICheckerboardGenerator", parameters: [
                "inputCenter": CIVector(x: 57, y: 53), "inputWidth": 13,
                "inputColor0": CIColor(red: 0.2, green: 0.3, blue: 0.9),
                "inputColor1": CIColor(red: 0.8, green: 0.7, blue: 0.1)
            ])!.outputImage!
        default: image = CIImage(color: CIColor(red: 0.4, green: 0.5, blue: 0.6))
        }
        context.render(image.cropped(to: bounds), to: buffer, bounds: bounds, colorSpace: colorSpace)
        return try predict(points: [0.34, 0.46, 0.66, 0.46, 0.50, 0.64, 0.37, 0.82, 0.63, 0.82])
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
    private let captureService = FaceDetectionService()
    private var enrollmentID: UUID?
    private var stableCaptures = 0
    private var prepared = false
    private var preparationFailure: String?

    var ready: Bool { lock.withLock { prepared } }
    var preparationMessage: String? { lock.withLock { preparationFailure } }

    func prepare() {
        let accepted = lock.withLock { () -> Bool in
            guard !working, !prepared else { return false }
            working = true
            preparationFailure = nil
            return true
        }
        guard accepted else { return }
        queue.async { [self] in
            autoreleasepool {
                let start = ProcessInfo.processInfo.systemUptime
                Self.saveMetrics([["state": 0, "uptime": start]], filename: "privacy-face-preparation.json")
                do {
                    if recognizer == nil { recognizer = try PrivacyFaceEmbeddingModel() }
                    let warmupStart = ProcessInfo.processInfo.systemUptime
                    try recognizer!.benchmarkPrediction()
                    lock.withLock { prepared = true; working = false }
                    Self.saveMetrics([["state": 1, "uptime": start,
                                       "compute_mode": 2,
                                       "prepare_ms": (ProcessInfo.processInfo.systemUptime - start) * 1000,
                                       "recognizer_load_ms": recognizer!.recognizerLoadMilliseconds,
                                       "detector_load_ms": recognizer!.detectorLoadMilliseconds,
                                       "warmup_ms": (ProcessInfo.processInfo.systemUptime - warmupStart) * 1000]],
                                     filename: "privacy-face-preparation.json")
                } catch {
                    lock.withLock { prepared = false; working = false; preparationFailure = error.localizedDescription }
                    Self.saveMetrics([["state": 2, "prepare_ms": (ProcessInfo.processInfo.systemUptime - start) * 1000]],
                                     filename: "privacy-face-preparation.json")
                }
            }
        }
    }

    private func enrollmentImage(_ job: Job) throws -> CGImage {
        if enrollmentID != job.id { enrollmentID = job.id; stableCaptures = 0 }
        let outcome = captureService.analyzeUpright(image: job.image)
        switch outcome {
        case let .ready(data):
            stableCaptures += 1
            guard stableCaptures >= 3 else { throw PrivacyFaceError.message("좋아요. 잠시 그대로 있어 주세요.") }
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw PrivacyModelError.imageBuffer }
            return image
        case .noFace: stableCaptures = 0; throw PrivacyFaceSampleError.faceCount
        case .multipleFaces: stableCaptures = 0; throw PrivacyFaceSampleError.multipleFaces
        case .moveCloser: stableCaptures = 0; throw PrivacyFaceSampleError.size
        case .centerFace: stableCaptures = 0; throw PrivacyFaceError.message("얼굴을 가운데 영역에 맞춰 주세요.")
        case .failed: stableCaptures = 0; throw PrivacyFaceError.message("얼굴 촬영을 다시 시도합니다.")
        }
    }

    var busy: Bool { lock.withLock { working || result != nil } }
    func takeResult() -> Output? { lock.withLock { let value = result; result = nil; return value } }

    func submit(_ job: Job) {
        let accepted = lock.withLock { () -> Bool in
            guard prepared, !working, result == nil else { return false }
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
                var inputSize = CGSize(width: job.image.width, height: job.image.height)
                do {
                    let image = try job.enrollment ? enrollmentImage(job) : job.image
                    inputSize = CGSize(width: image.width, height: image.height)
                    embedding = try recognizer!.embedding(image: image, enrollment: job.enrollment)
                } catch {
                    message = error.localizedDescription
                    failureCode = (error as? PrivacyFaceSampleError)?.rawValue ?? 9
                    fatal = recognizer == nil
                }
                let milliseconds = (ProcessInfo.processInfo.systemUptime - start) * 1000
                let layout = PrivacyYuNetDecoding.InputLayout(size: inputSize, enrollment: job.enrollment)
                metrics.append(["uptime": start, "recognition_ms": milliseconds,
                                "enrollment": job.enrollment ? 1 : 0,
                                "input_width": Double(inputSize.width), "input_height": Double(inputSize.height),
                                "detector_width": Double(layout.width), "detector_height": Double(layout.height),
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
