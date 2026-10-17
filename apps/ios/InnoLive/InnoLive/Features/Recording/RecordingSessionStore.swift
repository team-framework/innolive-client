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
    case alreadyRecording, insufficientSpace, capacityUnavailable, wrongSession, invalidFile, noRetry
    var errorDescription: String? {
        switch self {
        case .alreadyRecording: "이미 진행 중인 녹화가 있습니다."
        case .insufficientSpace: "원본 영상을 저장할 공간이 부족합니다."
        case .capacityUnavailable: "녹화에 필요한 저장 공간을 확인하지 못했습니다."
        case .wrongSession: "현재 방송과 녹화 세션이 일치하지 않습니다."
        case .invalidFile: "저장한 영상과 음성 파일을 확인하지 못했습니다."
        case .noRetry: "다시 저장할 수 있는 완료 파일이 없습니다."
        }
    }
}

@MainActor final class RecordingSessionStore: ObservableObject {
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
    private struct Session {
        let generation: UUID
        let id: UUID
        let sessionID: String
        let createdAt: Date
        let partialURL: URL
        let finalURL: URL
        let recorder: LocalBroadcastRecorder
        var liveTargets: Set<String> = []
    }
    private struct PendingCommit {
        let session: Session
        let result: LocalBroadcastRecordingResult
    }
    private let directory: URL
    private let minimumFreeBytes: Int64
    private let availableCapacity: @Sendable (URL) throws -> Int64
    private var generation = UUID()
    private var active: Session?
    private var pendingCommits: [UUID: PendingCommit] = [:]
    private var retryID: UUID?

    /// The injected location/capacity reader allow filesystem tests without touching the real library.
    init(directory: URL? = nil, minimumFreeBytes: Int64 = 256 * 1_024 * 1_024,
         availableCapacity: @escaping @Sendable (URL) throws -> Int64 = RecordingSessionStore.readAvailableCapacity) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Recordings", isDirectory: true)
        self.minimumFreeBytes = minimumFreeBytes
        self.availableCapacity = availableCapacity
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
        active = Session(generation: token, id: id, sessionID: sessionID, createdAt: Date(),
            partialURL: partialURL, finalURL: finalURL, recorder: recorder)
        retryID = nil
        state = .recording
        return recorder
    }

    /// Only confirmed live targets belong here. A second target shares the same recorder.
    func targetDidStart(_ targetID: String, sessionID: String) throws {
        guard active?.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        active?.liveTargets.insert(targetID)
    }

    /// Atomic snapshot update. The owner detaches capture before beginning finalization.
    func reconcileTargets(_ targets: Set<String>, sessionID: String) throws {
        guard active?.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
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
        active = nil
        if generation == session.generation { state = .finalizing }
        return Task { @MainActor in
            let result: LocalBroadcastRecordingResult
            do { result = try await session.recorder.stop() }
            catch {
                // Failed writers cannot be restarted; do not expose a misleading Retry button.
                if self.generation == session.generation { self.state = .failed(message: error.localizedDescription, canRetry: false) }
                throw error
            }
            let pending = PendingCommit(session: session, result: result)
            self.pendingCommits[session.id] = pending
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
        active = nil
        if generation == session.generation { state = .idle }
        return Task { @MainActor in
            _ = try? await session.recorder.stop()
            if FileManager.default.fileExists(atPath: session.partialURL.path) {
                try FileManager.default.removeItem(at: session.partialURL)
            }
        }
    }

    @discardableResult
    func retryFinalization() async throws -> SavedBroadcastRecording {
        guard let retryID, let pending = pendingCommits[retryID] else { throw RecordingSessionStoreError.noRetry }
        if generation == pending.session.generation { state = .finalizing }
        do { return try await commit(pending) }
        catch {
            if generation == pending.session.generation { state = .failed(message: error.localizedDescription, canRetry: true) }
            throw error
        }
    }

    /// Validate durable MP4s on startup. A crash after rename but before metadata write is recoverable.
    /// Partial files are retained but never listed as saved recordings.
    func reloadLibrary() async {
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
                    let metadataURL = directory.appendingPathComponent("\(id.uuidString).json")
                    let metadata = try? JSONDecoder().decode(SavedBroadcastRecording.self, from: Data(contentsOf: metadataURL))
                    let attributes = try url.resourceValues(forKeys: [.creationDateKey, .fileSizeKey])
                    let entry: SavedBroadcastRecording
                    if let metadata, metadata.id == id, metadata.fileName == url.lastPathComponent {
                        entry = SavedBroadcastRecording(id: id, sessionID: metadata.sessionID, createdAt: metadata.createdAt,
                            duration: duration, fileName: url.lastPathComponent, bytes: Int64(attributes.fileSize ?? 0),
                            videoFrames: metadata.videoFrames, audioFrames: metadata.audioFrames, droppedVideoFrames: metadata.droppedVideoFrames)
                    } else {
                        entry = SavedBroadcastRecording(id: id, sessionID: "", createdAt: attributes.creationDate ?? Date(),
                            duration: duration, fileName: url.lastPathComponent, bytes: Int64(attributes.fileSize ?? 0),
                            videoFrames: nil, audioFrames: nil, droppedVideoFrames: nil)
                    }
                    try JSONEncoder().encode(entry).write(to: metadataURL, options: .atomic)
                    recovered.append(entry)
                } catch { rejected += 1 }
            }
            // Preserve entries finalized while asynchronous validation was running.
            let completedDuringReload = recordings.filter { !existingIDs.contains($0.id) }
            let merged = Dictionary((recovered + completedDuringReload).map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })
            recordings = merged.values.sorted { $0.createdAt > $1.createdAt }
            libraryError = rejected == 0 ? nil : "녹화 파일 \(rejected)개를 확인하지 못했습니다."
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

    nonisolated private static func readAvailableCapacity(_ url: URL) throws -> Int64 {
        let values = try url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        if let important = values.volumeAvailableCapacityForImportantUsage { return important }
        if let available = values.volumeAvailableCapacity { return Int64(available) }
        throw RecordingSessionStoreError.capacityUnavailable
    }
}
