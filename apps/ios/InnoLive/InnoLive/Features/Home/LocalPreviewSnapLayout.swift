import CoreGraphics
import Foundation

enum LocalPreviewPresentation: Equatable {
    case hidden
    case cameraSession
    case webrtcLocal

    static func current(
        isHomeVisible: Bool,
        previewTransition: BroadcastPreviewTransition,
        isCapturingMedia: Bool,
        isReleasingCamera: Bool
    ) -> LocalPreviewPresentation {
        guard isHomeVisible, previewTransition == .none, !isReleasingCamera else {
            return .hidden
        }
        return isCapturingMedia ? .webrtcLocal : .cameraSession
    }
}

enum LocalPreviewCorner: CaseIterable, Equatable {
    case topLeading
    case topTrailing
    case bottomLeading
    case bottomTrailing
}

struct LocalPreviewSnapLayout: Equatable {
    static let defaultHorizontalPadding: CGFloat = 24
    static let defaultTopPadding: CGFloat = 8
    static let defaultBottomPadding: CGFloat = 12
    static let defaultCornerSpacing: CGFloat = 12

    var containerSize: CGSize
    var previewSize: CGSize
    var horizontalPadding: CGFloat
    var topPadding: CGFloat
    var bottomPadding: CGFloat
    var topTrailingReserved: CGSize
    var bottomReservedHeight: CGFloat
    var cornerSpacing: CGFloat

    func origin(for corner: LocalPreviewCorner) -> CGPoint {
        let leadingX = horizontalPadding
        let trailingX = containerSize.width - horizontalPadding - previewSize.width
        let topY = topPadding
        let bottomY = containerSize.height
            - bottomPadding
            - bottomReservedHeight
            - previewSize.height

        let raw: CGPoint
        switch corner {
        case .topLeading:
            raw = CGPoint(x: leadingX, y: topY)
        case .topTrailing:
            raw = CGPoint(
                x: trailingX - topTrailingReserved.width - cornerSpacing,
                y: topY
            )
        case .bottomLeading:
            raw = CGPoint(x: leadingX, y: bottomY)
        case .bottomTrailing:
            raw = CGPoint(x: trailingX, y: bottomY)
        }
        return clampedOrigin(raw)
    }

    func nearestCorner(toPreviewOrigin origin: CGPoint) -> LocalPreviewCorner {
        var nearest = LocalPreviewCorner.topLeading
        var nearestDistance = CGFloat.greatestFiniteMagnitude

        for corner in LocalPreviewCorner.allCases {
            let candidate = self.origin(for: corner)
            let distance = hypot(origin.x - candidate.x, origin.y - candidate.y)
            if distance < nearestDistance {
                nearest = corner
                nearestDistance = distance
            }
        }

        return nearest
    }

    func clampedOrigin(_ origin: CGPoint) -> CGPoint {
        let maxX = max(containerSize.width - previewSize.width, 0)
        let maxY = max(containerSize.height - previewSize.height, 0)
        return CGPoint(
            x: min(max(origin.x, 0), maxX),
            y: min(max(origin.y, 0), maxY)
        )
    }
}
