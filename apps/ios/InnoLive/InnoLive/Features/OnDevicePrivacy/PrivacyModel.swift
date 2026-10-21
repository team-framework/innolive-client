import CoreML
import CoreImage
import CoreVideo
import ImageIO

nonisolated struct PrivacyTimings: Sendable {
    let prepare: Double
    let inference: Double
    let mask: Double
    let render: Double

    var total: Double { prepare + inference + mask + render }
}

nonisolated final class PrivacyModel {
    private let model: MLModel
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()
    private var blurBuffer: CVPixelBuffer?
    private let input: CVPixelBuffer
    private let inputName: String
    private let detectionsName: String
    private let prototypesName: String
    private let stabilizer = PrivacyMaskStabilizer()
    private let tracker = PrivacyBoTSORT()
    private let cameraMotion = PrivacyCameraMotion()
    private var previousExtent: CGRect?
    private var previousTime: Double?
    let faces = PrivacyFaceCoordinator()

    init() throws {
        guard let url = Bundle.main.url(forResource: "PrivacyDetector", withExtension: "mlmodelc") else {
            throw PrivacyModelError.missingModel
        }
        let config = MLModelConfiguration()
        config.computeUnits = .cpuAndNeuralEngine
        model = try MLModel(contentsOf: url, configuration: config)
        let description = model.modelDescription
        guard let imageInput = description.inputDescriptionsByName.first(where: { $0.value.type == .image }),
              imageInput.value.imageConstraint?.pixelsWide == 640,
              imageInput.value.imageConstraint?.pixelsHigh == 640,
              let detections = description.outputDescriptionsByName.first(where: {
                  $0.value.multiArrayConstraint?.shape.map(\.intValue) == [1, 38, 8400]
              }),
              let prototypes = description.outputDescriptionsByName.first(where: {
                  $0.value.multiArrayConstraint?.shape.map(\.intValue) == [1, 32, 160, 160]
              }) else { throw PrivacyModelError.outputContract }
        inputName = imageInput.key
        detectionsName = detections.key
        prototypesName = prototypes.key
        var buffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(kCFAllocatorDefault, 640, 640, kCVPixelFormatType_32BGRA,
                                        [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
        guard status == kCVReturnSuccess, let buffer else { throw PrivacyModelError.imageBuffer }
        input = buffer
    }

    func resetTemporalState() {
        stabilizer.reset()
        tracker.reset()
        cameraMotion.reset()
        faces.reset()
        previousExtent = nil
        previousTime = nil
    }

    func process(_ pixelBuffer: CVPixelBuffer) throws -> (CGImage, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        return try process(CIImage(cvPixelBuffer: pixelBuffer))
    }

    func process(_ original: CIImage) throws -> (CGImage, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        try process(original) { image in
            guard let output = context.createCGImage(image, from: original.extent) else {
                throw PrivacyModelError.imageBuffer
            }
            return output
        }
    }

    /// Render directly into reusable output without a full-frame CGImage round trip.
    func process(_ original: CIImage, into output: CVPixelBuffer,
                 restoring orientation: CGImagePropertyOrientation) throws -> (Int, PrivacyTimings, PrivacyFaceSnapshot) {
        let result = try process(original) { image in
            let restored = image.oriented(orientation)
            let normalized = restored.transformed(by: CGAffineTransform(translationX: -restored.extent.minX,
                                                                         y: -restored.extent.minY))
            context.render(normalized, to: output,
                           bounds: CGRect(x: 0, y: 0, width: CVPixelBufferGetWidth(output), height: CVPixelBufferGetHeight(output)),
                           colorSpace: colorSpace)
        }
        return (result.1, result.2, result.3)
    }

    private func process<Output>(_ original: CIImage, render: (CIImage) throws -> Output) throws -> (Output, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        do { return try processFrame(original, render: render) }
        catch { resetTemporalState(); throw error }
    }

    private func processFrame<Output>(_ original: CIImage, render: (CIImage) throws -> Output) throws -> (Output, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        let start = ProcessInfo.processInfo.systemUptime
        if let previousTime, start <= previousTime || start - previousTime >= 1.0 { resetTemporalState() }
        if let previousExtent, previousExtent != original.extent { resetTemporalState() }
        previousTime = start
        previousExtent = original.extent
        let size = original.extent.size
        let layout = PrivacySegmentation.Letterbox(size: size)
        let resized = original.transformed(by: CGAffineTransform(
            scaleX: layout.resized.width / size.width, y: layout.resized.height / size.height
        )).transformed(by: CGAffineTransform(translationX: layout.left, y: layout.bottom))
        let bounds = CGRect(x: 0, y: 0, width: 640, height: 640)
        let background = CIImage(color: CIColor(red: 114 / 255, green: 114 / 255, blue: 114 / 255)).cropped(to: bounds)
        context.render(resized.composited(over: background), to: input, bounds: bounds, colorSpace: colorSpace)
        let prepared = ProcessInfo.processInfo.systemUptime
        let prediction = try model.prediction(from: MLDictionaryFeatureProvider(dictionary: [inputName: MLFeatureValue(pixelBuffer: input)]))
        let inferred = ProcessInfo.processInfo.systemUptime
        guard let raw = prediction.featureValue(for: detectionsName)?.multiArrayValue,
              let proto = prediction.featureValue(for: prototypesName)?.multiArrayValue else {
            throw PrivacyModelError.outputContract
        }
        let objects = try PrivacySegmentation.detections(PrivacySegmentation.floats(raw, shape: [1, 38, 8400]))
        let motion = PrivacyPipelineProbe.measure("camera_motion") { cameraMotion.estimate(original, layout: layout) }
        let tracked = PrivacyPipelineProbe.measure("tracking") { tracker.update(objects, motion: motion) }
        let trackIDs = Dictionary(uniqueKeysWithValues: tracked.observations.filter(\.confirmed).map { ($0.detectionIndex, $0.trackID) })
        let allowed = PrivacyPipelineProbe.measure("recognition_schedule") {
            faces.process(image: original, objects: objects, layout: layout, timestamp: start, trackIDs: trackIDs, liveTrackIDs: tracked.liveTrackIDs)
        }
        // High-confidence discoveries are protected on their first frame, including
        // tentative tracks. Low-confidence detections need an existing track.
        let continued = Set(tracked.observations.map(\.detectionIndex))
        let protectedIndices = objects.indices.filter { objects[$0].score >= PrivacyBoTSORT.highThreshold || continued.contains($0) }
        let instances = try PrivacySegmentation.instanceMasks(detections: protectedIndices.map { objects[$0] },
                                                              prototypes: PrivacySegmentation.floats(proto, shape: [1, 32, 160, 160]))
        let indexedMasks = Dictionary(uniqueKeysWithValues: zip(protectedIndices, instances))
        let bytes = try stabilizer.apply(indexedMasks, frame: tracked, allowed: allowed, approvedTrackIDs: faces.approvedTrackIDs)
        let masked = ProcessInfo.processInfo.systemUptime
        let mask = try PrivacyMask.modelImage(bytes: bytes)
            .transformed(by: CGAffineTransform(scaleX: 4, y: 4))
            .transformed(by: CGAffineTransform(translationX: -layout.left, y: -layout.bottom))
            .transformed(by: CGAffineTransform(scaleX: size.width / layout.resized.width,
                                              y: size.height / layout.resized.height))
            .cropped(to: original.extent)
        let blurred = try broadcastStyleBlur(original)
        let result = blurred.applyingFilter("CIBlendWithMask", parameters: [kCIInputBackgroundImageKey: original,
                                                                          kCIInputMaskImageKey: mask])
        let rendered = try render(result)
        let completed = ProcessInfo.processInfo.systemUptime
        return (rendered, protectedIndices.count, PrivacyTimings(prepare: (prepared - start) * 1000,
                                                       inference: (inferred - prepared) * 1000,
                                                       mask: (masked - inferred) * 1000,
                                                       render: (completed - masked) * 1000), faces.snapshot)
    }

    /// CIGaussianBlur's radius is the Gaussian sigma. The AI server's nominal mosaic
    /// is sigma 24 at full resolution (pixel_size 2, then sigma 12). On a 1080p
    /// on-device preview that still leaves eyes, glasses, and the mouth. The broadcast
    /// screen shows a smooth blur that removes those features, which matches about
    /// sigma 72 on a 1080-short-side frame. Scale with the short side so 720p and
    /// 1080p keep the same strength relative to the picture.
    // Match Android's quarter-resolution blur while keeping the full-resolution sigma.
    static let blurReduction: CGFloat = 4
    static let broadcastBlurSigmaPerShortSide: CGFloat = 1.0 / 15

    static func broadcastBlurSigma(for extent: CGRect) -> CGFloat {
        let shortSide = min(extent.width, extent.height)
        return max(48, (shortSide * broadcastBlurSigmaPerShortSide).rounded())
    }

    func broadcastStyleBlur(_ original: CIImage, reduction: CGFloat = PrivacyModel.blurReduction) throws -> CIImage {
        guard reduction == 2 || reduction == 4 else { throw PrivacyModelError.outputContract }
        let extent = original.extent
        let sigma = Self.broadcastBlurSigma(for: extent)
        let reducedWidth = max(1, Int((extent.width / reduction).rounded(.up)))
        let reducedHeight = max(1, Int((extent.height / reduction).rounded(.up)))
        let reducedExtent = CGRect(x: 0, y: 0, width: reducedWidth, height: reducedHeight)
        let reduced = original.transformed(by: CGAffineTransform(
            scaleX: reducedExtent.width / extent.width,
            y: reducedExtent.height / extent.height
        ))
        if blurBuffer.map({ CVPixelBufferGetWidth($0) != reducedWidth || CVPixelBufferGetHeight($0) != reducedHeight }) ?? true {
            var buffer: CVPixelBuffer?
            guard CVPixelBufferCreate(kCFAllocatorDefault, reducedWidth, reducedHeight, kCVPixelFormatType_32BGRA,
                                      [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer) == kCVReturnSuccess,
                  let buffer else { throw PrivacyModelError.imageBuffer }
            blurBuffer = buffer
        }
        guard let buffer = blurBuffer else { throw PrivacyModelError.imageBuffer }
        context.render(reduced, to: buffer, bounds: reducedExtent, colorSpace: colorSpace)
        let blurred = CIImage(cvPixelBuffer: buffer).clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: sigma / reduction])
            .cropped(to: reducedExtent)
        return blurred.transformed(by: CGAffineTransform(
            scaleX: extent.width / reducedExtent.width,
            y: extent.height / reducedExtent.height
        )).cropped(to: extent)
    }
}
