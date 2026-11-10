import CoreImage

nonisolated enum PrivacyMask {
    static let protectedEdgeCells = 1
    static let maxEdgeCells = 12

    /// Android의 px→cells 계산을 그대로 써서 온디바이스 마스크가 서버와 같은 비율로 넓어지게 한다.
    static func edgeCells(width: CGFloat, height: CGFloat, marginPx: Int) -> Int {
        let cellPx = max(width, height) / CGFloat(PrivacySegmentation.maskSize)
        guard cellPx > 0 else { return protectedEdgeCells }
        let cells = Int((CGFloat(marginPx) / cellPx).rounded())
        return min(max(cells, protectedEdgeCells), maxEdgeCells)
    }

    /// 원래 보호 영역은 불투명하게 유지하고 바깥쪽에 부드러운 가장자리를 더한다.
    /// 입력에는 현재 마스크와, PrivacyMaskStabilizer가 위치를 맞춘 최근 가장자리가 함께 들어 있다.
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
        // 불투명한 중심부는 선택한 너비를 따르고, 1셀보다 작아지지 않는다.
        let coreCells = max(edgeCells, protectedEdgeCells)
        let source = CIImage(cgImage: bitmap)
        let core = source.applyingFilter("CIMorphologyMaximum", parameters: ["inputRadius": coreCells])
        guard feathered else { return core.cropped(to: source.extent) }
        let outer = source.clampedToExtent()
            .applyingFilter("CIMorphologyMaximum", parameters: ["inputRadius": coreCells + 1])
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 1])
        return outer.applyingFilter("CIMaximumCompositing", parameters: [kCIInputBackgroundImageKey: core])
            .cropped(to: source.extent)
    }

    static let modelScale: CGFloat = CGFloat(PrivacySegmentation.inputSize / PrivacySegmentation.maskSize)
    static let edgeSmoothingSigma: CGFloat = 2.5

    /// 160 격자를 모델 좌표로 키운 뒤 경계를 흐려 격자 한 칸 크기의 계단을 없앤다.
    /// sigma 2.5는 격자 한 칸(4px)보다 작아서 원래 마스크 영역은 거의 불투명하게 남는다.
    static func smoothedModelImage(_ image: CIImage) -> CIImage {
        let side = CGFloat(PrivacySegmentation.inputSize)
        return image.transformed(by: CGAffineTransform(scaleX: modelScale, y: modelScale))
            .clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: edgeSmoothingSigma])
            .cropped(to: CGRect(x: 0, y: 0, width: side, height: side))
    }
}
