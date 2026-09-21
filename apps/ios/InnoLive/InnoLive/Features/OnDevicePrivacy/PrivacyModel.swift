#if DEBUG
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

    func process(_ pixelBuffer: CVPixelBuffer) throws -> (CGImage, Int, PrivacyTimings) {
        let start = ProcessInfo.processInfo.systemUptime
        let original = CIImage(cvPixelBuffer: pixelBuffer)
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
        let bytes = try PrivacySegmentation.unionMask(detections: objects,
                                                     prototypes: PrivacySegmentation.floats(proto, shape: [1, 32, 160, 160]))
        let masked = ProcessInfo.processInfo.systemUptime
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let bitmap = CGImage(width: 160, height: 160, bitsPerComponent: 8, bitsPerPixel: 8,
                                   bytesPerRow: 160, space: CGColorSpaceCreateDeviceGray(),
                                   bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider,
                                   decode: nil, shouldInterpolate: false, intent: .defaultIntent) else {
            throw PrivacyModelError.imageBuffer
        }
        let mask = CIImage(cgImage: bitmap)
            .applyingFilter("CIMorphologyMaximum", parameters: ["inputRadius": 2])
            .transformed(by: CGAffineTransform(scaleX: 4, y: 4))
            .transformed(by: CGAffineTransform(translationX: -layout.left, y: -layout.bottom))
            .transformed(by: CGAffineTransform(scaleX: size.width / layout.resized.width,
                                              y: size.height / layout.resized.height))
            .cropped(to: original.extent)
        let blurred = original.clampedToExtent()
            .applyingFilter("CIPixellate", parameters: [kCIInputScaleKey: 24])
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 24])
            .cropped(to: original.extent)
        let result = blurred.applyingFilter("CIBlendWithMask", parameters: [kCIInputBackgroundImageKey: original,
                                                                          kCIInputMaskImageKey: mask])
        guard let rendered = context.createCGImage(result, from: original.extent) else {
            throw PrivacyModelError.imageBuffer
        }
        let completed = ProcessInfo.processInfo.systemUptime
        return (rendered, objects.count, PrivacyTimings(prepare: (prepared - start) * 1000,
                                                       inference: (inferred - prepared) * 1000,
                                                       mask: (masked - inferred) * 1000,
                                                       render: (completed - masked) * 1000))
    }
}
#endif
