import CoreImage
import Foundation

/// On-device animal for the lab anonymization method. The lab picker is locked while a session exists,
/// so the animal stays fixed for the session like face swap.
nonisolated enum PrivacyAnimal: String, CaseIterable, Identifiable {
    case blur, rabbit, owl
    var id: String { rawValue }
    static var selected: Self {
        guard AIProcessingMode.selected == .onDevice else { return .blur }
        switch FaceAnonymizationMode.selected {
        case .rabbit: return .rabbit
        case .owl: return .owl
        case .blur, .faceSwap: return .blur
        }
    }
}

/// Blender-rendered yaw views of one animal. Every cell is the same fixed orthographic camera frame. Each view
/// carries the ArcFace 5-point template projected onto the animal head, so a face aligns like the server's InSwapper crop.
nonisolated struct PrivacyAnimalAtlas {
    /// `cell` is atlas pixels; `landmarks` are normalized to the cell, both top-left, in YuNet order.
    struct View { let yaw: Double; let cell: CGRect; let landmarks: [CGPoint] }
    /// Similarity from (mirrored) top-left cell pixels to top-left frame pixels: frame = [a -b; b a] * cell + t.
    struct Placement {
        let from: View, to: View, blend: Double, mirrored: Bool
        let a: Double, b: Double, tx: Double, ty: Double
    }

    let size: CGSize
    let views: [View]
    let mirrorNegativeYaw: Bool

    private struct Metadata: Decodable {
        struct Entry: Decodable { let yaw: Double; let cell: [Double]; let landmarks: [[Double]] }
        let version: Int; let width: Int; let height: Int; let views: [Entry]; let mirrorNegativeYaw: Bool?
    }

    init?(json: Data, imageSize: CGSize) {
        guard let metadata = try? JSONDecoder().decode(Metadata.self, from: json), metadata.version >= 2,
              CGFloat(metadata.width) == imageSize.width, CGFloat(metadata.height) == imageSize.height else { return nil }
        var views: [View] = []
        for entry in metadata.views {
            let c = entry.cell
            guard c.count == 4, c[0] >= 0, c[1] >= 0, c[2] > 0, c[3] > 0, c[0] + c[2] <= imageSize.width,
                  c[1] + c[3] <= imageSize.height, entry.landmarks.count == 5,
                  entry.landmarks.allSatisfy({ $0.count == 2 && $0.allSatisfy { $0.isFinite && $0 >= 0 && $0 <= 1 } }) else { return nil }
            views.append(View(yaw: entry.yaw, cell: CGRect(x: c[0], y: c[1], width: c[2], height: c[3]),
                              landmarks: entry.landmarks.map { CGPoint(x: $0[0], y: $0[1]) }))
        }
        guard let first = views.first, first.yaw == 0, zip(views, views.dropFirst()).allSatisfy({ $0.yaw < $1.yaw }),
              views.allSatisfy({ $0.cell.size == first.cell.size }) else { return nil }
        size = imageSize
        self.views = views
        mirrorNegativeYaw = metadata.mirrorNegativeYaw == true
    }

    /// `landmarks` are top-left frame pixels in YuNet order.
    func place(landmarks: [CGPoint], yaw: Double) -> Placement? {
        guard landmarks.count == 5, yaw.isFinite, let last = views.last else { return nil }
        let mirrored = mirrorNegativeYaw && yaw < 0
        let magnitude = min(max(mirrorNegativeYaw ? abs(yaw) : yaw, 0), last.yaw)
        let lower = views.lastIndex(where: { $0.yaw <= magnitude }) ?? 0
        let upper = min(lower + 1, views.count - 1)
        let t = upper == lower ? 0 : (magnitude - views[lower].yaw) / (views[upper].yaw - views[lower].yaw)
        // One crisp view almost everywhere; a wide cross-fade would ghost both views' eyes at the midpoint.
        let s = min(max((t - 0.45) / 0.1, 0), 1), blend = s * s * (3 - 2 * s)
        let cell = views[lower].cell.size
        var reference: [CGPoint] = []
        for (a, b) in zip(views[lower].landmarks, views[upper].landmarks) {
            let x: CGFloat = (a.x + (b.x - a.x) * blend) * cell.width
            let y: CGFloat = (a.y + (b.y - a.y) * blend) * cell.height
            reference.append(CGPoint(x: x, y: y))
        }
        // A mirrored cell swaps which eye and mouth corner lies on the image-left side.
        if mirrored {
            let source = reference
            reference = [1, 0, 2, 4, 3].map { (index: Int) -> CGPoint in CGPoint(x: cell.width - source[index].x, y: source[index].y) }
        }
        guard let (a, b, tx, ty) = Self.similarity(reference, landmarks) else { return nil }
        return Placement(from: views[lower], to: views[upper], blend: blend, mirrored: mirrored, a: a, b: b, tx: tx, ty: ty)
    }

    /// Least-squares similarity (rotation, uniform scale, translation) mapping `source` onto `target`.
    static func similarity(_ source: [CGPoint], _ target: [CGPoint]) -> (Double, Double, Double, Double)? {
        guard source.count == target.count, !source.isEmpty else { return nil }
        let n = Double(source.count)
        let sx = source.reduce(0) { $0 + Double($1.x) } / n, sy = source.reduce(0) { $0 + Double($1.y) } / n
        let dx = target.reduce(0) { $0 + Double($1.x) } / n, dy = target.reduce(0) { $0 + Double($1.y) } / n
        var norm = 0.0, dot = 0.0, cross = 0.0
        for (p, q) in zip(source, target) {
            let px = Double(p.x) - sx, py = Double(p.y) - sy, qx = Double(q.x) - dx, qy = Double(q.y) - dy
            norm += px * px + py * py; dot += px * qx + py * qy; cross += px * qy - py * qx
        }
        guard norm >= 1e-6 else { return nil }
        let a = dot / norm, b = cross / norm
        guard a.isFinite, b.isFinite, hypot(a, b) >= 1e-6 else { return nil }
        return (a, b, dx - (a * sx - b * sy), dy - (b * sx + a * sy))
    }
}

/// Head yaw from YuNet's five landmarks; only used to pick the animal's yaw view.
nonisolated enum PrivacyAnimalPose {
    /// Nose tip depth in front of the eye line, in interocular distances, for an average adult face.
    static let noseDepth = 0.55

    /// Top-left landmarks in YuNet order. The nose shifts along the eye line by depth * sin(yaw) while the eye
    /// distance shrinks by cos(yaw), so their ratio is depth * tan(yaw). Positive yaw turns the nose toward image right.
    static func yaw(_ points: [CGPoint]) -> Double? {
        guard points.count == 5, points.allSatisfy({ $0.x.isFinite && $0.y.isFinite }) else { return nil }
        var axis = CGVector(dx: points[1].x - points[0].x, dy: points[1].y - points[0].y)
        // Measure along the eye line pointing toward image right, whichever eye YuNet listed first.
        if axis.dx < 0 { axis = CGVector(dx: -axis.dx, dy: -axis.dy) }
        let distance = hypot(axis.dx, axis.dy)
        guard distance >= 1e-3 else { return nil }
        let nose = CGVector(dx: points[2].x - (points[0].x + points[1].x) / 2, dy: points[2].y - (points[0].y + points[1].y) / 2)
        let along = (nose.dx * axis.dx + nose.dy * axis.dy) / distance
        return atan2(Double(along / distance), noseDepth) * 180 / .pi
    }
}

/// Face-swap target rules (innolive-ai `InSwapperRenderer.swap`) applied to on-device animal overlays.
nonisolated enum PrivacyAnimalTargets {
    static let roiPadding: CGFloat = 0.3
    static let minIoU: CGFloat = 0.25
    static let minScore: Float = 0.65
    /// `face_too_small`: a bbox whose shorter side is under 16 px stays blurred.
    static let minFace: CGFloat = 16

    /// Padded integer ROI clipped to the frame (top-left), or nil when the face is too small or outside it.
    static func roi(_ box: CGRect, frame: CGSize) -> CGRect? {
        guard min(box.width, box.height) >= minFace else { return nil }
        let pad = max(box.width, box.height) * roiPadding
        let left = max(0, (box.minX - pad).rounded(.towardZero)), top = max(0, (box.minY - pad).rounded(.towardZero))
        let right = min(frame.width, (box.maxX + pad).rounded(.towardZero)), bottom = min(frame.height, (box.maxY + pad).rounded(.towardZero))
        guard right > left, bottom > top else { return nil }
        return CGRect(x: left, y: top, width: right - left, height: bottom - top)
    }

    /// Landmarks of the candidate overlapping the tracked box by more than `minIoU`, or nil.
    static func match(_ box: CGRect, candidates: [(box: CGRect, landmarks: [CGPoint])]) -> [CGPoint]? {
        var best: [CGPoint]?, bestIoU = minIoU
        for candidate in candidates {
            let overlap = box.intersection(candidate.box)
            let intersection = overlap.isNull ? 0 : overlap.width * overlap.height
            let iou = intersection / max(box.width * box.height + candidate.box.width * candidate.box.height - intersection, 1)
            if iou > bestIoU { best = candidate.landmarks; bestIoU = iou }
        }
        return best
    }
}

/// Per-track landmarks refreshed every `refreshFrames` processed frames, counted like the server's 30-frame
/// metadata cache. Between refreshes they follow the tracked box; a failed refresh keeps the blur until the next one.
nonisolated final class PrivacyAnimalLandmarkCache {
    static let refreshFrames = 3
    private struct Entry { let refreshedAt: Int; let relative: [CGPoint]? }
    private var entries: [Int: Entry] = [:]
    private var frame = 0
    private let refreshFrames: Int

    init(refreshFrames: Int = PrivacyAnimalLandmarkCache.refreshFrames) { self.refreshFrames = refreshFrames }

    /// Starts a frame; tracks no longer present drop their landmarks.
    func beginFrame(liveTracks: Set<Int>) { frame += 1; entries = entries.filter { liveTracks.contains($0.key) } }

    /// Top-left frame landmarks for `track` in `box`, or nil to keep the blur.
    func landmarks(track: Int, box: CGRect, estimate: () -> [CGPoint]?) -> [CGPoint]? {
        let relative: [CGPoint]?
        if let cached = entries[track], frame - cached.refreshedAt < refreshFrames {
            relative = cached.relative
        } else {
            relative = estimate()?.map { CGPoint(x: ($0.x - box.minX) / box.width, y: ($0.y - box.minY) / box.height) }
            entries[track] = Entry(refreshedAt: frame, relative: relative)
        }
        return relative?.map { CGPoint(x: box.minX + $0.x * box.width, y: box.minY + $0.y * box.height) }
    }

    func reset() { entries.removeAll(); frame = 0 }
}

/// Queue-owned compositor. Like face swap, an animal replaces a face only inside its protected mask; untracked,
/// tiny or unmatched faces keep the blur. Missing assets always retain the already protected image.
nonisolated final class PrivacyAnimalSprite {
    static let poseInput: CGFloat = 160
    private var unavailable: Set<PrivacyAnimal> = []
    private var cached: [PrivacyAnimal: (CIImage, PrivacyAnimalAtlas)] = [:]
    private let cache = PrivacyAnimalLandmarkCache()
    private var detector: PrivacyYuNetDetector?
    private var detectorUnavailable = false

    private func asset(_ animal: PrivacyAnimal) -> (CIImage, PrivacyAnimalAtlas)? {
        if let asset = cached[animal] { return asset }
        guard animal != .blur, !unavailable.contains(animal) else { return nil }
        unavailable.insert(animal)
        guard let png = Bundle.main.url(forResource: animal.rawValue, withExtension: "png"),
              let json = Bundle.main.url(forResource: animal.rawValue, withExtension: "json"),
              let data = try? Data(contentsOf: json), let image = CIImage(contentsOf: png),
              let atlas = PrivacyAnimalAtlas(json: data, imageSize: image.extent.size) else { return nil }
        unavailable.remove(animal)
        cached[animal] = (image, atlas)
        return (image, atlas)
    }

    /// `faces` are bottom-left Core Image boxes with their confirmed BoT-SORT track (nil when untracked).
    /// `source` is the unblurred frame for landmarks; `mask` is the protected-region mask used for the blur.
    func composite(_ protected: CIImage, faces: [(box: CGRect, track: Int?)], source: CIImage, mask: CIImage,
                   animal: PrivacyAnimal = .selected) -> CIImage {
        guard animal != .blur, !faces.isEmpty, !detectorUnavailable, let (sprite, atlas) = asset(animal) else { return protected }
        let frame = source.extent.size
        cache.beginFrame(liveTracks: Set(faces.compactMap(\.track)))
        let animals = faces.reduce(protected) { output, face -> CIImage in
            // Untracked faces keep the blur, as face swap does.
            guard let track = face.track else { return output }
            let box = CGRect(x: face.box.minX, y: frame.height - face.box.maxY, width: face.box.width, height: face.box.height)
            guard let roi = PrivacyAnimalTargets.roi(box, frame: frame),
                  let landmarks = cache.landmarks(track: track, box: box, estimate: { self.landmarks(source, box: box, roi: roi) }),
                  let yaw = PrivacyAnimalPose.yaw(landmarks),
                  let place = atlas.place(landmarks: landmarks, yaw: yaw) else { return output }
            return layer(sprite, atlas, place, frameHeight: frame.height).composited(over: output)
        }
        guard animals !== protected else { return protected }
        // Inside the protected mask only, like face swap's polygon; uncovered mask pixels keep the blur.
        return animals.applyingFilter("CIBlendWithMask", parameters: [kCIInputBackgroundImageKey: protected,
                                                                    kCIInputMaskImageKey: mask])
            .cropped(to: protected.extent)
    }

    func reset() { cache.reset() }

    private func layer(_ sprite: CIImage, _ atlas: PrivacyAnimalAtlas, _ place: PrivacyAnimalAtlas.Placement,
                       frameHeight: CGFloat) -> CIImage {
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
        // Core Image cell (y-up) -> top-left cell -> mirror -> landmark similarity -> top-left frame -> Core Image frame.
        var transform = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: size.height)
        if place.mirrored { transform = transform.concatenating(CGAffineTransform(a: -1, b: 0, c: 0, d: 1, tx: size.width, ty: 0)) }
        transform = transform
            .concatenating(CGAffineTransform(a: place.a, b: place.b, c: -place.b, d: place.a, tx: place.tx, ty: place.ty))
            .concatenating(CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: frameHeight))
        return canvas.transformed(by: transform)
    }

    private func landmarks(_ source: CIImage, box: CGRect, roi: CGRect) -> [CGPoint]? {
        // The top-left ROI sits at the top-left of a square input scaled to poseInput; the rest is frame or black.
        let frameHeight = source.extent.height
        let side = max(roi.width, roi.height), scale = side / Self.poseInput
        let square = CGRect(x: roi.minX, y: frameHeight - roi.minY - side, width: side, height: side)
        let crop = source.cropped(to: square).composited(over: CIImage(color: .black).cropped(to: square))
            .transformed(by: CGAffineTransform(translationX: -square.minX, y: -square.minY)
                .concatenating(CGAffineTransform(scaleX: 1 / scale, y: 1 / scale)))
        do {
            let model = try detector ?? PrivacyYuNetDetector()
            detector = model
            let candidates = try model.faces(in: crop, size: CGSize(width: Self.poseInput, height: Self.poseInput))
                .filter { $0.score >= PrivacyAnimalTargets.minScore }
                .map { face in
                    (box: CGRect(x: roi.minX + face.box.minX * scale, y: roi.minY + face.box.minY * scale,
                                 width: face.box.width * scale, height: face.box.height * scale),
                     landmarks: face.landmarks.map { CGPoint(x: roi.minX + $0.x * scale, y: roi.minY + $0.y * scale) })
                }
            return PrivacyAnimalTargets.match(box, candidates: candidates)
        } catch {
            // `swapper_unavailable`: every face keeps the blur.
            detectorUnavailable = true
            return nil
        }
    }
}
