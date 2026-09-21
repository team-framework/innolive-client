#if DEBUG
import Foundation
import CoreGraphics

/// Aligns recent per-object masks to current detection boxes. New protection is immediate;
/// only removal of an old edge fades. Matching never grants an identity/whitelist exception.
nonisolated final class PrivacyMaskStabilizer {
    private var previous: [PrivacySegmentation.InstanceMask] = []
    private var previousTime: TimeInterval?
    private let fadeSeconds: Double = 0.12
    private let maximumGap: Double = 0.20

    func reset() {
        previous = []
        previousTime = nil
    }

    func apply(_ current: [PrivacySegmentation.InstanceMask], timestamp: TimeInterval) throws -> [UInt8] {
        let size = PrivacySegmentation.maskSize
        guard timestamp.isFinite, current.allSatisfy({ $0.bytes.count == size * size }) else {
            throw PrivacyModelError.outputContract
        }
        let interval = previousTime.map { timestamp - $0 } ?? maximumGap
        if interval <= 0 || interval >= maximumGap { previous = [] }
        let decay = Float(exp(-max(0, interval) / fadeSeconds))
        var available = Set(previous.indices)
        var stabilized: [PrivacySegmentation.InstanceMask] = []
        for instance in current {
            let box = instance.detection.box
            let match = available
                .filter { previous[$0].detection.classID == instance.detection.classID }
                .map { ($0, PrivacySegmentation.iou(box, previous[$0].detection.box)) }
                .filter { $0.1 >= 0.30 }
                .max { $0.1 < $1.1 }?.0
            guard let match else {
                stabilized.append(instance)
                continue
            }
            available.remove(match)
            let older = previous[match]
            let scale = CGFloat(size) / CGFloat(PrivacySegmentation.inputSize)
            let destination = box.applying(CGAffineTransform(scaleX: scale, y: scale))
            let source = older.detection.box.applying(CGAffineTransform(scaleX: scale, y: scale))
            guard destination.width > 0, destination.height > 0 else {
                throw PrivacyModelError.outputContract
            }
            let x0 = max(0, Int(floor(destination.minX)))
            let x1 = min(size, Int(ceil(destination.maxX)))
            let y0 = max(0, Int(floor(destination.minY)))
            let y1 = min(size, Int(ceil(destination.maxY)))
            var bytes = instance.bytes
            if x0 < x1 && y0 < y1 {
                for y in y0..<y1 {
                    let oldY = source.minY + (CGFloat(y) + 0.5 - destination.minY) / destination.height * source.height - 0.5
                    for x in x0..<x1 {
                        let oldX = source.minX + (CGFloat(x) + 0.5 - destination.minX) / destination.width * source.width - 0.5
                        let faded = Self.sample(older.bytes, x: oldX, y: oldY, size: size) * decay
                        let index = y * size + x
                        bytes[index] = max(bytes[index], UInt8(clamping: Int(faded)))
                    }
                }
            }
            stabilized.append(.init(detection: instance.detection, bytes: bytes))
        }
        // Unmatched/disappeared detections are not painted at stale screen coordinates.
        previous = stabilized
        previousTime = timestamp
        return PrivacySegmentation.union(stabilized)
    }

    private static func sample(_ bytes: [UInt8], x: CGFloat, y: CGFloat, size: Int) -> Float {
        guard x >= 0, y >= 0, x <= CGFloat(size - 1), y <= CGFloat(size - 1) else { return 0 }
        let x0 = Int(floor(x)), y0 = Int(floor(y))
        let x1 = min(x0 + 1, size - 1), y1 = min(y0 + 1, size - 1)
        let dx = Float(x - CGFloat(x0)), dy = Float(y - CGFloat(y0))
        let top = Float(bytes[y0 * size + x0]) * (1 - dx) + Float(bytes[y0 * size + x1]) * dx
        let bottom = Float(bytes[y1 * size + x0]) * (1 - dx) + Float(bytes[y1 * size + x1]) * dx
        return top * (1 - dy) + bottom * dy
    }
}
#endif
