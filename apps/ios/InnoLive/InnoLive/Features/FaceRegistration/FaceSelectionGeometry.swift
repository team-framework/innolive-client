import CoreGraphics

nonisolated struct FaceSelectionCandidate: Equatable, Sendable {
    /// 바로 세운 프레임에서 왼쪽 위를 원점으로 정규화한 좌표다.
    let rect: CGRect
    let isSelectable: Bool
    /// 같은 좌표계의 턱선 점들로, 한쪽 귀에서 반대쪽 귀까지 이어진다. Vision이 랜드마크를 찾지 못하면 비어 있다.
    var contour: [CGPoint] = []
}

/// 얼굴 감지, aspect-fill 미리보기, 등록용 crop이 함께 쓰는 좌표 계산이다.
nonisolated enum FaceSelectionGeometry {
    /// 원본 픽셀 기준으로 이보다 작은 얼굴은 등록 임베딩 품질이 떨어진다.
    static let minimumFaceSide: CGFloat = 100
    /// 등록 미리보기는 정사각형 aspect-fill 뷰다.
    static let previewAspectRatio: CGFloat = 1
    /// 얼굴 인식기가 쓰는 1.5x 정사각형이 crop 안에 들어오게 한다.
    static let registrationCropScale: CGFloat = 2
    /// 옆 사람 얼굴에 딱 맞는 박스 둘레의 머리카락과 턱까지 가린다.
    static let maskedFaceScale: CGFloat = 1.3

    static func candidates(
        for rects: [CGRect],
        imageSize: CGSize,
        previewAspectRatio: CGFloat = previewAspectRatio
    ) -> [FaceSelectionCandidate] {
        let visible = visibleRegion(imageSize: imageSize, previewAspectRatio: previewAspectRatio)
        return rects.map { rect in
            let side = min(rect.width * imageSize.width, rect.height * imageSize.height)
            let center = CGPoint(x: rect.midX, y: rect.midY)
            return FaceSelectionCandidate(
                rect: rect,
                isSelectable: side >= minimumFaceSide && visible.contains(center)
            )
        }
    }

    /// 바로 세운 프레임 가운데 aspect-fill 미리보기에 보이는 영역을 정규화 좌표로 구한다.
    static func visibleRegion(imageSize: CGSize, previewAspectRatio: CGFloat) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0, previewAspectRatio > 0 else { return .zero }
        let imageAspectRatio = imageSize.width / imageSize.height
        if imageAspectRatio > previewAspectRatio {
            let width = previewAspectRatio / imageAspectRatio
            return CGRect(x: (1 - width) / 2, y: 0, width: width, height: 1)
        }
        let height = imageAspectRatio / previewAspectRatio
        return CGRect(x: 0, y: (1 - height) / 2, width: 1, height: height)
    }

    static func displayRect(for normalized: CGRect, imageSize: CGSize, in viewSize: CGSize) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0 else { return .zero }
        let scale = max(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        let displayed = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        let origin = CGPoint(
            x: (viewSize.width - displayed.width) / 2,
            y: (viewSize.height - displayed.height) / 2
        )
        return CGRect(
            x: origin.x + normalized.minX * displayed.width,
            y: origin.y + normalized.minY * displayed.height,
            width: normalized.width * displayed.width,
            height: normalized.height * displayed.height
        )
    }

    static func displayPoint(for normalized: CGPoint, imageSize: CGSize, in viewSize: CGSize) -> CGPoint {
        let rect = displayRect(
            for: CGRect(origin: normalized, size: .zero),
            imageSize: imageSize,
            in: viewSize
        )
        return rect.origin
    }

    /// 얼굴 중심을 기준으로 배율을 적용한 Core Image 픽셀 사각형(왼쪽 아래 원점)이다.
    static func pixelRect(for normalized: CGRect, imageSize: CGSize, scale: CGFloat = 1) -> CGRect {
        let width = normalized.width * imageSize.width * scale
        let height = normalized.height * imageSize.height * scale
        let center = CGPoint(
            x: normalized.midX * imageSize.width,
            y: (1 - normalized.midY) * imageSize.height
        )
        return CGRect(x: center.x - width / 2, y: center.y - height / 2, width: width, height: height)
    }

    static func registrationCropRect(around face: CGRect) -> CGRect {
        let side = max(face.width, face.height) * registrationCropScale
        return CGRect(x: face.midX - side / 2, y: face.midY - side / 2, width: side, height: side)
    }

    static func scaled(_ rect: CGRect, by scale: CGFloat) -> CGRect {
        rect.insetBy(dx: -rect.width * (scale - 1) / 2, dy: -rect.height * (scale - 1) / 2)
    }
}
