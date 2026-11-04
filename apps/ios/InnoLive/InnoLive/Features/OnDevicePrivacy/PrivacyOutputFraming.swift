import CoreImage

/// 보호 처리된 출력만 크롭한다. 검출, 추적, 인식은 카메라 전체 화각을 그대로 쓴다.
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
