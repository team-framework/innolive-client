import CoreImage
import CoreVideo
@preconcurrency import LiveKitWebRTC

/// Processes one frame at a time on the caller's serial video-processing queue.
nonisolated final class VideoColorFrameProcessor: @unchecked Sendable {
    enum ProcessingError: Error {
        case invalidAdjustment
        case unsupportedBuffer
        case invalidImage
        case unavailableFilter
        case pixelBufferPool(CVReturn)
        case pixelBuffer(CVReturn)
    }

    private struct Dimensions: Hashable {
        let width: Int
        let height: Int
    }

    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    private var pools: [Dimensions: CVPixelBufferPool] = [:]

    func process(_ frame: LKRTCVideoFrame, warmth: Float, saturation: Float) throws -> LKRTCVideoFrame {
        guard warmth.isFinite, saturation.isFinite else { throw ProcessingError.invalidAdjustment }
        let clampedWarmth = min(max(warmth, -1), 1)
        let clampedSaturation = min(max(saturation, 0), 2)
        if clampedWarmth == 0, clampedSaturation == 1 { return frame }

        guard let source = frame.buffer as? LKRTCCVPixelBuffer else { throw ProcessingError.unsupportedBuffer }
        let sourceBuffer = source.pixelBuffer
        let dimensions = Dimensions(width: CVPixelBufferGetWidth(sourceBuffer), height: CVPixelBufferGetHeight(sourceBuffer))
        guard dimensions.width > 0, dimensions.height > 0 else { throw ProcessingError.invalidImage }

        var image = CIImage(cvPixelBuffer: sourceBuffer)
        let extent = image.extent
        guard extent.origin.x.isFinite, extent.origin.y.isFinite,
              extent.width.isFinite, extent.height.isFinite,
              !extent.isEmpty else { throw ProcessingError.invalidImage }
        if clampedWarmth != 0 {
            guard let filter = CIFilter(name: "CITemperatureAndTint", parameters: [
                kCIInputImageKey: image,
                "inputNeutral": CIVector(x: 6_500, y: 0),
                "inputTargetNeutral": CIVector(x: 6_500 - CGFloat(clampedWarmth) * 2_000, y: 0)
            ]), let output = filter.outputImage else { throw ProcessingError.unavailableFilter }
            image = output
        }
        if clampedSaturation != 1 {
            guard let filter = CIFilter(name: "CIColorControls", parameters: [
                kCIInputImageKey: image,
                kCIInputSaturationKey: clampedSaturation
            ]), let output = filter.outputImage else { throw ProcessingError.unavailableFilter }
            image = output
        }

        let bounds = CGRect(x: 0, y: 0, width: dimensions.width, height: dimensions.height)
        image = image.cropped(to: bounds)
        guard image.extent == bounds else { throw ProcessingError.invalidImage }

        let pool = try pixelBufferPool(for: dimensions)
        var output: CVPixelBuffer?
        let status = CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &output)
        guard status == kCVReturnSuccess, let output else { throw ProcessingError.pixelBuffer(status) }
        let destination = CIRenderDestination(pixelBuffer: output)
        destination.colorSpace = colorSpace
        let task = try context.startTask(toRender: image, to: destination)
        _ = try task.waitUntilCompleted()

        let result = LKRTCVideoFrame(
            buffer: LKRTCCVPixelBuffer(pixelBuffer: output),
            rotation: frame.rotation,
            timeStampNs: frame.timeStampNs
        )
        result.timeStamp = frame.timeStamp
        return result
    }

    private func pixelBufferPool(for dimensions: Dimensions) throws -> CVPixelBufferPool {
        if let pool = pools[dimensions] { return pool }
        let attributes: [CFString: Any] = [
            kCVPixelBufferWidthKey: dimensions.width,
            kCVPixelBufferHeightKey: dimensions.height,
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [:],
            kCVPixelBufferMetalCompatibilityKey: true
        ]
        var pool: CVPixelBufferPool?
        let status = CVPixelBufferPoolCreate(
            kCFAllocatorDefault,
            [kCVPixelBufferPoolMinimumBufferCountKey: 2] as CFDictionary,
            attributes as CFDictionary,
            &pool
        )
        guard status == kCVReturnSuccess, let pool else { throw ProcessingError.pixelBufferPool(status) }
        pools[dimensions] = pool
        return pool
    }
}
