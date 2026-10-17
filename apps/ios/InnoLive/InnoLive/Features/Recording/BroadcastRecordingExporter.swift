import AVFoundation
import Photos

struct BroadcastRecordingExportPackage {
    let urls: [URL]
    fileprivate let ownedDirectory: URL?
}

enum BroadcastRecordingExportError: LocalizedError {
    case missingFile, missingTracks, unsupportedFormat, invalidOutput, photosDenied
    var errorDescription: String? {
        switch self {
        case .missingFile: return String(localized: "원본 영상 파일을 찾을 수 없습니다.", table: "BroadcastRecording")
        case .missingTracks: return String(localized: "원본 영상에 영상과 음성 트랙이 모두 필요합니다.", table: "BroadcastRecording")
        case .unsupportedFormat: return String(localized: "이 파일의 분리 저장 형식을 지원하지 않습니다.", table: "BroadcastRecording")
        case .invalidOutput: return String(localized: "내보낸 파일을 확인하지 못했습니다. 다시 시도해 주세요.", table: "BroadcastRecording")
        case .photosDenied: return String(localized: "사진 앱에 저장하려면 사진 추가 권한을 허용해 주세요.", table: "BroadcastRecording")
        }
    }
}

/// Owns only its UUID export folders. Never removes the app's recording source.
@MainActor
final class BroadcastRecordingExporter {
    nonisolated deinit {}
    nonisolated static let defaultRoot = FileManager.default.temporaryDirectory.appendingPathComponent("BroadcastExports", isDirectory: true)
    private static let ownershipMarker = ".innolive-export-v1"
    private static let ownershipContents = "InnoLive broadcast export v1"
    private let root: URL
    /// Injection permits deterministic partial-write/cancellation tests; normal callers use native export.
    private let exportOperation: (@MainActor (AVAsset, URL, AVFileType) async throws -> Void)?
    private var ownedDirectories: Set<URL> = []

    init(root: URL = BroadcastRecordingExporter.defaultRoot,
         exportOperation: (@MainActor (AVAsset, URL, AVFileType) async throws -> Void)? = nil) {
        self.root = root
        self.exportOperation = exportOperation
    }

    /// Call once at cold startup, before any sharing/export UI exists. Never during active sharing.
    /// Touches only old UUID directories with our marker; skips symlinks and unrecognized entries.
    @discardableResult
    static func cleanupStaleExportsOnColdStart(root: URL = defaultRoot,
                                               now: Date = Date(), retention: TimeInterval = 24 * 3600) throws -> Int {
        guard retention >= 24 * 3600 else { return 0 }
        let manager = FileManager.default
        guard manager.fileExists(atPath: root.path) else { return 0 }
        let rootValues = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard rootValues.isDirectory == true, rootValues.isSymbolicLink != true else { return 0 }
        var removed = 0
        for directory in try manager.contentsOfDirectory(at: root,
                includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]) {
            guard UUID(uuidString: directory.lastPathComponent) != nil else { continue }
            let values = try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isDirectory == true, values.isSymbolicLink != true else { continue }
            let marker = directory.appendingPathComponent(ownershipMarker)
            guard let markerValues = try? marker.resourceValues(forKeys: [
                .isRegularFileKey, .isSymbolicLinkKey, .contentModificationDateKey, .fileSizeKey,
            ]), markerValues.isRegularFile == true, markerValues.isSymbolicLink != true,
                  markerValues.fileSize == ownershipContents.utf8.count,
                  let modified = markerValues.contentModificationDate,
                  now.timeIntervalSince(modified) > retention,
                  (try? String(contentsOf: marker, encoding: .utf8)) == ownershipContents else { continue }
            try manager.removeItem(at: directory)
            removed += 1
        }
        return removed
    }

    func original(_ url: URL) async throws -> BroadcastRecordingExportPackage {
        let source = try await inspectSource(url)
        defer { withExtendedLifetime(source.asset) {} }
        try await verify(url, videos: source.video.count, audios: source.audio.count, duration: source.duration)
        return .init(urls: [url], ownedDirectory: nil)
    }

    func split(_ url: URL) async throws -> BroadcastRecordingExportPackage {
        try Task.checkCancellation()
        let source = try await inspectSource(url)
        defer { withExtendedLifetime(source.asset) {} }
        let directory = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        ownedDirectories.insert(directory)
        do {
            let marker = directory.appendingPathComponent(Self.ownershipMarker)
            try Self.ownershipContents.write(to: marker, atomically: true, encoding: .utf8)
            let video = try await composition(tracks: source.video)
            let audio = try await composition(tracks: source.audio)
            let videoURL = directory.appendingPathComponent("video.mp4")
            let audioURL = directory.appendingPathComponent("audio.m4a")
            let videoDuration = try await video.load(.duration)
            let audioDuration = try await audio.load(.duration)
            try await export(video, to: videoURL, type: .mp4)
            try await export(audio, to: audioURL, type: .m4a)
            try Task.checkCancellation()
            try await verify(videoURL, videos: source.video.count, audios: 0, duration: videoDuration)
            try await verify(audioURL, videos: 0, audios: source.audio.count, duration: audioDuration)
            try Task.checkCancellation()
            // Age successful exports from completion, not from an unusually long export start.
            try FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: marker.path)
            return .init(urls: [videoURL, audioURL], ownedDirectory: directory)
        } catch {
            removeOwnedDirectory(directory)
            throw error
        }
    }

    func release(_ package: BroadcastRecordingExportPackage) {
        guard let directory = package.ownedDirectory else { return }
        removeOwnedDirectory(directory)
    }

    func saveOriginalToPhotos(_ url: URL) async throws {
        _ = try await original(url)
        try Task.checkCancellation()
        var authorization = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        if authorization == .notDetermined {
            authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        }
        guard authorization == .authorized || authorization == .limited else {
            throw BroadcastRecordingExportError.photosDenied
        }
        try Task.checkCancellation()
        // A successful return is the sole source of the UI's photo-save confirmation.
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: url, options: nil)
        }
    }

    private func inspectSource(_ url: URL) async throws -> (asset: AVURLAsset, video: [AVAssetTrack], audio: [AVAssetTrack], duration: CMTime) {
        guard url.isFileURL, FileManager.default.fileExists(atPath: url.path) else {
            throw BroadcastRecordingExportError.missingFile
        }
        let asset = AVURLAsset(url: url)
        let video = try await asset.loadTracks(withMediaType: .video)
        let audio = try await asset.loadTracks(withMediaType: .audio)
        let duration = try await asset.load(.duration)
        guard !video.isEmpty, !audio.isEmpty else { throw BroadcastRecordingExportError.missingTracks }
        guard duration.isNumeric, CMTimeGetSeconds(duration) > 0 else {
            throw BroadcastRecordingExportError.invalidOutput
        }
        return (asset, video, audio, duration)
    }

    private func composition(tracks: [AVAssetTrack]) async throws -> AVMutableComposition {
        let result = AVMutableComposition()
        for source in tracks {
            try Task.checkCancellation()
            guard let target = result.addMutableTrack(withMediaType: source.mediaType,
                                                      preferredTrackID: kCMPersistentTrackID_Invalid) else {
                throw BroadcastRecordingExportError.unsupportedFormat
            }
            let range = try await source.load(.timeRange)
            // Keep offsets and internal gaps: neither stream is independently shifted to zero.
            try target.insertTimeRange(range, of: source, at: range.start)
            if source.mediaType == .video { target.preferredTransform = try await source.load(.preferredTransform) }
        }
        return result
    }

    private func export(_ asset: AVAsset, to url: URL, type: AVFileType) async throws {
        try Task.checkCancellation()
        if let exportOperation {
            try await exportOperation(asset, url, type)
            return
        }
        guard var session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            throw BroadcastRecordingExportError.unsupportedFormat
        }
        if !session.supportedFileTypes.contains(type), type == .m4a {
            // Only fall back to AAC encoding when the container cannot pass through this source.
            guard let fallback = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
                throw BroadcastRecordingExportError.unsupportedFormat
            }
            session = fallback
        }
        guard session.supportedFileTypes.contains(type) else { throw BroadcastRecordingExportError.unsupportedFormat }
        // SDK implementation propagates task cancellation to cancelExport().
        do {
            try await session.export(to: url, as: type)
        } catch {
            let failure = error as NSError
            let incompatibleMedia = failure.domain == AVFoundationErrorDomain
                && [AVError.operationNotSupportedForAsset.rawValue,
                    AVError.operationNotSupportedForPreset.rawValue].contains(failure.code)
            guard type == .m4a, session.presetName == AVAssetExportPresetPassthrough,
                  incompatibleMedia else { throw error }
            try Task.checkCancellation()
            // M4A passthrough rejects some valid AAC edit lists (for example a delayed start).
            // Retry only this supported-media failure, never disk/cancellation/unknown failures.
            try? FileManager.default.removeItem(at: url)
            guard let fallback = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A),
                  fallback.supportedFileTypes.contains(type) else { throw error }
            try await fallback.export(to: url, as: type)
        }
    }

    private func verify(_ url: URL, videos: Int, audios: Int, duration: CMTime) async throws {
        try Task.checkCancellation()
        let asset = AVURLAsset(url: url)
        let video = try await asset.loadTracks(withMediaType: .video)
        let audio = try await asset.loadTracks(withMediaType: .audio)
        let actual = try await asset.load(.duration)
        let playable = try await asset.load(.isPlayable)
        let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard playable, bytes > 0, video.count == videos, audio.count == audios,
              actual.isNumeric, abs(CMTimeGetSeconds(actual) - CMTimeGetSeconds(duration)) <= 0.15 else {
            throw BroadcastRecordingExportError.invalidOutput
        }
    }

    private func removeOwnedDirectory(_ url: URL) {
        guard ownedDirectories.remove(url) != nil else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
