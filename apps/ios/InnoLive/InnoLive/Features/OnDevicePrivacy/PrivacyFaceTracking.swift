import Foundation
import CoreGraphics

/// Short-lived identity cache. Ambiguous geometry, overlap, disappearance and stale results revoke exceptions.
nonisolated final class PrivacyFaceTracking {
    struct Track {
        let id: UUID
        var index: Int
        var box: CGRect
        var lastScheduled: Double = -.infinity
        var candidate: UUID?
        var confirmations = 0
        var lastConfirmed = -Double.infinity
        var allowedUntil = -Double.infinity
    }
    private(set) var tracks: [Track] = []
    private var lastTime: Double?

    func reset() { tracks = []; lastTime = nil }

    func update(_ boxes: [Int: CGRect], at time: Double) {
        if let lastTime, time <= lastTime || time - lastTime >= 0.20 { tracks = [] }
        lastTime = time
        let valid = boxes.filter { _, box in
            box.width.isFinite && box.height.isFinite && box.minX.isFinite && box.minY.isFinite && box.width > 0 && box.height > 0
        }
        let isolated = valid.filter { index, box in
            !valid.contains { $0.key != index && PrivacySegmentation.iou(box, $0.value) > 0.05 }
        }
        let old = tracks
        tracks = isolated.sorted { $0.key < $1.key }.map { index, box in
            let candidates = old.filter { PrivacySegmentation.iou($0.box, box) >= 0.50 }
            if candidates.count == 1, var prior = candidates.first,
               isolated.values.filter({ PrivacySegmentation.iou(prior.box, $0) >= 0.50 }).count == 1 {
                prior.box = box
                prior.index = index
                return prior
            }
            return Track(id: UUID(), index: index, box: box)
        }
    }

    func next(at time: Double) -> Track? {
        guard let index = tracks.indices.filter({ time - tracks[$0].lastScheduled >= 0.25 })
            .min(by: { tracks[$0].lastScheduled < tracks[$1].lastScheduled }) else { return nil }
        tracks[index].lastScheduled = time
        return tracks[index]
    }

    func accept(trackID: UUID, match: UUID?, capturedAt: Double, now: Double, sampleAvailable: Bool = true) {
        guard let index = tracks.firstIndex(where: { $0.id == trackID }) else { return }
        // A missing landmark sample is not evidence of a different person. Do not renew
        // the lease, and let its original 750ms deadline and geometry checks still apply.
        guard sampleAvailable else { return }
        guard let match, now >= capturedAt, now - capturedAt < 0.75 else {
            tracks[index].candidate = nil
            tracks[index].confirmations = 0
            tracks[index].allowedUntil = -.infinity
            return
        }
        if tracks[index].candidate == match, capturedAt - tracks[index].lastConfirmed < 1.0 {
            tracks[index].confirmations += 1
        } else {
            tracks[index].candidate = match
            tracks[index].confirmations = 1
            tracks[index].allowedUntil = -.infinity
        }
        tracks[index].lastConfirmed = capturedAt
        if tracks[index].confirmations >= 2 { tracks[index].allowedUntil = capturedAt + 0.75 }
    }

    func allowed(at time: Double) -> Set<Int> {
        let qualified = tracks.filter { $0.candidate != nil && $0.confirmations >= 2 && time < $0.allowedUntil }
        return Set(qualified.filter { candidate in
            qualified.filter { $0.candidate == candidate.candidate }.count == 1
        }.map(\.index))
    }
}
