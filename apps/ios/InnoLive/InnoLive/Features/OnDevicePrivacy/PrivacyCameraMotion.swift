import CoreImage
import PrivacyTracking

nonisolated final class PrivacyCameraMotion {
    private let flow = PrivacySparseFlow()
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()

    func reset() { flow.reset() }

    func estimate(_ image: CIImage, layout: PrivacySegmentation.Letterbox) -> CGAffineTransform {
        let width = Int(image.extent.width), height = Int(image.extent.height)
        guard width > 0, height > 0 else { return .identity }
        var pixels = Data(count: width * height * 4)
        // CIContext's bitmap output is already top-row-first, as OpenCV expects.
        let topLeft = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
        pixels.withUnsafeMutableBytes { buffer in
            context.render(topLeft, toBitmap: buffer.baseAddress!, rowBytes: width * 4,
                           bounds: CGRect(x: 0, y: 0, width: width, height: height), format: .BGRA8, colorSpace: colorSpace)
        }
        let warp = flow.estimateBGRA(pixels, width: width, height: height, bytesPerRow: width * 4)
        return Self.modelTransform(warp, imageSize: image.extent.size, layout: layout)
    }

    static func modelTransform(_ warp: CGAffineTransform, imageSize: CGSize,
                               layout: PrivacySegmentation.Letterbox) -> CGAffineTransform {
        let sx = layout.resized.width / imageSize.width, sy = layout.resized.height / imageSize.height
        let left = layout.left, top = CGFloat(640) - layout.resized.height - layout.bottom
        // L * warp * inverse(L), where L is the detector's top-left letterbox transform.
        let b = warp.b * sy / sx, c = warp.c * sx / sy
        return CGAffineTransform(a: warp.a, b: b, c: c, d: warp.d,
                                 tx: sx * warp.tx + left - warp.a * left - c * top,
                                 ty: sy * warp.ty + top - b * left - warp.d * top)
    }
}
