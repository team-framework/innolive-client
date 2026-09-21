#if DEBUG
import Foundation
import CoreImage

nonisolated struct PrivacyFaceSnapshot: Sendable {
    struct Person: Identifiable, Sendable { let id: UUID; let name: String }
    let people: [Person]
    let message: String
    let enrolling: Bool
    let allowed: Int
    let milliseconds: Double
    let ready: Bool
}

/// All state is owned by PrivacyCamera's serial queue. Recognition returns through a locked mailbox.
nonisolated final class PrivacyFaceCoordinator {
    private struct Enrollment {
        let id = UUID()
        let name: String
        let started: Double
    }
    private let worker = PrivacyFaceWorker()
    private let tracking = PrivacyFaceTracking()
    private let context = CIContext(options: [.cacheIntermediates: false])
    private var library: PrivacyFaceLibrary?
    private var pending: Enrollment?
    private var generation = 0
    private var disabled = false
    private var lastAttempt = -Double.infinity
    private var message = "이름을 입력하고 한 명씩 등록하세요."
    private var milliseconds = 0.0
    private var allowedCount = 0
    private var decisionMetrics: [[String: Double]] = []

    init() {
        worker.prepare()
        do { library = try PrivacyFaceLibrary() }
        catch { disabled = true; message = "등록 데이터 읽기 실패: \(error.localizedDescription)" }
    }

    var snapshot: PrivacyFaceSnapshot {
        .init(people: (library?.entries ?? []).map { .init(id: $0.id, name: $0.name) },
              message: !worker.ready && !disabled ? (worker.preparationMessage ?? "얼굴 인식 모델 준비 중… 완료 후 등록할 수 있습니다.") : message,
              enrolling: pending != nil, allowed: allowedCount, milliseconds: milliseconds, ready: worker.ready && !disabled)
    }

    func reset() {
        worker.prepare()
        generation += 1
        pending = nil
        tracking.reset()
        allowedCount = 0
        if !disabled { message = "등록 \(library?.entries.count ?? 0)명 · 이름을 입력해 추가할 수 있습니다." }
    }

    func enroll(name: String) {
        guard !disabled, worker.ready, let library else { return }
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 40, library.entries.count < 20 else {
            message = "이름은 1~40자, 등록은 최대 20명입니다."
            return
        }
        reset()
        pending = Enrollment(name: name, started: ProcessInfo.processInfo.systemUptime)
        message = "\(name) 등록 중 · 얼굴을 가운데 영역에 맞춰 주세요."
    }

    func delete(id: UUID) {
        reset()
        do {
            try library?.delete(id: id)
            message = "등록을 삭제했습니다."
        } catch {
            disabled = true
            message = "삭제 저장 실패 · 얼굴 예외를 중지했습니다: \(error.localizedDescription)"
        }
    }

    func process(image: CIImage, objects: [PrivacySegmentation.Detection], layout: PrivacySegmentation.Letterbox,
                 timestamp: Double) -> Set<Int> {
        let faceIndices = objects.indices.filter { objects[$0].classID == 0 }
        var boxes: [Int: CGRect] = [:]
        for index in faceIndices {
            let box = objects[index].box
            let real = CGRect(x: (box.minX - layout.left) * image.extent.width / layout.resized.width,
                              y: (640 - box.maxY - layout.bottom) * image.extent.height / layout.resized.height,
                              width: box.width * image.extent.width / layout.resized.width,
                              height: box.height * image.extent.height / layout.resized.height).intersection(image.extent)
            if !real.isNull, min(real.width, real.height) >= 24 { boxes[index] = real }
        }
        // Use model-coordinate boxes for stable geometry, including small faces to invalidate crossings.
        tracking.update(Dictionary(uniqueKeysWithValues: faceIndices.map { ($0, objects[$0].box) }), at: timestamp)
        if let output = worker.takeResult(), output.generation == generation {
            milliseconds = output.milliseconds
            if output.fatal {
                disabled = true
                pending = nil
                tracking.reset()
                message = output.message ?? "얼굴 인식 모델을 실행할 수 없습니다."
            } else if output.enrollment {
                consumeEnrollment(output, now: timestamp)
            } else {
                let match = output.embedding.flatMap { PrivacyFaceMath.match($0, entries: library?.entries ?? []) }
                let scores = output.embedding.map { embedding in
                    (library?.entries ?? []).map { PrivacyFaceMath.cosine(embedding, $0.embedding) }.sorted(by: >)
                } ?? []
                decisionMetrics.append(["uptime": timestamp, "age_ms": (timestamp - output.capturedAt) * 1000,
                                        "failure_code": Double(output.failureCode), "best_score": Double(scores.first ?? -1),
                                        "second_score": scores.count > 1 ? Double(scores[1]) : -1,
                                        "matched": match == nil ? 0 : 1,
                                        "current_track": tracking.tracks.contains { $0.id == output.id } ? 1 : 0])
                if decisionMetrics.count > 900 { decisionMetrics.removeFirst() }
                PrivacyFaceWorker.saveMetrics(decisionMetrics, filename: "privacy-face-decisions.json")
                tracking.accept(trackID: output.id, match: match, capturedAt: output.capturedAt, now: timestamp,
                                sampleAvailable: !(1...5).contains(output.failureCode))
            }
        }
        guard !disabled, worker.ready else { allowedCount = 0; return [] }
        if let pending, timestamp - pending.started > 30 {
            self.pending = nil
            message = "등록 시간이 지났습니다. 얼굴을 가까이 맞추고 다시 등록해 주세요."
        }
        if !worker.busy {
            if let pending, timestamp - lastAttempt >= 0.35 {
                schedule(image: image, box: image.extent, id: pending.id, enrollment: true, timestamp: timestamp)
            } else if pending == nil, !(library?.entries.isEmpty ?? true), let track = tracking.next(at: timestamp),
                      let box = boxes[track.index] {
                schedule(image: image, box: box, id: track.id, enrollment: false, timestamp: timestamp)
            }
        }
        let allowed = pending == nil ? tracking.allowed(at: timestamp) : []
        allowedCount = allowed.count
        return allowed
    }

    private func schedule(image: CIImage, box: CGRect, id: UUID, enrollment: Bool, timestamp: Double) {
        let region = enrollment ? image.extent : box.insetBy(dx: -box.width * 0.25, dy: -box.height * 0.25).intersection(image.extent).integral
        guard let crop = context.createCGImage(image, from: region) else { return }
        lastAttempt = timestamp
        worker.submit(.init(id: id, generation: generation, capturedAt: timestamp, enrollment: enrollment, image: crop))
    }

    private func consumeEnrollment(_ output: PrivacyFaceWorker.Output, now: Double) {
        guard let pending, pending.id == output.id else { return }
        guard let fused = output.embedding, now - output.capturedAt < 5 else {
            message = "\(pending.name) 등록 중 · \(output.message ?? "얼굴 촬영을 다시 시도합니다.")"
            return
        }
        self.pending = nil
        if let duplicate = library?.entries.first(where: { PrivacyFaceMath.cosine(fused, $0.embedding) >= 0.75 }) {
            message = "이미 등록된 \(duplicate.name)와 유사합니다. 다른 사람을 등록해 주세요."
            return
        }
        do {
            try library?.add(name: pending.name, embedding: fused)
            generation += 1
            tracking.reset()
            message = "\(pending.name) 등록 완료 · 총 \(library?.entries.count ?? 0)명"
        } catch {
            message = "등록 저장 실패: \(error.localizedDescription)"
        }
    }
}
#endif
