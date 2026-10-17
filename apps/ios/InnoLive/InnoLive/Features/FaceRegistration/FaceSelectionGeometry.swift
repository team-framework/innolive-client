import CoreGraphics

nonisolated struct FaceSelectionCandidate: Equatable, Sendable {
    /// Upright frame coordinates normalized with a top-left origin.
    let rect: CGRect
    let isSelectable: Bool
}

/// Coordinates shared by face detection, the aspect-fill preview, and the registration crop.
nonisolated enum FaceSelectionGeometry {
    /// Faces smaller than this in source pixels give poor registration embeddings.
    static let minimumFaceSide: CGFloat = 100
    /// The registration preview is a square aspect-fill view.
    static let previewAspectRatio: CGFloat = 1
    /// Leaves the 1.5x square used by the face recognizer inside the crop.
    static let registrationCropScale: CGFloat = 2
    /// Covers hair and chin around a neighbor's tight face box.
    static let maskedFaceScale: CGFloat = 1.3
    /// Vision boxes hug the face; the bracket frames the whole head.
    static let bracketScale: CGFloat = 1.25

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

    /// Normalized part of the upright frame that an aspect-fill preview shows.
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

    /// Core Image pixel rect (bottom-left origin) scaled around the face center.
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
