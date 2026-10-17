import AVFoundation
import Combine
import Foundation

nonisolated struct SavedBroadcastRecording: Codable, Identifiable, Sendable, Equatable {
    let id: UUID
    let sessionID: String
    let createdAt: Date
    let duration: TimeInterval
    let fileName: String
    let bytes: Int64
    let videoFrames: Int?
    let audioFrames: Int?
    let droppedVideoFrames: Int?
}

nonisolated enum RecordingSessionStoreError: LocalizedError {
    case alreadyRecording, insufficientSpace, capacityUnavailable, wrongSession, invalidFile, noRetry, backgroundTimeExpired
    var errorDescription: String? {
        switch self {
        case .alreadyRecording: String(localized: "이미 진행 중인 녹화가 있습니다.")
        case .insufficientSpace: String(localized: "원본 영상을 저장할 공간이 부족합니다.")
        case .capacityUnavailable: String(localized: "녹화에 필요한 저장 공간을 확인하지 못했습니다.")
        case .wrongSession: String(localized: "현재 방송과 녹화 세션이 일치하지 않습니다.")
        case .invalidFile: String(localized: "저장한 영상과 음성 파일을 확인하지 못했습니다.")
        case .noRetry: String(localized: "다시 저장할 수 있는 완료 파일이 없습니다.")
        case .backgroundTimeExpired: String(localized: "백그라운드 저장 시간이 끝났습니다. 앱을 열면 저장 상태를 확인합니다.")
        }
    }
}

@MainActor final class RecordingSessionStore: ObservableObject {
    nonisolated deinit {}
    enum State: Equatable {
        case idle
        case recording
        case finalizing
        case saved(SavedBroadcastRecording)
        case failed(message: String, canRetry: Bool)
    }
    @Published private(set) var state: State = .idle
    @Published private(set) var recordings: [SavedBroadcastRecording] = []
    @Published private(set) var libraryError: String?
    @Published private(set) var preservedRecordings: [PreservedBroadcastRecording] = []
    private static var ownedPaths: Set<String> = []
    private var isReloadingLibrary = false
    private var deletedRecordingIDs: Set<UUID> = []
    private struct Session {
        let generation: UUID
        let id: UUID
        let sessionID: String
        let createdAt: Date
        let partialURL: URL
        let finalURL: URL
        let recorder: LocalBroadcastRecorder
        var liveTargets: Set<String> = []
        var hasConfirmedLive = false
    }
    private struct PendingCommit {
        let session: Session
        let result: LocalBroadcastRecordingResult
    }
    private let directory: URL
    private let minimumFreeBytes: Int64
    private let backgroundLeaseFactory: RecordingBackgroundLeaseFactory
    private let availableCapacity: @Sendable (URL) throws -> Int64
    private var generation = UUID()
    private var active: Session?
    private var pendingCommits: [UUID: PendingCommit] = [:]
    private var retryID: UUID?

    /// The injected location/capacity reader allow filesystem tests without touching the real library.
    init(directory: URL? = nil, minimumFreeBytes: Int64 = 256 * 1_024 * 1_024,
         availableCapacity: @escaping @Sendable (URL) throws -> Int64 = RecordingSessionStore.readAvailableCapacity,
         backgroundLeaseFactory: @escaping RecordingBackgroundLeaseFactory = { RecordingBackgroundTaskLease(name: $0, onExpiration: $1) }) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Recordings", isDirectory: true)
        self.minimumFreeBytes = minimumFreeBytes
        self.availableCapacity = availableCapacity
        self.backgroundLeaseFactory = backgroundLeaseFactory
    }

    func fileURL(for recording: SavedBroadcastRecording) -> URL {
        directory.appendingPathComponent(recording.fileName)
    }

    /// Call before the actual go-live request; attach the returned recorder to video/audio sinks.
    /// A previous session may still be finalizing. Its completion cannot overwrite this session's state.
    @discardableResult
    func begin(sessionID: String, onFailure: @escaping @Sendable (String) -> Void = { _ in }) throws -> LocalBroadcastRecorder {
        guard active == nil else { throw RecordingSessionStoreError.alreadyRecording }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard try availableCapacity(directory) >= minimumFreeBytes else { throw RecordingSessionStoreError.insufficientSpace }
        let id = UUID()
        let token = UUID()
        let partialURL = directory.appendingPathComponent("\(id.uuidString).partial.mp4")
        let finalURL = directory.appendingPathComponent("\(id.uuidString).mp4")
        let recorder = LocalBroadcastRecorder(onFailure: onFailure)
        try recorder.start(url: partialURL)
        generation = token
        let session = Session(generation: token, id: id, sessionID: sessionID, createdAt: Date(),
            partialURL: partialURL, finalURL: finalURL, recorder: recorder)
        try writeJournal(session, status: .provisional)
        active = session
        Self.ownedPaths.insert(Self.ownershipKey(partialURL))
        retryID = nil
        state = .recording
        return recorder
    }

    /// Only confirmed live targets belong here. A second target shares the same recorder.
    func targetDidStart(_ targetID: String, sessionID: String) throws {
        guard active?.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        if let session = active, !session.hasConfirmedLive {
            try? writeJournal(session, status: .confirmed)
            active?.hasConfirmedLive = true
        }
        active?.liveTargets.insert(targetID)
    }

    /// Atomic snapshot update. The owner detaches capture before beginning finalization.
    func reconcileTargets(_ targets: Set<String>, sessionID: String) throws {
        guard active?.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        if !targets.isEmpty, let session = active, !session.hasConfirmedLive {
            try? writeJournal(session, status: .confirmed)
            active?.hasConfirmedLive = true
        }
        active?.liveTargets = targets
    }

    /// Returns nil while another target is live. Call only after confirmed target termination.
    @discardableResult
    func targetDidEnd(_ targetID: String, sessionID: String) async throws -> SavedBroadcastRecording? {
        guard var session = active, session.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        guard session.liveTargets.remove(targetID) != nil else { return nil }
        active = session
        guard session.liveTargets.isEmpty else { return nil }
        return try await finish(sessionID: sessionID)
    }

    /// For confirmed whole-session termination. remainingTargetCount must come from the integration's
    /// actual provider state, including any provider started before this store was notified.
    @discardableResult
    func finish(sessionID: String, remainingTargetCount: Int = 0) async throws -> SavedBroadcastRecording? {
        guard let task = try beginFinalization(sessionID: sessionID, remainingTargetCount: remainingTargetCount) else { return nil }
        return try await task.value
    }

    /// Release active ownership synchronously so a following broadcast can start while disk I/O finishes.
    /// `forceLocalEnd` is only for a stopped local capture path, regardless of remote acknowledgement.
    func beginFinalization(sessionID: String, remainingTargetCount: Int = 0,
                           forceLocalEnd: Bool = false) throws -> Task<SavedBroadcastRecording, Error>? {
        guard let session = active, session.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        guard forceLocalEnd || (remainingTargetCount == 0 && session.liveTargets.isEmpty) else { return nil }
        try? writeJournal(session, status: .finalizing)
        active = nil
        if generation == session.generation { state = .finalizing }
        let lease = makeLease(for: session)
        return Task { @MainActor in
            defer {
                lease.end()
                if self.pendingCommits[session.id] == nil {
                    Self.ownedPaths.remove(Self.ownershipKey(session.partialURL))
                }
            }
            let result: LocalBroadcastRecordingResult
            do { result = try await session.recorder.stop() }
            catch {
                // Failed writers cannot be restarted; preserve their file without auto-recovery.
                try? self.writeJournal(session, status: .failed)
                if self.generation == session.generation { self.state = .failed(message: error.localizedDescription, canRetry: false) }
                throw error
            }
            let pending = PendingCommit(session: session, result: result)
            self.pendingCommits[session.id] = pending
            try? self.writeJournal(session, status: .writerCompleted)
            do { return try await self.commit(pending) }
            catch {
                if self.generation == session.generation {
                    self.retryID = session.id
                    self.state = .failed(message: error.localizedDescription, canRetry: true)
                }
                throw error
            }
        }
    }

    /// Only for total go-live failure, before any target became live. It never removes library files.
    func abortFailedStart(sessionID: String) async throws {
        guard let task = try beginAbortFailedStart(sessionID: sessionID) else { return }
        try await task.value
    }

    func beginAbortFailedStart(sessionID: String) throws -> Task<Void, Error>? {
        guard let session = active, session.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        guard session.liveTargets.isEmpty else { return nil }
        try? writeJournal(session, status: .aborted)
        active = nil
        if generation == session.generation { state = .idle }
        return Task { @MainActor in
            defer { Self.ownedPaths.remove(Self.ownershipKey(session.partialURL)) }
            _ = try? await session.recorder.stop()
            if FileManager.default.fileExists(atPath: session.partialURL.path) {
                try FileManager.default.removeItem(at: session.partialURL)
            }
            try? FileManager.default.removeItem(at: self.journalURL(session.id))
        }
    }

    @discardableResult
    func retryFinalization() async throws -> SavedBroadcastRecording {
        guard let retryID, let pending = pendingCommits[retryID] else { throw RecordingSessionStoreError.noRetry }
        if generation == pending.session.generation { state = .finalizing }
        let lease = makeLease(for: pending.session)
        Self.ownedPaths.insert(Self.ownershipKey(pending.session.partialURL))
        defer {
            lease.end()
            if pendingCommits[pending.session.id] == nil {
                Self.ownedPaths.remove(Self.ownershipKey(pending.session.partialURL))
            }
        }
        do { return try await commit(pending) }
        catch {
            if generation == pending.session.generation { state = .failed(message: error.localizedDescription, canRetry: true) }
            throw error
        }
    }

    /// Delete only a completed library entry; active and unfinished recordings are excluded.
    func delete(_ recording: SavedBroadcastRecording) throws {
        guard recordings.contains(where: { $0.id == recording.id }),
              recording.fileName == "\(recording.id.uuidString).mp4",
              active?.id != recording.id, pendingCommits[recording.id] == nil else {
            throw RecordingSessionStoreError.invalidFile
        }
        let url = fileURL(for: recording)
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
        // A reload can be suspended in asset validation. Do not let it restore this entry.
        deletedRecordingIDs.insert(recording.id)
        recordings.removeAll { $0.id == recording.id }
        if case .saved(let saved) = state, saved.id == recording.id { state = .idle }
        // Metadata alone cannot restore a deleted video. File deletion errors above remain visible.
        try? FileManager.default.removeItem(at: directory.appendingPathComponent("\(recording.id.uuidString).json"))
        try? FileManager.default.removeItem(at: journalURL(recording.id))
    }

    /// Validate durable MP4s on startup. A crash after rename but before metadata write is recoverable.
    /// Partial files are retained but never listed as saved recordings.
    func reloadLibrary() async {
        guard !isReloadingLibrary else { return }
        isReloadingLibrary = true
        defer { isReloadingLibrary = false }
        await recoverCompletedPartials()
        let existingIDs = Set(recordings.map(\.id))
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let urls = try FileManager.default.contentsOfDirectory(at: directory,
                includingPropertiesForKeys: [.creationDateKey, .fileSizeKey], options: [.skipsHiddenFiles])
            var recovered: [SavedBroadcastRecording] = []
            var rejected = 0
            for url in urls where url.pathExtension == "mp4" && !url.lastPathComponent.hasSuffix(".partial.mp4") {
                guard let id = UUID(uuidString: url.deletingPathExtension().lastPathComponent) else { continue }
                do {
                    let duration = try await validate(url)
                    guard !deletedRecordingIDs.contains(id) else { continue }
                    let metadataURL = directory.appendingPathComponent("\(id.uuidString).json")
                    let metadata = try? JSONDecoder().decode(SavedBroadcastRecording.self, from: Data(contentsOf: metadataURL))
                    let attributes = try url.resourceValues(forKeys: [.creationDateKey, .fileSizeKey])
                    let entry: SavedBroadcastRecording
                    if let metadata, metadata.id == id, metadata.fileName == url.lastPathComponent {
                        entry = SavedBroadcastRecording(id: id, sessionID: metadata.sessionID, createdAt: metadata.createdAt,
                            duration: duration, fileName: url.lastPathComponent, bytes: Int64(attributes.fileSize ?? 0),
                            videoFrames: metadata.videoFrames, audioFrames: metadata.audioFrames, droppedVideoFrames: metadata.droppedVideoFrames)
                    } else {
                        let journal = readJournal(id)
                        entry = SavedBroadcastRecording(id: id, sessionID: journal?.sessionID ?? "", createdAt: journal?.createdAt ?? attributes.creationDate ?? Date(),
                            duration: duration, fileName: url.lastPathComponent, bytes: Int64(attributes.fileSize ?? 0),
                            videoFrames: nil, audioFrames: nil, droppedVideoFrames: nil)
                    }
                    try JSONEncoder().encode(entry).write(to: metadataURL, options: .atomic)
                    recovered.append(entry)
                } catch { if !deletedRecordingIDs.contains(id) { rejected += 1 } }
            }
            // Preserve entries finalized while asynchronous validation was running.
            let completedDuringReload = recordings.filter { !existingIDs.contains($0.id) }
            let merged = Dictionary((recovered + completedDuringReload).map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })
            recordings = merged.values.filter { !deletedRecordingIDs.contains($0.id) }.sorted { $0.createdAt > $1.createdAt }
            let messages = [
                rejected == 0 ? nil : String(format: String(localized: "녹화 파일 %lld개를 확인하지 못했습니다."), Int64(rejected)),
                preservedRecordings.isEmpty ? nil : String(format: String(localized: "완료하지 못한 원본 %lld개를 기기에 보관하고 있습니다."), Int64(preservedRecordings.count))
            ].compactMap { $0 }
            libraryError = messages.isEmpty ? nil : messages.joined(separator: " ")
        } catch { libraryError = error.localizedDescription }
    }

    private func commit(_ pending: PendingCommit) async throws -> SavedBroadcastRecording {
        let session = pending.session
        let existsFinal = FileManager.default.fileExists(atPath: session.finalURL.path)
        let candidate = existsFinal ? session.finalURL : session.partialURL
        let duration = try await validate(candidate)
        if !existsFinal {
            // Same-directory rename makes a verified file visible under its final name atomically.
            try FileManager.default.moveItem(at: session.partialURL, to: session.finalURL)
        }
        let values = try session.finalURL.resourceValues(forKeys: [.fileSizeKey])
        let metadata = SavedBroadcastRecording(id: session.id, sessionID: session.sessionID, createdAt: session.createdAt,
            duration: duration, fileName: session.finalURL.lastPathComponent, bytes: Int64(values.fileSize ?? 0),
            videoFrames: pending.result.videoFrames, audioFrames: pending.result.audioFrames,
            droppedVideoFrames: pending.result.droppedVideoFrames)
        try JSONEncoder().encode(metadata).write(to: directory.appendingPathComponent("\(session.id.uuidString).json"), options: .atomic)
        recordings.removeAll { $0.id == metadata.id }
        recordings.append(metadata)
        recordings.sort { $0.createdAt > $1.createdAt }
        try? FileManager.default.removeItem(at: journalURL(session.id))
        pendingCommits.removeValue(forKey: session.id)
        if retryID == session.id { retryID = nil }
        if generation == session.generation { state = .saved(metadata) }
        return metadata
    }

    /// The writer completing is insufficient: require a playable asset with video and audio samples.
    private func validate(_ url: URL) async throws -> TimeInterval {
        let asset = AVURLAsset(url: url)
        guard try await asset.load(.isPlayable) else { throw RecordingSessionStoreError.invalidFile }
        let duration = CMTimeGetSeconds(try await asset.load(.duration))
        guard duration.isFinite, duration > 0 else { throw RecordingSessionStoreError.invalidFile }
        let video = try await asset.loadTracks(withMediaType: .video)
        let audio = try await asset.loadTracks(withMediaType: .audio)
        guard let videoTrack = video.first, let audioTrack = audio.first else { throw RecordingSessionStoreError.invalidFile }
        let outputs: [(AVAssetTrack, [String: Any])] = [
            (videoTrack, [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]),
            (audioTrack, [AVFormatIDKey: kAudioFormatLinearPCM])
        ]
        for (track, settings) in outputs {
            let reader = try AVAssetReader(asset: asset)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
            guard reader.canAdd(output) else { throw RecordingSessionStoreError.invalidFile }
            reader.add(output)
            guard reader.startReading(), let sample = output.copyNextSampleBuffer(), CMSampleBufferGetNumSamples(sample) > 0 else {
                throw reader.error ?? RecordingSessionStoreError.invalidFile
            }
            reader.cancelReading()
        }
        return duration
    }

    private func makeLease(for session: Session) -> any RecordingBackgroundLease {
        backgroundLeaseFactory("Finish broadcast recording") { [weak self] in
            guard let self, self.generation == session.generation else { return }
            // stop()/finishWriting may still be running. Do not enable retry on the same writer.
            // If real validation later succeeds, commit is allowed to report that actual success.
            self.state = .failed(message: RecordingSessionStoreError.backgroundTimeExpired.localizedDescription, canRetry: false)
        }
    }

    /// iOS may enumerate /private/var while the writer received /var (or a
    /// caller-supplied directory symlink). Resolve the parent even before the MP4 exists.
    private static func ownershipKey(_ url: URL) -> String {
        url.deletingLastPathComponent().resolvingSymlinksInPath()
            .appendingPathComponent(url.lastPathComponent).standardizedFileURL.path
    }

    private func journalURL(_ id: UUID) -> URL {
        directory.appendingPathComponent("\(id.uuidString).pending.json")
    }

    private func writeJournal(_ session: Session, status: RecordingRecoveryJournal.Status) throws {
        let journal = RecordingRecoveryJournal(id: session.id, sessionID: session.sessionID,
                                              createdAt: session.createdAt, status: status)
        try JSONEncoder().encode(journal).write(to: journalURL(session.id), options: .atomic)
    }

    private func readJournal(_ id: UUID) -> RecordingRecoveryJournal? {
        guard let data = try? Data(contentsOf: journalURL(id)),
              let journal = try? JSONDecoder().decode(RecordingRecoveryJournal.self, from: data),
              journal.id == id else { return nil }
        return journal
    }

    /// Only abandoned, confirmed sessions with a fully readable MP4 can become saved recordings.
    /// Missing journals, failed writers, corrupt media, and duplicate names stay untouched.
    private func recoverCompletedPartials() async {
        guard let urls = try? FileManager.default.contentsOfDirectory(at: directory,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else {
            preservedRecordings = []
            return
        }
        var preserved: [PreservedBroadcastRecording] = []
        for url in urls where url.lastPathComponent.hasSuffix(".partial.mp4") {
            let stem = String(url.lastPathComponent.dropLast(".partial.mp4".count))
            guard let id = UUID(uuidString: stem) else { continue }
            let key = Self.ownershipKey(url)
            guard !Self.ownedPaths.contains(key) else { continue }
            let before = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            var reason = String(localized: "저장이 완료되지 않은 원본을 보관하고 있습니다.")
            if let journal = readJournal(id), journal.status.mayRecover {
                let finalURL = directory.appendingPathComponent("\(id.uuidString).mp4")
                if !FileManager.default.fileExists(atPath: finalURL.path) {
                    Self.ownedPaths.insert(key)
                    let lease = backgroundLeaseFactory("Recover broadcast recording", {})
                    do {
                        // Decode on a utility task: a long file must not block main-actor UI work.
                        let duration = try await Task.detached(priority: .utility) {
                            try await Self.validateEntireRecoveryFile(url)
                        }.value
                        let after = try url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
                        guard before?.fileSize == after.fileSize,
                              before?.contentModificationDate == after.contentModificationDate else {
                            throw RecordingSessionStoreError.invalidFile
                        }
                        try FileManager.default.moveItem(at: url, to: finalURL)
                        let metadata = SavedBroadcastRecording(id: id, sessionID: journal.sessionID,
                            createdAt: journal.createdAt, duration: duration, fileName: finalURL.lastPathComponent,
                            bytes: Int64(after.fileSize ?? 0), videoFrames: nil, audioFrames: nil, droppedVideoFrames: nil)
                        try JSONEncoder().encode(metadata).write(to: directory.appendingPathComponent("\(id.uuidString).json"), options: .atomic)
                        try? FileManager.default.removeItem(at: journalURL(id))
                        lease.end()
                        Self.ownedPaths.remove(key)
                        continue
                    } catch {
                        reason = String(localized: "완료 여부를 확인하지 못한 원본을 보관하고 있습니다.")
                        lease.end()
                        Self.ownedPaths.remove(key)
                        // Rename may have succeeded before metadata write failed. The final-file
                        // scan below will validate and rebuild metadata; never remove that MP4.
                        if !FileManager.default.fileExists(atPath: url.path) { continue }
                    }
                } else {
                    reason = String(localized: "같은 이름의 저장 파일이 있어 임시 원본도 보관하고 있습니다.")
                }
            } else {
                reason = String(localized: "방송 시작 또는 저장 완료를 확인하지 못한 원본을 보관하고 있습니다.")
            }
            preserved.append(PreservedBroadcastRecording(id: id, fileName: url.lastPathComponent,
                bytes: Int64(before?.fileSize ?? 0), modifiedAt: before?.contentModificationDate, reason: reason))
        }
        preservedRecordings = preserved.sorted { ($0.modifiedAt ?? .distantPast) > ($1.modifiedAt ?? .distantPast) }
    }

    nonisolated private static func validateEntireRecoveryFile(_ url: URL) async throws -> TimeInterval {
        let asset = AVURLAsset(url: url)
        guard try await asset.load(.isPlayable) else { throw RecordingSessionStoreError.invalidFile }
        let duration = CMTimeGetSeconds(try await asset.load(.duration))
        guard duration.isFinite, duration > 0 else { throw RecordingSessionStoreError.invalidFile }
        let videos = try await asset.loadTracks(withMediaType: .video)
        let audios = try await asset.loadTracks(withMediaType: .audio)
        guard let video = videos.first, let audio = audios.first else { throw RecordingSessionStoreError.invalidFile }
        let outputs: [(AVAssetTrack, [String: Any])] = [
            (video, [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]),
            (audio, [AVFormatIDKey: kAudioFormatLinearPCM])
        ]
        for (track, settings) in outputs {
            let reader = try AVAssetReader(asset: asset)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
            guard reader.canAdd(output) else { throw RecordingSessionStoreError.invalidFile }
            reader.add(output)
            guard reader.startReading() else { throw RecordingSessionStoreError.invalidFile }
            var samples = 0
            while let sample = output.copyNextSampleBuffer() {
                try Task.checkCancellation()
                samples += CMSampleBufferGetNumSamples(sample)
            }
            guard reader.status == .completed, samples > 0 else {
                throw reader.error ?? RecordingSessionStoreError.invalidFile
            }
        }
        return duration
    }

    nonisolated private static func readAvailableCapacity(_ url: URL) throws -> Int64 {
        let values = try url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        if let important = values.volumeAvailableCapacityForImportantUsage { return important }
        if let available = values.volumeAvailableCapacity { return Int64(available) }
        throw RecordingSessionStoreError.capacityUnavailable
    }
}
