import Foundation
import CoreGraphics

/// 스트림 안에서만 유효한 승인으로, 등록된 얼굴과 BoT-SORT 트랙 수명에 묶인다.
/// 트래커에 ReID가 없어 ID가 뒤바뀌면 승인이 모르는 사람에게 넘어갈 수 있으므로,
/// 승인된 트랙도 `reverifyFrames` 프레임마다 다시 매칭한다.
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
            // 처음 크롭이 모호해지지 않게 한다. 이미 받은 승인은 얼굴이 겹쳐 있는 동안에도 ID를 따라간다.
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

    /// 허용 목록을 고치면 진행 중인 판단은 취소하되, 남아 있는 항목의 승인은 유지한다.
    func retainEntries(_ entryIDs: Set<UUID>) {
        tracks = tracks.map { track in
            if let candidate = track.candidate, entryIDs.contains(candidate) { return track }
            return Track(index: track.index, box: track.box, sourceID: track.sourceID, isolated: track.isolated)
        }
    }

    /// 재검증할 때가 된 승인 트랙을 승인되지 않은 트랙보다 먼저 고른다.
    func next(at time: Double) -> Track? {
        guard time.isFinite,
              let index = tracks.indices.filter({ tracks[$0].needsMatch && tracks[$0].index >= 0 && tracks[$0].isolated && time - tracks[$0].lastScheduled >= 0.25 })
            .min(by: { (tracks[$0].candidate == nil ? 1 : 0, tracks[$0].lastScheduled) < (tracks[$1].candidate == nil ? 1 : 0, tracks[$1].lastScheduled) })
        else { return nil }
        tracks[index].lastScheduled = time
        return tracks[index]
    }

    /// 최근에 찍었고, 다른 얼굴과 겹치지 않으며, 쓸 수 있는 샘플이 트랙을 결정한다. 매칭되면 승인하거나
    /// 승인을 확인하고, 매칭되지 않으면 승인을 취소한다.
    /// 쓸 수 없거나 늦게 온 샘플은 아무것도 바꾸지 않으므로, 재검증할 때가 된 승인 트랙은 그대로 다시 시도한다.
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
