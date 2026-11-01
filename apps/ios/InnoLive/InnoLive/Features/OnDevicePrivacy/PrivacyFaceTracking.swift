import Foundation
import CoreGraphics

/// Stream-local approval bound to a registered face and the BoT-SORT lifetime.
/// Approved tracks are matched again every `reverifyFrames` frames, because the tracker has no ReID
/// and an ID swap could otherwise carry an approval to a stranger.
nonisolated final class PrivacyFaceTracking {
    static let reverifyFrames = 30

    struct Track {
        let id = UUID()
        var index: Int
        var box: CGRect
        let sourceID: Int
        var isolated = true
        var lastScheduled: Double = -.infinity
        var candidate: UUID?
        var framesSinceVerified = 0

        var needsMatch: Bool { candidate == nil || framesSinceVerified >= PrivacyFaceTracking.reverifyFrames }
    }
    private(set) var tracks: [Track] = []
    private var lastTime: Double?

    func reset() { tracks = []; lastTime = nil }

    func update(_ boxes: [Int: CGRect], at time: Double, trackIDs: [Int: Int], liveTrackIDs: Set<Int>? = nil) {
        guard time.isFinite else { reset(); return }
        if let lastTime, time <= lastTime || time - lastTime >= 1.0 { tracks = [] }
        lastTime = time
        let valid = boxes.filter { _, box in
            box.width.isFinite && box.height.isFinite && box.minX.isFinite && box.minY.isFinite && box.width > 0 && box.height > 0
        }
        let old = tracks
        let live = liveTrackIDs ?? Set(trackIDs.values)
        tracks = valid.sorted { $0.key < $1.key }.compactMap { index, box in
            guard let sourceID = trackIDs[index], live.contains(sourceID) else { return nil }
            var track = old.first { $0.sourceID == sourceID } ?? Track(index: index, box: box, sourceID: sourceID)
            track.box = box
            track.index = index
            // Avoid ambiguous initial crops. Existing approvals follow the ID even during overlap.
            track.isolated = !valid.contains { $0.key != index && PrivacySegmentation.iou(box, $0.value) > 0.05 }
            return track
        }
        let present = Set(tracks.map(\.sourceID))
        tracks += old.filter { $0.candidate != nil && live.contains($0.sourceID) && !present.contains($0.sourceID) }.map {
            var lost = $0
            lost.index = -1
            return lost
        }
        for index in tracks.indices where tracks[index].candidate != nil { tracks[index].framesSinceVerified += 1 }
    }

    /// A whitelist edit cancels in-flight decisions, but preserves approvals for surviving entries.
    func retainEntries(_ entryIDs: Set<UUID>) {
        tracks = tracks.map { track in
            if let candidate = track.candidate, entryIDs.contains(candidate) { return track }
            return Track(index: track.index, box: track.box, sourceID: track.sourceID, isolated: track.isolated)
        }
    }

    /// Approved tracks due for re-verification go before unapproved ones.
    func next(at time: Double) -> Track? {
        guard time.isFinite,
              let index = tracks.indices.filter({ tracks[$0].needsMatch && tracks[$0].index >= 0 && tracks[$0].isolated && time - tracks[$0].lastScheduled >= 0.25 })
            .min(by: { (tracks[$0].candidate == nil ? 1 : 0, tracks[$0].lastScheduled) < (tracks[$1].candidate == nil ? 1 : 0, tracks[$1].lastScheduled) })
        else { return nil }
        tracks[index].lastScheduled = time
        return tracks[index]
    }

    /// A fresh, isolated, usable sample decides the track: a match approves or confirms it, no match revokes it.
    /// An unusable or late sample changes nothing, so a due approved track is simply retried.
    func accept(trackID: UUID, match: UUID?, capturedAt: Double, now: Double, sampleAvailable: Bool = true) {
        guard let index = tracks.firstIndex(where: { $0.id == trackID }), tracks[index].needsMatch,
              tracks[index].lastScheduled == capturedAt, tracks[index].isolated,
              sampleAvailable, now.isFinite, capturedAt.isFinite,
              now >= capturedAt, now - capturedAt < 0.75 else { return }
        tracks[index].candidate = match
        tracks[index].framesSinceVerified = 0
    }

    var approvedTrackIDs: Set<Int> { Set(tracks.filter { $0.candidate != nil }.map(\.sourceID)) }

    func allowed(at time: Double) -> Set<Int> {
        Set(tracks.filter { $0.candidate != nil && $0.index >= 0 }.map(\.index))
    }
}
