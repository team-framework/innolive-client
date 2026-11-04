import Combine
import Foundation
import UIKit

/// 저장된 로컬 테이크 하나로, `POST /users/me/usage/local-takes`로 보고한다. 녹화 UUID를 테이크 ID로 쓰므로
/// 같은 테이크를 여러 번 올려도 서버는 한 번만 센다.
nonisolated struct LocalTakeUsage: Codable, Equatable, Sendable {
    let takeID: UUID
    let deviceID: String
    let startedAt: Date
    let endedAt: Date
    let durationSeconds: Int

    /// 서버 세션 녹화이거나 반올림해서 0초가 되는 테이크면 nil을 반환한다.
    init?(recording: SavedBroadcastRecording, deviceID: String) {
        let seconds = Int(recording.duration.rounded())
        guard recording.sessionID.hasPrefix(CaptureSessionID.localKeyPrefix), seconds > 0 else { return nil }
        takeID = recording.id
        self.deviceID = deviceID
        startedAt = recording.createdAt
        endedAt = recording.createdAt.addingTimeInterval(recording.duration)
        durationSeconds = seconds
    }

    enum CodingKeys: String, CodingKey {
        case takeID = "take_id", deviceID = "device_id", startedAt = "started_at"
        case endedAt = "ended_at", durationSeconds = "duration_seconds"
    }
}

/// 서버가 아직 확인하지 않은 로컬 테이크 시간(초)이다. 이 기기에만 저장하고 계정과 함께 지운다.
@MainActor
final class LocalTakeUsageLedger: ObservableObject {
    nonisolated deinit {}

    @Published private(set) var pending: [LocalTakeUsage]
    private let fileURL: URL
    private let deviceID: String

    var pendingSeconds: Int { pending.reduce(0) { $0 + $1.durationSeconds } }

    init(fileURL: URL? = nil, deviceID: String? = nil) {
        self.fileURL = fileURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LocalTakeUsage.json")
        self.deviceID = deviceID ?? UIDevice.current.identifierForVendor?.uuidString ?? "ios-unknown"
        pending = (try? Data(contentsOf: self.fileURL)).flatMap { try? Self.decoder.decode([LocalTakeUsage].self, from: $0) } ?? []
    }

    /// 서버 세션 녹화와 이미 대기 중인 테이크는 무시한다.
    func record(_ recording: SavedBroadcastRecording) {
        guard let take = LocalTakeUsage(recording: recording, deviceID: deviceID),
              !pending.contains(where: { $0.takeID == take.takeID }) else { return }
        pending.append(take)
        persist()
    }

    func acknowledge(_ takeIDs: Set<UUID>) {
        let remaining = pending.filter { !takeIDs.contains($0.takeID) }
        guard remaining.count != pending.count else { return }
        pending = remaining
        persist()
    }

    func eraseAll() {
        pending = []
        try? FileManager.default.removeItem(at: fileURL)
    }

    private func persist() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            // 백그라운드 저장 직후에 업로드할 수 있으므로 처음 잠금 해제한 뒤로는 파일을 읽을 수 있게 둔다.
            try Self.encoder.encode(pending).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var url = fileURL
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        } catch {
            // 이번 실행에서는 메모리에 있는 대기열을 그대로 업로드한다.
        }
    }

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}

/// ledger를 배치 단위로 비운다. 엔드포인트가 없는 서버를 포함해 어떤 실패가 나도 테이크를 대기 상태로 두고
/// backoff 뒤에 다시 시도한다. `canUpload`가 false인 동안은 아무것도 보내지 않고 멈춘다. 다시 연결될 때
/// `drain()`을 호출하면 새로 시작하고 남은 backoff 대기는 건너뛴다.
@MainActor
final class LocalTakeUsageUploader {
    nonisolated deinit {}

    static let batchSize = 50
    private let ledger: LocalTakeUsageLedger
    private let upload: ([LocalTakeUsage]) async throws -> [UUID]
    private let sleep: (Duration) async throws -> Void
    private let canUpload: () -> Bool
    private let onUploaded: () -> Void
    private var task: Task<Void, Never>?
    private var isWaitingToRetry = false
    private struct UnacknowledgedBatch: Error {}

    init(ledger: LocalTakeUsageLedger,
         upload: @escaping ([LocalTakeUsage]) async throws -> [UUID],
         sleep: @escaping (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
         canUpload: @escaping () -> Bool = { true },
         onUploaded: @escaping () -> Void = {}) {
        self.ledger = ledger
        self.upload = upload
        self.sleep = sleep
        self.canUpload = canUpload
        self.onUploaded = onUploaded
    }

    static func retryDelay(afterFailures failures: Int) -> Duration {
        .seconds(min(5 * (1 << min(failures - 1, 7)), 600))
    }

    func drain() {
        if task != nil && !isWaitingToRetry { return }
        task?.cancel()
        task = Task { [weak self] in await self?.run() }
    }

    func stop() {
        task?.cancel()
        task = nil
        isWaitingToRetry = false
    }

    /// 테스트용으로 공개한다. ledger가 비었거나, 업로드할 수 없거나, 작업이 취소되면 반환한다.
    func run() async {
        var failures = 0
        var uploadedAny = false
        defer {
            isWaitingToRetry = false
            if !Task.isCancelled { task = nil }
            if uploadedAny { onUploaded() }
        }
        while !Task.isCancelled, !ledger.pending.isEmpty, canUpload() {
            let batch = Array(ledger.pending.prefix(Self.batchSize))
            do {
                let acknowledged = try await upload(batch)
                guard !Task.isCancelled else { return }
                let sent = Set(batch.map(\.takeID))
                let removed = sent.intersection(acknowledged)
                // 배치에서 하나도 확인하지 않은 응답을 그냥 넘기면 끝없이 반복하므로 실패로 처리한다.
                guard !removed.isEmpty else { throw UnacknowledgedBatch() }
                ledger.acknowledge(removed)
                uploadedAny = true
                failures = 0
            } catch {
                guard !Task.isCancelled else { return }
                failures += 1
                isWaitingToRetry = true
                do { try await sleep(Self.retryDelay(afterFailures: failures)) } catch { return }
                isWaitingToRetry = false
            }
        }
    }
}
