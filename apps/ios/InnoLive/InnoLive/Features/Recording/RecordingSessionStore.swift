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
    var source: RecordingSource? = nil
    var mosaic: OnDeviceMosaicSettings? = nil
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
    /// 새로 커밋된 파일마다 한 번 불린다. 일반 저장과 크래시 복구 양쪽에서 부른다.
    var onSaved: (SavedBroadcastRecording) -> Void = { _ in }
    private static var ownedPaths: Set<String> = []
    private var isReloadingLibrary = false
    private var deletedRecordingIDs: Set<UUID> = []
    private struct Session {
        let generation: UUID
        let id: UUID
        let sessionID: String
        let createdAt: Date
        let source: RecordingSource
        let mosaic: OnDeviceMosaicSettings?
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

    /// 저장 위치와 용량 조회 함수를 주입하면 실제 라이브러리를 건드리지 않고 파일 시스템 테스트를 할 수 있다.
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

    /// 실제 go-live 요청 전에 호출하고, 반환된 recorder를 영상·음성 sink에 연결한다.
    /// 이전 세션이 아직 마무리 저장 중일 수 있다. 그 세션이 끝나도 이 세션의 상태를 덮어쓰지 못한다.
    @discardableResult
    func begin(sessionID: String, source: RecordingSource = .raw, mosaic: OnDeviceMosaicSettings? = nil,
               onFailure: @escaping @Sendable (String) -> Void = { _ in }) throws -> LocalBroadcastRecorder {
        guard active == nil else { throw RecordingSessionStoreError.alreadyRecording }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard try availableCapacity(directory) >= minimumFreeBytes else { throw RecordingSessionStoreError.insufficientSpace }
        let id = UUID()
        let token = UUID()
        let partialURL = partialRecordingURL(id)
        let finalURL = finalRecordingURL(id)
        let recorder = LocalBroadcastRecorder(source: source, onFailure: onFailure)
        try recorder.start(url: partialURL)
        generation = token
        let session = Session(generation: token, id: id, sessionID: sessionID, createdAt: Date(),
            source: source, mosaic: source == .protected ? mosaic : nil, partialURL: partialURL, finalURL: finalURL, recorder: recorder)
        try writeJournal(session, status: .provisional)
        active = session
        Self.ownedPaths.insert(Self.ownershipKey(partialURL))
        retryID = nil
        state = .recording
        return recorder
    }

    /// 라이브가 확정된 대상만 여기에 넣는다. 두 번째 대상은 같은 recorder를 함께 쓴다.
    func targetDidStart(_ targetID: String, sessionID: String) throws {
        guard active?.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        if let session = active, !session.hasConfirmedLive {
            try? writeJournal(session, status: .confirmed)
            active?.hasConfirmedLive = true
        }
        active?.liveTargets.insert(targetID)
    }

    /// 대상 스냅샷을 원자적으로 갱신한다. 소유자는 마무리 저장을 시작하기 전에 캡처를 떼어 낸다.
    func reconcileTargets(_ targets: Set<String>, sessionID: String) throws {
        guard active?.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        if !targets.isEmpty, let session = active, !session.hasConfirmedLive {
            try? writeJournal(session, status: .confirmed)
            active?.hasConfirmedLive = true
        }
        active?.liveTargets = targets
    }

    /// 다른 대상이 아직 라이브 중이면 nil을 반환한다. 대상 종료가 확정된 뒤에만 호출한다.
    @discardableResult
    func targetDidEnd(_ targetID: String, sessionID: String) async throws -> SavedBroadcastRecording? {
        guard var session = active, session.sessionID == sessionID else { throw RecordingSessionStoreError.wrongSession }
        guard session.liveTargets.remove(targetID) != nil else { return nil }
        active = session
        guard session.liveTargets.isEmpty else { return nil }
        return try await finish(sessionID: sessionID)
    }

    /// 세션 전체 종료가 확정됐을 때 쓴다. remainingTargetCount는 연동 쪽의 실제 provider 상태에서 가져와야 하고,
    /// 이 store가 알림을 받기 전에 시작된 provider도 포함해야 한다.
    @discardableResult
    func finish(sessionID: String, remainingTargetCount: Int = 0) async throws -> SavedBroadcastRecording? {
        guard let task = try beginFinalization(sessionID: sessionID, remainingTargetCount: remainingTargetCount) else { return nil }
        return try await task.value
    }

    /// 진행 중 소유권을 동기로 놓아서 디스크 I/O가 끝나는 동안에도 다음 방송을 시작할 수 있게 한다.
    /// `forceLocalEnd`는 원격 확인 여부와 상관없이 로컬 캡처가 멈춘 경로에서만 쓴다.
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
                // 실패한 writer는 다시 시작할 수 없다. 파일은 보관하되 자동 복구는 하지 않는다.
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

    /// 어떤 대상도 라이브가 되기 전에 go-live가 전부 실패했을 때만 쓴다. 라이브러리 파일은 절대 지우지 않는다.
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

    /// 완료된 라이브러리 항목만 지운다. 진행 중이거나 아직 마무리되지 않은 녹화는 제외한다.
    func delete(_ recording: SavedBroadcastRecording) throws {
        guard recordings.contains(where: { $0.id == recording.id }),
              recording.fileName == finalRecordingURL(recording.id).lastPathComponent,
              active?.id != recording.id, pendingCommits[recording.id] == nil else {
            throw RecordingSessionStoreError.invalidFile
        }
        let url = fileURL(for: recording)
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
        // reload가 asset 검증 도중 suspend돼 있을 수 있다. 그 reload가 이 항목을 되살리지 못하게 한다.
        deletedRecordingIDs.insert(recording.id)
        recordings.removeAll { $0.id == recording.id }
        if case .saved(let saved) = state, saved.id == recording.id { state = .idle }
        // 메타데이터만으로는 지운 영상을 되살릴 수 없다. 위의 파일 삭제 오류는 그대로 드러낸다.
        try? FileManager.default.removeItem(at: metadataURL(recording.id))
        try? FileManager.default.removeItem(at: journalURL(recording.id))
    }

    /// 시작할 때 최종 저장된 MP4를 검증한다. 이름을 바꾼 뒤 메타데이터를 쓰기 전에 앱이 죽어도 복구할 수 있다.
    /// 부분 파일은 보관하지만 저장된 녹화 목록에는 절대 넣지 않는다.
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
            for url in urls where url.pathExtension == "mp4" && !url.lastPathComponent.hasSuffix(Self.partialSuffix) {
                guard let id = UUID(uuidString: url.deletingPathExtension().lastPathComponent) else { continue }
                do {
                    let duration = try await validate(url)
                    guard !deletedRecordingIDs.contains(id) else { continue }
                    let metadata = try? JSONDecoder().decode(SavedBroadcastRecording.self, from: Data(contentsOf: metadataURL(id)))
                    let attributes = try url.resourceValues(forKeys: [.creationDateKey, .fileSizeKey])
                    let entry: SavedBroadcastRecording
                    if let metadata, metadata.id == id, metadata.fileName == url.lastPathComponent {
                        entry = SavedBroadcastRecording(id: id, sessionID: metadata.sessionID, createdAt: metadata.createdAt,
                            duration: duration, fileName: url.lastPathComponent, bytes: Int64(attributes.fileSize ?? 0),
                            videoFrames: metadata.videoFrames, audioFrames: metadata.audioFrames, droppedVideoFrames: metadata.droppedVideoFrames,
                            source: metadata.source, mosaic: metadata.mosaic)
                    } else {
                        let journal = readJournal(id)
                        entry = SavedBroadcastRecording(id: id, sessionID: journal?.sessionID ?? "", createdAt: journal?.createdAt ?? attributes.creationDate ?? Date(),
                            duration: duration, fileName: url.lastPathComponent, bytes: Int64(attributes.fileSize ?? 0),
                            videoFrames: nil, audioFrames: nil, droppedVideoFrames: nil,
                            source: journal?.source, mosaic: journal?.mosaic)
                    }
                    try writeMetadata(entry)
                    recovered.append(entry)
                } catch { if !deletedRecordingIDs.contains(id) { rejected += 1 } }
            }
            // 비동기 검증이 도는 동안 마무리 저장된 항목도 그대로 둔다.
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
            // 같은 폴더 안에서 이름을 바꾸므로 검증된 파일이 최종 이름으로 원자적으로 나타난다.
            try FileManager.default.moveItem(at: session.partialURL, to: session.finalURL)
        }
        let values = try session.finalURL.resourceValues(forKeys: [.fileSizeKey])
        let metadata = SavedBroadcastRecording(id: session.id, sessionID: session.sessionID, createdAt: session.createdAt,
            duration: duration, fileName: session.finalURL.lastPathComponent, bytes: Int64(values.fileSize ?? 0),
            videoFrames: pending.result.videoFrames, audioFrames: pending.result.audioFrames,
            droppedVideoFrames: pending.result.droppedVideoFrames,
            source: session.source, mosaic: session.mosaic)
        try writeMetadata(metadata)
        onSaved(metadata)
        recordings.removeAll { $0.id == metadata.id }
        recordings.append(metadata)
        recordings.sort { $0.createdAt > $1.createdAt }
        try? FileManager.default.removeItem(at: journalURL(session.id))
        pendingCommits.removeValue(forKey: session.id)
        if retryID == session.id { retryID = nil }
        if generation == session.generation { state = .saved(metadata) }
        return metadata
    }

    /// writer가 완료됐다는 것만으로는 부족하다. 재생할 수 있고 영상·음성 샘플이 들어 있는 asset이어야 한다.
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
            // stop()/finishWriting이 아직 돌고 있을 수 있다. 같은 writer로는 재시도하지 못하게 한다.
            // 나중에 실제 검증이 성공하면 commit이 그 실제 성공을 알려도 된다.
            self.state = .failed(message: RecordingSessionStoreError.backgroundTimeExpired.localizedDescription, canRetry: false)
        }
    }

    /// writer는 /var(또는 호출부가 넘긴 폴더 심볼릭 링크) 경로를 받았는데 iOS는
    /// /private/var로 나열할 수 있다. MP4가 아직 없어도 상위 폴더 경로를 풀어서 맞춘다.
    private static func ownershipKey(_ url: URL) -> String {
        url.deletingLastPathComponent().resolvingSymlinksInPath()
            .appendingPathComponent(url.lastPathComponent).standardizedFileURL.path
    }

    private static let partialSuffix = ".partial.mp4"

    private func partialRecordingURL(_ id: UUID) -> URL {
        directory.appendingPathComponent("\(id.uuidString)\(Self.partialSuffix)")
    }

    private func finalRecordingURL(_ id: UUID) -> URL {
        directory.appendingPathComponent("\(id.uuidString).mp4")
    }

    private func metadataURL(_ id: UUID) -> URL {
        directory.appendingPathComponent("\(id.uuidString).json")
    }

    private func journalURL(_ id: UUID) -> URL {
        directory.appendingPathComponent("\(id.uuidString).pending.json")
    }

    private func writeMetadata(_ metadata: SavedBroadcastRecording) throws {
        try JSONEncoder().encode(metadata).write(to: metadataURL(metadata.id), options: .atomic)
    }

    private func writeJournal(_ session: Session, status: RecordingRecoveryJournal.Status) throws {
        let journal = RecordingRecoveryJournal(id: session.id, sessionID: session.sessionID,
                                              createdAt: session.createdAt, status: status,
                                              source: session.source, mosaic: session.mosaic)
        try JSONEncoder().encode(journal).write(to: journalURL(session.id), options: .atomic)
    }

    private func readJournal(_ id: UUID) -> RecordingRecoveryJournal? {
        guard let data = try? Data(contentsOf: journalURL(id)),
              let journal = try? JSONDecoder().decode(RecordingRecoveryJournal.self, from: data),
              journal.id == id else { return nil }
        return journal
    }

    /// 중단됐지만 라이브가 확정됐고 MP4를 끝까지 읽을 수 있는 세션만 저장된 녹화가 될 수 있다.
    /// 저널이 없거나, writer가 실패했거나, 미디어가 손상됐거나, 이름이 겹치는 경우는 건드리지 않는다.
    private func recoverCompletedPartials() async {
        guard let urls = try? FileManager.default.contentsOfDirectory(at: directory,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else {
            preservedRecordings = []
            return
        }
        var preserved: [PreservedBroadcastRecording] = []
        for url in urls where url.lastPathComponent.hasSuffix(Self.partialSuffix) {
            let stem = String(url.lastPathComponent.dropLast(Self.partialSuffix.count))
            guard let id = UUID(uuidString: stem) else { continue }
            let key = Self.ownershipKey(url)
            guard !Self.ownedPaths.contains(key) else { continue }
            let before = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            var reason = String(localized: "저장이 완료되지 않은 원본을 보관하고 있습니다.")
            if let journal = readJournal(id), journal.status.mayRecover {
                let finalURL = finalRecordingURL(id)
                if !FileManager.default.fileExists(atPath: finalURL.path) {
                    Self.ownedPaths.insert(key)
                    let lease = backgroundLeaseFactory("Recover broadcast recording", {})
                    do {
                        // utility 작업에서 디코딩한다. 긴 파일이 main actor의 UI 작업을 막으면 안 된다.
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
                        try writeMetadata(metadata)
                        onSaved(metadata)
                        try? FileManager.default.removeItem(at: journalURL(id))
                        lease.end()
                        Self.ownedPaths.remove(key)
                        continue
                    } catch {
                        reason = String(localized: "완료 여부를 확인하지 못한 원본을 보관하고 있습니다.")
                        lease.end()
                        Self.ownedPaths.remove(key)
                        // 메타데이터 쓰기가 실패하기 전에 이름 바꾸기는 성공했을 수 있다. 아래 최종 파일
                        // 검사에서 검증하고 메타데이터를 다시 만들므로 그 MP4는 절대 지우지 않는다.
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
