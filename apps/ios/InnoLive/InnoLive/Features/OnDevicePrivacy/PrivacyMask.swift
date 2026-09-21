import CoreImage

nonisolated enum PrivacyMask {
    /// Keep the original protected area opaque and add a soft outer edge.
    /// Input includes the current mask and any recent edge aligned by PrivacyMaskStabilizer.
    static func modelImage(bytes: [UInt8], feathered: Bool = true) throws -> CIImage {
        let size = PrivacySegmentation.maskSize
        guard bytes.count == size * size,
              let provider = CGDataProvider(data: Data(bytes) as CFData),
              let bitmap = CGImage(width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 8,
                                   bytesPerRow: size, space: CGColorSpaceCreateDeviceGray(),
                                   bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider,
                                   decode: nil, shouldInterpolate: false, intent: .defaultIntent) else {
            throw PrivacyModelError.imageBuffer
        }
        let source = CIImage(cgImage: bitmap)
        let core = source.applyingFilter("CIMorphologyMaximum", parameters: ["inputRadius": 2])
        guard feathered else { return core.cropped(to: source.extent) }
        let outer = source.clampedToExtent()
            .applyingFilter("CIMorphologyMaximum", parameters: ["inputRadius": 4])
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 1.5])
        return outer.applyingFilter("CIMaximumCompositing", parameters: [kCIInputBackgroundImageKey: core])
            .cropped(to: source.extent)
    }
}
