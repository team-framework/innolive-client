import CoreML
import CoreImage
import CoreVideo

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
    private let input: CVPixelBuffer
    private let inputName: String
    private let detectionsName: String
    private let prototypesName: String
    private let stabilizer = PrivacyMaskStabilizer()
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

    func resetTemporalState() { stabilizer.reset(); faces.reset() }

    func process(_ pixelBuffer: CVPixelBuffer) throws -> (CGImage, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        return try process(CIImage(cvPixelBuffer: pixelBuffer))
    }

    func process(_ original: CIImage) throws -> (CGImage, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        let start = ProcessInfo.processInfo.systemUptime
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
        let allowed = faces.process(image: original, objects: objects, layout: layout, timestamp: start)
        let protected = objects.enumerated().filter { !allowed.contains($0.offset) }.map(\.element)
        let instances = try PrivacySegmentation.instanceMasks(detections: protected,
                                                              prototypes: PrivacySegmentation.floats(proto, shape: [1, 32, 160, 160]))
        let bytes = try stabilizer.apply(instances, timestamp: start)
        let masked = ProcessInfo.processInfo.systemUptime
        let mask = try PrivacyMask.modelImage(bytes: bytes)
            .transformed(by: CGAffineTransform(scaleX: 4, y: 4))
            .transformed(by: CGAffineTransform(translationX: -layout.left, y: -layout.bottom))
            .transformed(by: CGAffineTransform(scaleX: size.width / layout.resized.width,
                                              y: size.height / layout.resized.height))
            .cropped(to: original.extent)
        let blurred = broadcastStyleBlur(original)
        let result = blurred.applyingFilter("CIBlendWithMask", parameters: [kCIInputBackgroundImageKey: original,
                                                                          kCIInputMaskImageKey: mask])
        guard let rendered = context.createCGImage(result, from: original.extent) else {
            throw PrivacyModelError.imageBuffer
        }
        let completed = ProcessInfo.processInfo.systemUptime
        return (rendered, objects.count, PrivacyTimings(prepare: (prepared - start) * 1000,
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
    static let mosaicPixelSize: CGFloat = 2
    static let broadcastBlurSigmaPerShortSide: CGFloat = 1.0 / 15

    static func broadcastBlurSigma(for extent: CGRect) -> CGFloat {
        let shortSide = min(extent.width, extent.height)
        return max(48, (shortSide * broadcastBlurSigmaPerShortSide).rounded())
    }

    private func broadcastStyleBlur(_ original: CIImage) -> CIImage {
        let extent = original.extent
        let sigma = Self.broadcastBlurSigma(for: extent)
        let reducedWidth = max(1, Int((extent.width / Self.mosaicPixelSize).rounded(.up)))
        let reducedHeight = max(1, Int((extent.height / Self.mosaicPixelSize).rounded(.up)))
        let reducedExtent = CGRect(x: 0, y: 0, width: reducedWidth, height: reducedHeight)
        let reduced = original.transformed(by: CGAffineTransform(
            scaleX: reducedExtent.width / extent.width,
            y: reducedExtent.height / extent.height
        ))
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, reducedWidth, reducedHeight, kCVPixelFormatType_32BGRA,
                            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
        guard let buffer else { return original }
        context.render(reduced, to: buffer, bounds: reducedExtent, colorSpace: colorSpace)
        let blurred = CIImage(cvPixelBuffer: buffer).clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: sigma / Self.mosaicPixelSize])
            .cropped(to: reducedExtent)
        return blurred.transformed(by: CGAffineTransform(
            scaleX: extent.width / reducedExtent.width,
            y: extent.height / reducedExtent.height
        )).cropped(to: extent)
    }
}
