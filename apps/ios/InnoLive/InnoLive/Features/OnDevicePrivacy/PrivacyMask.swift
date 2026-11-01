import CoreImage

nonisolated enum PrivacyMask {
    static let protectedEdgeCells = 1
    static let maxEdgeCells = 12

    /// Reuse Android's px→cells math so the on-device mask widens at the same rate as the server.
    static func edgeCells(width: CGFloat, height: CGFloat, marginPx: Int) -> Int {
        let cellPx = max(width, height) / CGFloat(PrivacySegmentation.maskSize)
        guard cellPx > 0 else { return protectedEdgeCells }
        let cells = Int((CGFloat(marginPx) / cellPx).rounded())
        return min(max(cells, protectedEdgeCells), maxEdgeCells)
    }

    /// Keep the original protected area opaque and add a soft outer edge.
    /// Input includes the current mask and any recent edge aligned by PrivacyMaskStabilizer.
    static func modelImage(bytes: [UInt8], feathered: Bool = true, edgeCells: Int = protectedEdgeCells) throws -> CIImage {
        let size = PrivacySegmentation.maskSize
        guard bytes.count == size * size,
              let provider = CGDataProvider(data: Data(bytes) as CFData),
              let bitmap = CGImage(width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 8,
                                   bytesPerRow: size, space: CGColorSpaceCreateDeviceGray(),
                                   bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider,
                                   decode: nil, shouldInterpolate: false, intent: .defaultIntent) else {
            throw PrivacyModelError.imageBuffer
        }
        // Like Android, the opaque core follows the chosen width and never drops below the 2-cell baseline.
        let coreCells = max(edgeCells, 2)
        let source = CIImage(cgImage: bitmap)
        let core = source.applyingFilter("CIMorphologyMaximum", parameters: ["inputRadius": coreCells])
        guard feathered else { return core.cropped(to: source.extent) }
        let outer = source.clampedToExtent()
            .applyingFilter("CIMorphologyMaximum", parameters: ["inputRadius": coreCells + 2])
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 1.5])
        return outer.applyingFilter("CIMaximumCompositing", parameters: [kCIInputBackgroundImageKey: core])
            .cropped(to: source.extent)
    }
}
