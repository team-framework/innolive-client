import SwiftUI

/// Face-shaped outline: the Vision jawline, closed with a curve over the forehead.
enum FaceOutline {
    /// Keeps the line just outside the face edge instead of cutting through the cheeks.
    private static let margin: CGFloat = 1.1

    static func path(for face: FaceSelectionCandidate, imageSize: CGSize, in viewSize: CGSize) -> Path {
        let box = FaceSelectionGeometry.displayRect(for: face.rect, imageSize: imageSize, in: viewSize)
        let center = CGPoint(x: box.midX, y: box.midY)
        let jaw = face.contour.map { point -> CGPoint in
            let display = FaceSelectionGeometry.displayPoint(for: point, imageSize: imageSize, in: viewSize)
            return CGPoint(
                x: center.x + (display.x - center.x) * margin,
                y: center.y + (display.y - center.y) * margin
            )
        }
        guard jaw.count >= 5, let first = jaw.first, let last = jaw.last else {
            return Path(ellipseIn: FaceSelectionGeometry.scaled(box, by: 1.15))
        }

        var path = Path()
        path.move(to: first)
        // Smooth the jaw by curving through the midpoints between Vision's points.
        for index in 1..<(jaw.count - 1) {
            let next = jaw[index + 1]
            let mid = CGPoint(x: (jaw[index].x + next.x) / 2, y: (jaw[index].y + next.y) / 2)
            path.addQuadCurve(to: mid, control: jaw[index])
        }
        path.addLine(to: last)
        // The jaw ends near the eyes; a cubic dome reaches roughly the hairline.
        let top = min(first.y, last.y) - box.height * 0.6
        path.addCurve(
            to: first,
            control1: CGPoint(x: last.x, y: top),
            control2: CGPoint(x: first.x, y: top)
        )
        path.closeSubpath()
        return path
    }
}

/// Live outlines over the original camera. Only faces large enough to register are drawn.
struct FaceSelectionOverlay: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let faces: [FaceSelectionCandidate]
    let imageSize: CGSize
    let onSelect: (Int) -> Void

    var body: some View {
        GeometryReader { proxy in
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { context in
                let opacity = outlineOpacity(at: context.date)
                // Every outline is positioned in the full preview, so stack them, not list them.
                ZStack {
                    ForEach(Array(faces.enumerated()), id: \.offset) { index, face in
                        if face.isSelectable {
                            outline(index: index, face: face, viewSize: proxy.size, opacity: opacity)
                        }
                    }
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
    }

    private func outline(index: Int, face: FaceSelectionCandidate, viewSize: CGSize, opacity: Double) -> some View {
        let path = FaceOutline.path(for: face, imageSize: imageSize, in: viewSize)
        let bounds = path.boundingRect
        let chipBelow = bounds.maxY + 30 <= viewSize.height
        return ZStack {
            path
                .stroke(.white, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                .shadow(color: .black.opacity(0.45), radius: 2)
                .opacity(opacity)
                .allowsHitTesting(false)

            Button {
                onSelect(index)
            } label: {
                Color.clear
                    .frame(width: max(bounds.width, 44), height: max(bounds.height, 44))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .position(x: bounds.midX, y: bounds.midY)
            .accessibilityLabel(String(localized: "얼굴 \(index + 1) 선택"))

            Text(String(localized: "탭해서 등록"))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(.black.opacity(0.55), in: Capsule())
                .fixedSize()
                .position(x: bounds.midX, y: chipBelow ? bounds.maxY + 16 : bounds.minY - 16)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    private func outlineOpacity(at date: Date) -> Double {
        guard !reduceMotion else { return 1 }
        let period = 1.6
        let phase = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
        return 0.6 + 0.4 * (0.5 + 0.5 * cos(phase * 2 * .pi))
    }
}
