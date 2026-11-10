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
    private let uprightPool = PrivacyOutputBufferPool()
    private let input: CVPixelBuffer
    private let inputName: String
    private let detectionsName: String
    private let prototypesName: String
    private let stabilizer = PrivacyMaskStabilizer()
    private let animalSprite = PrivacyAnimalSprite()
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
        guard PrivacyModelContract.detector.accepts(description),
              let imageInput = description.inputDescriptionsByName.first(where: { $0.value.type == .image }),
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

    func process(_ pixelBuffer: CVPixelBuffer, mosaic: OnDeviceMosaicSettings = OnDeviceMosaicSettings()) throws -> (CGImage, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        return try process(CIImage(cvPixelBuffer: pixelBuffer), mosaic: mosaic)
    }

    func process(_ original: CIImage, mosaic: OnDeviceMosaicSettings = OnDeviceMosaicSettings()) throws -> (CGImage, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        try process(original, mosaic: mosaic) { image in
            guard let output = context.createCGImage(image, from: original.extent) else {
                throw PrivacyModelError.imageBuffer
            }
            return output
        }
    }

    /// 전체 프레임을 CGImage로 바꿨다가 되돌리지 않고, 재사용하는 출력 버퍼에 바로 렌더링한다.
    func process(_ original: CIImage, into output: CVPixelBuffer,
                 restoring orientation: CGImagePropertyOrientation,
                 mosaic: OnDeviceMosaicSettings = OnDeviceMosaicSettings()) throws -> (Int, PrivacyTimings, PrivacyFaceSnapshot) {
        let result = try process(original, mosaic: mosaic) { image in
            func draw(_ image: CIImage, into buffer: CVPixelBuffer) {
                let normalized = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
                context.render(normalized, to: buffer,
                               bounds: CGRect(x: 0, y: 0, width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)),
                               colorSpace: colorSpace)
            }
            guard orientation != .up else { return draw(image, into: output) }
            // 회전을 처리 그래프에 합치면 8비트 반올림이 미리보기와 1단계 달라진다.
            // 미리보기처럼 똑바로 8비트 버퍼에 렌더한 뒤, 그 픽셀만 회전해 송출한다.
            let upright = try uprightPool.acquire(width: Int(image.extent.width), height: Int(image.extent.height))
            draw(image, into: upright)
            draw(CIImage(cvPixelBuffer: upright).oriented(orientation), into: output)
        }
        return (result.1, result.2, result.3)
    }

    private func process<Output>(_ original: CIImage, mosaic: OnDeviceMosaicSettings, render: (CIImage) throws -> Output) throws -> (Output, Int, PrivacyTimings, PrivacyFaceSnapshot) {
        do { return try processFrame(original, mosaic: mosaic, render: render) }
        catch { resetTemporalState(); throw error }
    }

    private func processFrame<Output>(_ original: CIImage, mosaic: OnDeviceMosaicSettings, render: (CIImage) throws -> Output) throws -> (Output, Int, PrivacyTimings, PrivacyFaceSnapshot) {
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
        // 신뢰도가 높은 새 검출은 tentative 트랙이어도 첫 프레임부터 보호한다.
        // 신뢰도가 낮은 검출은 기존 트랙이 있어야 보호한다.
        let continued = Set(tracked.observations.map(\.detectionIndex))
        let protectedIndices = objects.indices.filter { objects[$0].score >= PrivacyBoTSORT.highThreshold || continued.contains($0) }
        let instances = try PrivacySegmentation.instanceMasks(detections: protectedIndices.map { objects[$0] },
                                                              prototypes: PrivacySegmentation.floats(proto, shape: [1, 32, 160, 160]))
        let indexedMasks = Dictionary(uniqueKeysWithValues: zip(protectedIndices, instances))
        let bytes = try stabilizer.apply(indexedMasks, frame: tracked, allowed: allowed, approvedTrackIDs: faces.approvedTrackIDs)
        let masked = ProcessInfo.processInfo.systemUptime
        let edgeCells = PrivacyMask.edgeCells(width: original.extent.width, height: original.extent.height, marginPx: mosaic.maskMargin)
        let mask = try PrivacyMask.modelImage(bytes: bytes, edgeCells: edgeCells)
            .transformed(by: CGAffineTransform(scaleX: 4, y: 4))
            .transformed(by: CGAffineTransform(translationX: -layout.left, y: -layout.bottom))
            .transformed(by: CGAffineTransform(scaleX: size.width / layout.resized.width,
                                              y: size.height / layout.resized.height))
            .cropped(to: original.extent)
        let blurred = try broadcastStyleBlur(original, strength: mosaic.blurStrength)
        var result = blurred.applyingFilter("CIBlendWithMask", parameters: [kCIInputBackgroundImageKey: original,
                                                                          kCIInputMaskImageKey: mask])
        if PrivacyAnimal.selected != .blur {
            let animalBoxes = protectedIndices.filter { objects[$0].classID == 0 && !allowed.contains($0) }.map { index in
                let box = objects[index].box
                return CGRect(x: (box.minX - layout.left) * size.width / layout.resized.width,
                              y: (640 - box.maxY - layout.bottom) * size.height / layout.resized.height,
                              width: box.width * size.width / layout.resized.width,
                              height: box.height * size.height / layout.resized.height)
            }
            result = animalSprite.composite(result, boxes: animalBoxes)
        }
        // 원본 좌표 기준의 AI 처리와 마스크 합성을 모두 마친 뒤에만 프레이밍한다.
        // 로컬 CGImage 미리보기와 CVPixelBuffer 직접 업링크가 이 출력을 함께 쓴다.
        let rendered = try render(PrivacyOutputFraming.apply(to: result.cropped(to: original.extent)))
        let completed = ProcessInfo.processInfo.systemUptime
        return (rendered, protectedIndices.count, PrivacyTimings(prepare: (prepared - start) * 1000,
                                                       inference: (inferred - prepared) * 1000,
                                                       mask: (masked - inferred) * 1000,
                                                       render: (completed - masked) * 1000), faces.snapshot)
    }

    /// CIGaussianBlur의 radius는 가우시안 sigma다. 강도 값은 짧은 변이 1080인 프레임의 sigma이고,
    /// 기본 24는 서버 모자이크 기본값(blur_radius 24)과 같다. 짧은 변에 비례해 조정해서 720p와
    /// 1080p가 화면 대비 같은 강도를 유지하게 한다.
    // Android처럼 1/4 해상도에서 블러를 적용하되, sigma는 원본 해상도 기준을 유지한다.
    static let blurReduction: CGFloat = 4
    static let blurSigmaReferenceShortSide: CGFloat = 1080

    /// 기본값부터 최댓값까지 blurStrengthStep 간격의 단계 중 가장 가까운 값으로 맞춘다.
    static func strengthLevel(_ strength: Int) -> Int {
        let clamped = min(max(strength, OnDeviceMosaicSettings.defaultBlurStrength), OnDeviceMosaicSettings.maxBlurStrength)
        let steps = ((Double(clamped - OnDeviceMosaicSettings.defaultBlurStrength) / Double(OnDeviceMosaicSettings.blurStrengthStep)).rounded())
        return OnDeviceMosaicSettings.defaultBlurStrength + Int(steps) * OnDeviceMosaicSettings.blurStrengthStep
    }

    static func broadcastBlurSigma(for extent: CGRect, strength: Int = OnDeviceMosaicSettings.defaultBlurStrength) -> CGFloat {
        let shortSide = min(extent.width, extent.height)
        return max(1, (shortSide * CGFloat(strengthLevel(strength)) / blurSigmaReferenceShortSide).rounded())
    }

    func broadcastStyleBlur(_ original: CIImage, reduction: CGFloat = PrivacyModel.blurReduction,
                            strength: Int = OnDeviceMosaicSettings.defaultBlurStrength) throws -> CIImage {
        guard reduction == 2 || reduction == 4 else { throw PrivacyModelError.outputContract }
        let extent = original.extent
        let sigma = Self.broadcastBlurSigma(for: extent, strength: strength)
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
