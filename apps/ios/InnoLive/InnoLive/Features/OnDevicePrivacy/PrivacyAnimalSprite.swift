import CoreImage
import Foundation

nonisolated enum PrivacyAnimal: String, CaseIterable, Identifiable {
    case blur, rabbit, owl
    static let storageKey = "com.framework.innolive.privacy-animal"
    var id: String { rawValue }
    var title: String { switch self { case .blur: "블러"; case .rabbit: "토끼"; case .owl: "부엉이" } }
    static var selected: Self {
        guard AIProcessingMode.selected == .onDevice else { return .blur }
        return Self(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .blur
    }
}

/// Blender-rendered yaw views of one animal. Every cell is the same fixed orthographic camera frame,
/// so a view change only moves the face rectangle. Version 1 front-only sprites load as one view.
nonisolated struct PrivacyAnimalAtlas {
    /// `cell` is atlas pixels and `face` is normalized to its cell, both with a top-left origin.
    struct View { let yaw: Double; let cell: CGRect; let face: CGRect }
    /// The canvas-normalized top-left `anchor` lands on `center`; the canvas is rotated by `roll`
    /// (radians, clockwise on screen). `anchor.x` is already mirrored.
    struct Placement {
        let from: View, to: View, blend: Double, mirrored: Bool
        let center: CGPoint, canvas: CGSize, anchor: CGPoint, roll: Double
    }

    let size: CGSize
    let views: [View]
    let mirrorNegativeYaw: Bool

    private struct Metadata: Decodable {
        struct Entry: Decodable { let yaw: Double; let cell: [Double]; let faceRect: [Double] }
        let width: Int; let height: Int; let faceRect: [Double]?; let views: [Entry]?; let mirrorNegativeYaw: Bool?
    }

    init?(json: Data, imageSize: CGSize) {
        guard let metadata = try? JSONDecoder().decode(Metadata.self, from: json),
              CGFloat(metadata.width) == imageSize.width, CGFloat(metadata.height) == imageSize.height else { return nil }
        func normalized(_ f: [Double]) -> CGRect? {
            guard f.count == 4, f.allSatisfy({ $0.isFinite && $0 >= 0 && $0 <= 1 }), f[2] > 0, f[3] > 0,
                  f[0] + f[2] <= 1, f[1] + f[3] <= 1 else { return nil }
            return CGRect(x: f[0], y: f[1], width: f[2], height: f[3])
        }
        var views: [View] = []
        if let entries = metadata.views {
            for entry in entries {
                let c = entry.cell
                guard c.count == 4, c[0] >= 0, c[1] >= 0, c[2] > 0, c[3] > 0, c[0] + c[2] <= imageSize.width,
                      c[1] + c[3] <= imageSize.height, let face = normalized(entry.faceRect) else { return nil }
                views.append(View(yaw: entry.yaw, cell: CGRect(x: c[0], y: c[1], width: c[2], height: c[3]), face: face))
            }
        } else if let face = metadata.faceRect.flatMap(normalized) {
            views = [View(yaw: 0, cell: CGRect(origin: .zero, size: imageSize), face: face)]
        }
        guard let first = views.first, first.yaw == 0, zip(views, views.dropFirst()).allSatisfy({ $0.yaw < $1.yaw }),
              views.allSatisfy({ $0.cell.size == first.cell.size }) else { return nil }
        size = imageSize
        self.views = views
        mirrorNegativeYaw = metadata.views != nil && metadata.mirrorNegativeYaw == true
    }

    func place(box: CGRect, yaw: Double, roll: Double) -> Placement? {
        guard box.width > 0, box.height > 0, yaw.isFinite, roll.isFinite, let last = views.last else { return nil }
        let mirrored = mirrorNegativeYaw && yaw < 0
        let magnitude = min(max(mirrorNegativeYaw ? abs(yaw) : yaw, 0), last.yaw)
        let lower = views.lastIndex(where: { $0.yaw <= magnitude }) ?? 0
        let upper = min(lower + 1, views.count - 1)
        let t = upper == lower ? 0 : (magnitude - views[lower].yaw) / (views[upper].yaw - views[lower].yaw)
        // One crisp view almost everywhere; a wide cross-fade would ghost both views' eyes at the midpoint.
        let s = min(max((t - 0.45) / 0.1, 0), 1), blend = s * s * (3 - 2 * s)
        let a = views[lower].face, b = views[upper].face, cell = views[lower].cell.size
        func mix(_ x: CGFloat, _ y: CGFloat) -> CGFloat { x + (y - x) * blend }
        // Uniform scale never squeezes the animal and still covers the whole tracked face box.
        let scale = max(box.width / (mix(a.width, b.width) * cell.width), box.height / (mix(a.height, b.height) * cell.height))
        let anchorX = mix(a.midX, b.midX)
        return Placement(from: views[lower], to: views[upper], blend: blend, mirrored: mirrored,
                         center: CGPoint(x: box.midX, y: box.midY),
                         canvas: CGSize(width: cell.width * scale, height: cell.height * scale),
                         anchor: CGPoint(x: mirrored ? 1 - anchorX : anchorX, y: mix(a.midY, b.midY)), roll: roll)
    }
}

/// Head pose from YuNet's five landmarks; the animal only needs a stable yaw view and in-plane roll.
nonisolated enum PrivacyAnimalPose {
    /// Nose tip depth in front of the eye line, in interocular distances, for an average adult face.
    static let noseDepth = 0.55
    static let maxRoll = Double.pi / 3

    /// Top-left landmarks in YuNet order. The nose shifts along the eye line by depth * sin(yaw)
    /// while the eye distance shrinks by cos(yaw), so their ratio is depth * tan(yaw).
    static func estimate(_ points: [CGPoint]) -> (yaw: Double, roll: Double)? {
        guard points.count == 5, points.allSatisfy({ $0.x.isFinite && $0.y.isFinite }) else { return nil }
        var axis = CGVector(dx: points[1].x - points[0].x, dy: points[1].y - points[0].y)
        // Measure along the eye line pointing toward image right, whichever eye YuNet listed first.
        if axis.dx < 0 { axis = CGVector(dx: -axis.dx, dy: -axis.dy) }
        let distance = hypot(axis.dx, axis.dy)
        guard distance >= 1e-3 else { return nil }
        let roll = atan2(Double(axis.dy), Double(axis.dx))
        guard abs(roll) <= maxRoll else { return nil }
        let nose = CGVector(dx: points[2].x - (points[0].x + points[1].x) / 2, dy: points[2].y - (points[0].y + points[1].y) / 2)
        let along = (nose.dx * axis.dx + nose.dy * axis.dy) / distance
        return (atan2(Double(along / distance), noseDepth) * 180 / .pi, roll)
    }

    /// Per-track exponential smoothing; a track without a new estimate keeps its last pose.
    final class Smoother {
        private var poses: [Int: (yaw: Double, roll: Double)] = [:]
        static let untracked = -1

        func update(_ tracks: [(track: Int, estimate: (yaw: Double, roll: Double)?)]) -> [(yaw: Double, roll: Double)] {
            var seen = Set<Int>()
            let result = tracks.map { entry -> (yaw: Double, roll: Double) in
                guard entry.track != Self.untracked else { return entry.estimate ?? (0, 0) }
                seen.insert(entry.track)
                let next: (yaw: Double, roll: Double)
                switch (poses[entry.track], entry.estimate) {
                case let (previous?, estimate?): next = (previous.yaw + (estimate.yaw - previous.yaw) * 0.5,
                                                         previous.roll + (estimate.roll - previous.roll) * 0.5)
                case let (nil, estimate?): next = estimate
                case let (previous, nil): next = previous ?? (0, 0)
                }
                poses[entry.track] = next
                return next
            }
            poses = poses.filter { seen.contains($0.key) }
            return result
        }

        func reset() { poses.removeAll() }
    }
}

/// Queue-owned compositor. Missing assets always retain the already protected image.
nonisolated final class PrivacyAnimalSprite {
    static let poseInput: CGFloat = 160
    private static let poseMargin: CGFloat = 1.4
    private static let maxPosesPerFrame = 3
    private var unavailable: Set<PrivacyAnimal> = []
    private var cached: [PrivacyAnimal: (CIImage, PrivacyAnimalAtlas)] = [:]
    private let smoother = PrivacyAnimalPose.Smoother()
    private var detector: PrivacyYuNetDetector?
    private var poseUnavailable = false

    private func asset(_ animal: PrivacyAnimal) -> (CIImage, PrivacyAnimalAtlas)? {
        if let asset = cached[animal] { return asset }
        guard !unavailable.contains(animal) else { return nil }
        unavailable.insert(animal)
        guard let png = Bundle.main.url(forResource: animal.rawValue, withExtension: "png"),
              let json = Bundle.main.url(forResource: animal.rawValue, withExtension: "json"),
              let data = try? Data(contentsOf: json), let image = CIImage(contentsOf: png),
              let atlas = PrivacyAnimalAtlas(json: data, imageSize: image.extent.size) else { return nil }
        unavailable.remove(animal)
        cached[animal] = (image, atlas)
        return (image, atlas)
    }

    /// `faces` are bottom-left Core Image boxes with their BoT-SORT track, or `Smoother.untracked`.
    /// With `source`, YuNet landmarks on a small crop of the current frame turn the animal with the head.
    func composite(_ protected: CIImage, faces: [(box: CGRect, track: Int)], source: CIImage? = nil,
                   animal: PrivacyAnimal = .selected) -> CIImage {
        guard animal != .blur, !faces.isEmpty, let (sprite, atlas) = asset(animal) else { return protected }
        // Larger faces get the per-frame landmark budget first.
        let estimated = Set(faces.indices.filter { !faces[$0].box.isEmpty && !faces[$0].box.isNull }
            .sorted { faces[$0].box.width * faces[$0].box.height > faces[$1].box.width * faces[$1].box.height }
            .prefix(Self.maxPosesPerFrame))
        let poses = smoother.update(faces.indices.map { index in
            (faces[index].track, source.flatMap { estimated.contains(index) ? estimate($0, box: faces[index].box) : nil })
        })
        return zip(faces, poses).reduce(protected) { output, item in
            guard let place = atlas.place(box: item.0.box, yaw: item.1.yaw, roll: item.1.roll) else { return output }
            return layer(sprite, atlas, place).composited(over: output).cropped(to: protected.extent)
        }
    }

    func reset() { smoother.reset() }

    private func layer(_ sprite: CIImage, _ atlas: PrivacyAnimalAtlas, _ place: PrivacyAnimalAtlas.Placement) -> CIImage {
        func cell(_ view: PrivacyAnimalAtlas.View) -> CIImage {
            let rect = CGRect(x: view.cell.minX, y: atlas.size.height - view.cell.maxY, width: view.cell.width, height: view.cell.height)
            return sprite.cropped(to: rect).transformed(by: CGAffineTransform(translationX: -rect.minX, y: -rect.minY))
        }
        let size = place.from.cell.size
        var canvas = cell(place.from)
        if place.blend > 0 {
            canvas = canvas.applyingFilter("CIDissolveTransition", parameters: [kCIInputTargetImageKey: cell(place.to),
                                                                                 kCIInputTimeKey: place.blend])
        }
        if place.mirrored { canvas = canvas.transformed(by: CGAffineTransform(scaleX: -1, y: 1).translatedBy(x: -size.width, y: 0)) }
        let scale = place.canvas.width / size.width, center = place.center
        // Core Image is y-up: the top-left anchor flips, and a clockwise on-screen roll is a negative angle.
        return canvas.transformed(by: CGAffineTransform(scaleX: scale, y: scale)
            .concatenating(CGAffineTransform(translationX: center.x - place.anchor.x * place.canvas.width,
                                             y: center.y - (1 - place.anchor.y) * place.canvas.height))
            .concatenating(CGAffineTransform(translationX: -center.x, y: -center.y))
            .concatenating(CGAffineTransform(rotationAngle: -place.roll))
            .concatenating(CGAffineTransform(translationX: center.x, y: center.y)))
    }

    private func estimate(_ source: CIImage, box: CGRect) -> (yaw: Double, roll: Double)? {
        guard !poseUnavailable else { return nil }
        let side = max(box.width, box.height) * Self.poseMargin
        let square = CGRect(x: box.midX - side / 2, y: box.midY - side / 2, width: side, height: side)
        let crop = source.cropped(to: square).composited(over: CIImage(color: .black).cropped(to: square))
            .transformed(by: CGAffineTransform(translationX: -square.minX, y: -square.minY)
                .concatenating(CGAffineTransform(scaleX: Self.poseInput / side, y: Self.poseInput / side)))
        do {
            let model = try detector ?? PrivacyYuNetDetector()
            detector = model
            let face = try model.face(in: crop, size: CGSize(width: Self.poseInput, height: Self.poseInput), enrollment: false)
            return PrivacyAnimalPose.estimate(face.landmarks)
        } catch is PrivacyFaceSampleError {
            return nil
        } catch {
            // The animal keeps the front view; the privacy blur underneath is unchanged.
            poseUnavailable = true
            return nil
        }
    }
}
