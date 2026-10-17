import SwiftUI

/// Camera-focus style corners around a face.
struct FaceBracketShape: Shape {
    func path(in rect: CGRect) -> Path {
        let length = min(rect.width, rect.height) * 0.26
        let radius = min(length * 0.45, 10)
        var path = Path()
        let corners: [(CGPoint, CGFloat, CGFloat)] = [
            (CGPoint(x: rect.minX, y: rect.minY), 1, 1),
            (CGPoint(x: rect.maxX, y: rect.minY), -1, 1),
            (CGPoint(x: rect.maxX, y: rect.maxY), -1, -1),
            (CGPoint(x: rect.minX, y: rect.maxY), 1, -1)
        ]
        for (corner, dx, dy) in corners {
            path.move(to: CGPoint(x: corner.x, y: corner.y + dy * length))
            path.addLine(to: CGPoint(x: corner.x, y: corner.y + dy * radius))
            path.addQuadCurve(to: CGPoint(x: corner.x + dx * radius, y: corner.y), control: corner)
            path.addLine(to: CGPoint(x: corner.x + dx * length, y: corner.y))
        }
        return path
    }
}

/// Live brackets over the camera preview. Only faces large enough to register are drawn.
struct FaceSelectionOverlay: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let faces: [FaceSelectionCandidate]
    let imageSize: CGSize
    let onSelect: (Int) -> Void

    var body: some View {
        GeometryReader { proxy in
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { context in
                let opacity = bracketOpacity(at: context.date)
                // Every bracket is positioned in the full preview, so stack them, not list them.
                ZStack {
                    ForEach(Array(faces.enumerated()), id: \.offset) { index, face in
                        if face.isSelectable {
                            bracket(index: index, face: face, viewSize: proxy.size, opacity: opacity)
                        }
                    }
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
        .animation(.linear(duration: 0.12), value: faces)
    }

    private func bracket(index: Int, face: FaceSelectionCandidate, viewSize: CGSize, opacity: Double) -> some View {
        let rect = FaceSelectionGeometry.scaled(
            FaceSelectionGeometry.displayRect(for: face.rect, imageSize: imageSize, in: viewSize),
            by: FaceSelectionGeometry.bracketScale
        )
        let chipBelow = rect.maxY + 30 <= viewSize.height
        return ZStack {
            Button {
                onSelect(index)
            } label: {
                FaceBracketShape()
                    .stroke(.white, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                    .shadow(color: .black.opacity(0.45), radius: 2)
                    .opacity(opacity)
                    .frame(width: rect.width, height: rect.height)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .position(x: rect.midX, y: rect.midY)
            .accessibilityLabel(String(localized: "얼굴 \(index + 1) 선택"))

            Text(String(localized: "탭해서 등록"))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(.black.opacity(0.55), in: Capsule())
                .fixedSize()
                .position(x: rect.midX, y: chipBelow ? rect.maxY + 16 : rect.minY - 16)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    private func bracketOpacity(at date: Date) -> Double {
        guard !reduceMotion else { return 1 }
        let period = 1.6
        let phase = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
        return 0.6 + 0.4 * (0.5 + 0.5 * cos(phase * 2 * .pi))
    }
}

/// The frozen frame with everything but the selected face dimmed.
struct FrozenFaceSelectionView: View {
    let image: CGImage
    let faceRect: CGRect

    var body: some View {
        GeometryReader { proxy in
            let imageSize = CGSize(width: image.width, height: image.height)
            let face = FaceSelectionGeometry.scaled(
                FaceSelectionGeometry.displayRect(for: faceRect, imageSize: imageSize, in: proxy.size),
                by: FaceSelectionGeometry.bracketScale
            )
            ZStack {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .scaledToFill()
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .clipped()

                Path { path in
                    path.addRect(CGRect(origin: .zero, size: proxy.size))
                    path.addRoundedRect(in: face, cornerSize: CGSize(width: 14, height: 14))
                }
                .fill(.black.opacity(0.6), style: FillStyle(eoFill: true))

                FaceBracketShape()
                    .stroke(.blue, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                    .frame(width: face.width, height: face.height)
                    .position(x: face.midX, y: face.midY)
            }
        }
        .accessibilityElement()
        .accessibilityLabel(String(localized: "선택한 얼굴"))
    }
}
