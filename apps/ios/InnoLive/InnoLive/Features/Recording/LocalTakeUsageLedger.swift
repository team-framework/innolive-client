import Combine
import Foundation
import UIKit

/// One saved local take, reported to `POST /users/me/usage/local-takes`. The recording UUID is the take ID,
/// so a repeated upload of the same take is counted once by the server.
nonisolated struct LocalTakeUsage: Codable, Equatable, Sendable {
    let takeID: UUID
    let deviceID: String
    let startedAt: Date
    let endedAt: Date
    let durationSeconds: Int

    /// Returns nil for a server-session recording, and for a take that rounds to zero seconds.
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

/// Local take seconds the server has not acknowledged yet. Persisted on this device only and cleared with the account.
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

    /// Ignores server-session recordings and takes already pending.
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
            // Uploads may run right after a background save, so the file stays readable after first unlock.
            try Self.encoder.encode(pending).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var url = fileURL
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        } catch {
            // The in-memory queue still uploads in this run.
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

/// Drains the ledger in batches. Any failure, including a server without the endpoint, keeps the takes pending
/// and retries with backoff. `drain()` on reconnect skips a pending backoff.
@MainActor
final class LocalTakeUsageUploader {
    nonisolated deinit {}

    static let batchSize = 50
    private let ledger: LocalTakeUsageLedger
    private let upload: ([LocalTakeUsage]) async throws -> [UUID]
    private let sleep: (Duration) async throws -> Void
    private let onUploaded: () -> Void
    private var task: Task<Void, Never>?
    private var isWaitingToRetry = false
    private struct UnacknowledgedBatch: Error {}

    init(ledger: LocalTakeUsageLedger,
         upload: @escaping ([LocalTakeUsage]) async throws -> [UUID],
         sleep: @escaping (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
         onUploaded: @escaping () -> Void = {}) {
        self.ledger = ledger
        self.upload = upload
        self.sleep = sleep
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

    /// Exposed for tests. Returns when the ledger is empty or the task is cancelled.
    func run() async {
        var failures = 0
        var uploadedAny = false
        defer {
            isWaitingToRetry = false
            if !Task.isCancelled { task = nil }
            if uploadedAny { onUploaded() }
        }
        while !Task.isCancelled, !ledger.pending.isEmpty {
            let batch = Array(ledger.pending.prefix(Self.batchSize))
            do {
                let acknowledged = try await upload(batch)
                guard !Task.isCancelled else { return }
                let sent = Set(batch.map(\.takeID))
                let removed = sent.intersection(acknowledged)
                // An answer that acknowledges nothing from the batch would loop forever, so treat it as a failure.
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
