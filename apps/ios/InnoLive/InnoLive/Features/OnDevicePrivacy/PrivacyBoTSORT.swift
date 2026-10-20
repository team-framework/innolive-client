// SPDX-License-Identifier: AGPL-3.0-or-later
// Algorithm port referencing Ultralytics 8.4.104 (Ultralytics AGPL-3.0).
import Foundation
import CoreGraphics

/// Stream-local BoT-SORT, configured like innolive-ai/config/botsort.yaml.
/// Appearance ReID is disabled on the server; AdaFace remains a separate identity decision.
nonisolated final class PrivacyBoTSORT {
    struct Observation {
        let trackID: Int
        let detectionIndex: Int
        let box: CGRect
        let confirmed: Bool
    }
    struct Lost {
        let trackID: Int
        let box: CGRect
        let gap: Int
    }
    struct Frame {
        let observations: [Observation]
        let lost: [Lost]
    }
    private enum State { case tentative, tracked, lost }
    private struct Track {
        let id: Int
        var filter: PrivacyTrackKalman
        var state: State
        let startFrame: Int
        var lastFrame: Int
        var detectionIndex: Int
        var classID: Int
    }
    static let highThreshold: Float = 0.25
    static let lowThreshold: Float = 0.05
    static let buffer = 30
    private var tracks: [Track] = []
    private var frame = 0
    private var nextID = 1

    func reset() { tracks = []; frame = 0; nextID = 1 }

    func update(_ detections: [PrivacySegmentation.Detection], motion: CGAffineTransform = .identity) -> Frame {
        frame += 1
        let high = detections.indices.filter { detections[$0].score >= Self.highThreshold }
        let low = detections.indices.filter { detections[$0].score > Self.lowThreshold && detections[$0].score < Self.highThreshold }
        let pool = tracks.indices.filter { tracks[$0].state != .tentative }
        let tentative = tracks.indices.filter { tracks[$0].state == .tentative }
        for index in tracks.indices {
            if tracks[index].state != .tentative { tracks[index].filter.predict(lost: tracks[index].state == .lost) }
            tracks[index].filter.compensate(motion)
        }
        let first = associate(pool, high, detections, limit: 0.80, fuseScore: true)
        for (track, detection) in first.matches { correct(track, detection, detections) }
        let continuing = first.unmatchedTracks.filter { tracks[$0].state == .tracked }
        let second = associate(continuing, low, detections, limit: 0.50, fuseScore: false)
        for (track, detection) in second.matches { correct(track, detection, detections) }
        for index in second.unmatchedTracks { tracks[index].state = .lost }
        let confirmation = associate(tentative, first.unmatchedDetections, detections, limit: 0.70, fuseScore: true)
        for (track, detection) in confirmation.matches { correct(track, detection, detections) }
        let removed = Set(confirmation.unmatchedTracks)
        tracks = tracks.enumerated().filter { !removed.contains($0.offset) }.map(\.element)
        for index in confirmation.unmatchedDetections {
            let detection = detections[index]
            tracks.append(Track(id: nextID, filter: PrivacyTrackKalman(box: detection.box),
                                state: frame == 1 ? .tracked : .tentative, startFrame: frame,
                                lastFrame: frame, detectionIndex: index, classID: detection.classID))
            nextID += 1
        }
        // Match before expiring the lost pool, in the same order as BYTETracker.
        tracks.removeAll { $0.state == .lost && frame - $0.lastFrame > Self.buffer }
        removeDuplicates()
        // Tentative detections still get a mask immediately. They cannot grant a face exception.
        let observations = tracks.filter { $0.state != .lost && $0.lastFrame == frame }.map {
            Observation(trackID: $0.id, detectionIndex: $0.detectionIndex, box: $0.filter.box,
                        confirmed: $0.state == .tracked)
        }.sorted { $0.detectionIndex < $1.detectionIndex }
        return Frame(observations: observations, lost: tracks.filter { $0.state == .lost }.map {
            Lost(trackID: $0.id, box: $0.filter.box, gap: frame - $0.lastFrame)
        })
    }

    private func correct(_ index: Int, _ detectionIndex: Int, _ detections: [PrivacySegmentation.Detection]) {
        tracks[index].filter.correct(box: detections[detectionIndex].box)
        tracks[index].state = .tracked
        tracks[index].lastFrame = frame
        tracks[index].detectionIndex = detectionIndex
        tracks[index].classID = detections[detectionIndex].classID
    }

    private func associate(_ pool: [Int], _ indices: [Int], _ detections: [PrivacySegmentation.Detection],
                           limit: Double, fuseScore: Bool) -> (matches: [(Int, Int)], unmatchedTracks: [Int], unmatchedDetections: [Int]) {
        let costs = pool.map { track in
            indices.map { index in
                let iou = Double(PrivacySegmentation.iou(tracks[track].filter.box, detections[index].box))
                return 1 - iou * (fuseScore ? Double(detections[index].score) : 1)
            }
        }
        let pairs = PrivacyTrackAssignment.solve(costs, limit: limit).map { (pool[$0.0], indices[$0.1]) }
        let matchedTracks = Set(pairs.map(\.0)), matchedDetections = Set(pairs.map(\.1))
        return (pairs, pool.filter { !matchedTracks.contains($0) }, indices.filter { !matchedDetections.contains($0) })
    }

    private func removeDuplicates() {
        let current = tracks.indices.filter { tracks[$0].state != .lost }
        let lost = tracks.indices.filter { tracks[$0].state == .lost }
        var removed: Set<Int> = []
        for a in current {
            for b in lost where PrivacySegmentation.iou(tracks[a].filter.box, tracks[b].filter.box) > 0.85 {
                let ageA = tracks[a].lastFrame - tracks[a].startFrame
                let ageB = tracks[b].lastFrame - tracks[b].startFrame
                removed.insert(ageA > ageB ? b : a)
            }
        }
        tracks = tracks.enumerated().filter { !removed.contains($0.offset) }.map(\.element)
    }
}
