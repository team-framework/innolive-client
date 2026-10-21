import CoreImage

/// Crop the protected output only. Detector, tracking, and recognition retain the full camera field of view.
nonisolated enum PrivacyOutputFraming {
    static let scale: CGFloat = 1.1

    static func apply(to image: CIImage) -> CIImage {
        let extent = image.extent
        let transform = CGAffineTransform(a: scale, b: 0, c: 0, d: scale,
                                          tx: extent.midX * (1 - scale),
                                          ty: extent.midY * (1 - scale))
        return image.transformed(by: transform).cropped(to: extent)
    }
}
