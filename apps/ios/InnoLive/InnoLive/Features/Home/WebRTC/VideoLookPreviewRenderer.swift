import CoreImage
import CoreVideo
import Darwin
import ImageIO

/// Makes small upright stills from an unprocessed camera buffer.
/// Capture callbacks must finish using the source buffer before returning.
nonisolated final class VideoLookPreviewRenderer: @unchecked Sendable {
    static let shared = VideoLookPreviewRenderer()

    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()
    private let maxPixelSize: CGFloat = 960

    func makeBaseImage(pixelBuffer: CVPixelBuffer, rotation: Int) -> CGImage? {
        let oriented = CIImage(cvPixelBuffer: pixelBuffer).oriented(Self.orientation(rotation))
        return render(scaled(oriented))
    }

    func makePreview(
        from base: CGImage,
        warmth: Float,
        saturation: Float,
        relativeExposureEV: Float
    ) -> CGImage? {
        let warmth = min(max(warmth, -1), 1)
        let saturation = min(max(saturation, 0), 2)
        let exposure = min(max(relativeExposureEV, -4), 4)
        guard warmth.isFinite, saturation.isFinite, exposure.isFinite else { return nil }
        if warmth == 0, saturation == 1, exposure == 0 {
            return base
        }

        var image = CIImage(cgImage: base)
        if exposure != 0 {
            guard let filter = CIFilter(name: "CIExposureAdjust", parameters: [
                kCIInputImageKey: image,
                kCIInputEVKey: exposure
            ]), let output = filter.outputImage else { return nil }
            image = output
        }
        if warmth != 0 {
            guard let filter = CIFilter(name: "CITemperatureAndTint", parameters: [
                kCIInputImageKey: image,
                "inputNeutral": CIVector(x: 6_500, y: 0),
                "inputTargetNeutral": CIVector(x: 6_500 - CGFloat(warmth) * 2_000, y: 0)
            ]), let output = filter.outputImage else { return nil }
            image = output
        }
        if saturation != 1 {
            guard let filter = CIFilter(name: "CIColorControls", parameters: [
                kCIInputImageKey: image,
                kCIInputSaturationKey: saturation
            ]), let output = filter.outputImage else { return nil }
            image = output
        }
        let extent = CGRect(x: 0, y: 0, width: base.width, height: base.height)
        return render(image.cropped(to: extent))
    }

    private func scaled(_ image: CIImage) -> CIImage {
        let extent = image.extent
        guard extent.width.isFinite, extent.height.isFinite, extent.width > 0, extent.height > 0 else {
            return image
        }
        let longEdge = max(extent.width, extent.height)
        let scale = longEdge > maxPixelSize ? maxPixelSize / longEdge : 1
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return scaled.transformed(by: CGAffineTransform(
            translationX: -scaled.extent.minX,
            y: -scaled.extent.minY
        ))
    }

    private func render(_ image: CIImage) -> CGImage? {
        let extent = image.extent.integral
        guard extent.width > 1, extent.height > 1 else { return nil }
        return context.createCGImage(image, from: extent, format: .RGBA8, colorSpace: colorSpace)
    }

    func ownedCopy(of source: CVPixelBuffer) -> CVPixelBuffer? {
        let width = CVPixelBufferGetWidth(source)
        let height = CVPixelBufferGetHeight(source)
        guard width > 0, height > 0 else { return nil }
        var destination: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            CVPixelBufferGetPixelFormatType(source),
            [
                kCVPixelBufferIOSurfacePropertiesKey: [:],
                kCVPixelBufferMetalCompatibilityKey: true
            ] as CFDictionary,
            &destination
        )
        guard status == kCVReturnSuccess, let destination else { return nil }
        CVPixelBufferLockBaseAddress(source, .readOnly)
        CVPixelBufferLockBaseAddress(destination, [])
        defer {
            CVPixelBufferUnlockBaseAddress(destination, [])
            CVPixelBufferUnlockBaseAddress(source, .readOnly)
        }
        if CVPixelBufferIsPlanar(source) {
            let planes = CVPixelBufferGetPlaneCount(source)
            guard CVPixelBufferGetPlaneCount(destination) == planes else { return nil }
            for plane in 0..<planes {
                guard let sourceAddress = CVPixelBufferGetBaseAddressOfPlane(source, plane),
                      let destinationAddress = CVPixelBufferGetBaseAddressOfPlane(destination, plane) else {
                    return nil
                }
                let sourceRow = CVPixelBufferGetBytesPerRowOfPlane(source, plane)
                let destinationRow = CVPixelBufferGetBytesPerRowOfPlane(destination, plane)
                let planeHeight = CVPixelBufferGetHeightOfPlane(source, plane)
                let rowBytes = min(sourceRow, destinationRow)
                for row in 0..<planeHeight {
                    memcpy(
                        destinationAddress.advanced(by: row * destinationRow),
                        sourceAddress.advanced(by: row * sourceRow),
                        rowBytes
                    )
                }
            }
        } else {
            guard let sourceAddress = CVPixelBufferGetBaseAddress(source),
                  let destinationAddress = CVPixelBufferGetBaseAddress(destination) else { return nil }
            let sourceRow = CVPixelBufferGetBytesPerRow(source)
            let destinationRow = CVPixelBufferGetBytesPerRow(destination)
            let rowBytes = min(sourceRow, destinationRow)
            for row in 0..<height {
                memcpy(
                    destinationAddress.advanced(by: row * destinationRow),
                    sourceAddress.advanced(by: row * sourceRow),
                    rowBytes
                )
            }
        }
        return destination
    }

    private static func orientation(_ rotation: Int) -> CGImagePropertyOrientation {
        switch rotation {
        case 90: .right
        case 180: .down
        case 270: .left
        default: .up
        }
    }
}
