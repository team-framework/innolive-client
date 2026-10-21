import Foundation
import CoreGraphics

/// Server policy: current detector masks plus a predicted mask for one lost frame.
/// Cache by BoT-SORT ID, never by detection-array position or a second IoU match.
nonisolated final class PrivacyMaskStabilizer {
    private struct Cached {
        let box: CGRect
        let classID: Int
        let bytes: [UInt8]
    }
    private var masks: [Int: Cached] = [:]

    func reset() { masks = [:] }

    func apply(_ instances: [Int: PrivacySegmentation.InstanceMask], frame: PrivacyBoTSORT.Frame,
               allowed: Set<Int> = [], approvedTrackIDs: Set<Int> = []) throws -> [UInt8] {
        let size = PrivacySegmentation.maskSize
        guard instances.values.allSatisfy({ $0.bytes.count == size * size }) else { throw PrivacyModelError.outputContract }
        var bytes = PrivacySegmentation.union(instances.filter { !allowed.contains($0.key) }.map(\.value))
        let currentBoxes = frame.observations.map(\.box)
        for observation in frame.observations {
            guard let instance = instances[observation.detectionIndex] else { continue }
            masks[observation.trackID] = Cached(box: observation.box, classID: instance.detection.classID, bytes: instance.bytes)
        }
        for lost in frame.lost where lost.gap == 1 {
            guard let cached = masks[lost.trackID],
                  !(cached.classID == 0 && approvedTrackIDs.contains(lost.trackID)),
                  !currentBoxes.contains(where: { PrivacySegmentation.iou($0, lost.box) >= 0.5 }) else { continue }
            let held = Self.warp(cached.bytes, from: cached.box, to: lost.box)
            // Server decays the confidence metadata by 0.90, not mask opacity.
            for index in bytes.indices { bytes[index] = max(bytes[index], held[index]) }
        }
        let liveIDs = Set(frame.observations.map(\.trackID) + frame.lost.filter { $0.gap <= 30 }.map(\.trackID))
        masks = masks.filter { liveIDs.contains($0.key) }
        return bytes
    }

    static func warp(_ bytes: [UInt8], from sourceBox: CGRect, to targetBox: CGRect) -> [UInt8] {
        let size = PrivacySegmentation.maskSize
        let scale = CGFloat(size) / CGFloat(PrivacySegmentation.inputSize)
        let source = sourceBox.applying(CGAffineTransform(scaleX: scale, y: scale))
        let target = targetBox.intersection(CGRect(x: 0, y: 0, width: 640, height: 640))
            .applying(CGAffineTransform(scaleX: scale, y: scale))
        var result = [UInt8](repeating: 0, count: size * size)
        guard !target.isNull, !target.isEmpty, source.width > 0, source.height > 0 else { return result }
        let x0 = max(0, Int(floor(target.minX))), x1 = min(size, Int(ceil(target.maxX)))
        let y0 = max(0, Int(floor(target.minY))), y1 = min(size, Int(ceil(target.maxY)))
        guard x0 < x1, y0 < y1 else { return result }
        for y in y0..<y1 {
            let sy = Int(floor(source.minY + (CGFloat(y) + 0.5 - target.minY) * source.height / target.height))
            guard sy >= 0, sy < size else { continue }
            for x in x0..<x1 {
                let sx = Int(floor(source.minX + (CGFloat(x) + 0.5 - target.minX) * source.width / target.width))
                if sx >= 0, sx < size { result[y * size + x] = bytes[sy * size + sx] }
            }
        }
        return result
    }
}
