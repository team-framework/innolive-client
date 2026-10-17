import AVKit
import SwiftUI

struct BroadcastRecordingPlayback: Identifiable {
    let url: URL
    var id: URL { url }
}

struct BroadcastRecordingLibraryView: View {
    @ObservedObject var store: RecordingSessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var recordingToDelete: SavedBroadcastRecording?
    @State private var deletionError: String?
    // Export is added in the next feature; callers can supply the verified file URL.
    var onExport: ((URL) -> Void)?

    var body: some View {
        List {
            if let message = store.libraryError {
                Text(message).foregroundStyle(.orange)
            }
            if case .finalizing = store.state {
                Label(String(localized: "영상을 저장하는 중"), systemImage: "clock")
            }
            if store.recordings.isEmpty {
                ContentUnavailableView(String(localized: "저장된 방송이 없어요"), systemImage: "film.stack",
                    description: Text(String(localized: "방송이 끝나면 원본 영상을 여기에서 확인할 수 있어요.")))
            }
            ForEach(store.recordings) { recording in
                NavigationLink {
                    BroadcastRecordingPlayerView(url: store.fileURL(for: recording), onExport: onExport)
                } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(recording.createdAt, format: .dateTime.year().month().day().hour().minute())
                            .font(.headline)
                        Text(duration(recording.duration) + " · " + ByteCountFormatter.string(fromByteCount: recording.bytes, countStyle: .file))
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("broadcast-recording-" + recording.id.uuidString)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) { recordingToDelete = recording } label: {
                        Label(String(localized: "삭제"), systemImage: "trash")
                    }
                }
                .contextMenu {
                    Button(role: .destructive) { recordingToDelete = recording } label: {
                        Label(String(localized: "삭제"), systemImage: "trash")
                    }
                }
            }
        }
        .navigationTitle(String(localized: "저장된 방송"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(String(localized: "닫기")) { dismiss() }
            }
        }
        .alert(String(localized: "저장된 방송을 삭제할까요?"),
            isPresented: Binding(get: { recordingToDelete != nil }, set: { if !$0 { recordingToDelete = nil } }),
            presenting: recordingToDelete) { recording in
            Button(String(localized: "삭제"), role: .destructive) {
                do { try store.delete(recording) }
                catch { deletionError = error.localizedDescription }
                recordingToDelete = nil
            }
            Button(String(localized: "취소"), role: .cancel) { recordingToDelete = nil }
        } message: { recording in
            Text(String(localized: "앱에 저장된 영상 \(ByteCountFormatter.string(fromByteCount: recording.bytes, countStyle: .file))를 삭제합니다. 복구할 수 없으며, 사진이나 파일 앱으로 내보낸 사본은 유지됩니다."))
        }
        .alert(String(localized: "영상을 삭제하지 못했어요"),
            isPresented: Binding(get: { deletionError != nil }, set: { if !$0 { deletionError = nil } })) {
            Button(String(localized: "확인")) { deletionError = nil }
        } message: { Text(deletionError ?? "") }
        .task { await store.reloadLibrary() }
        .refreshable { await store.reloadLibrary() }
    }

    private func duration(_ duration: TimeInterval) -> String {
        let seconds = Int(max(0, duration))
        return String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}

struct BroadcastRecordingPlayerView: View {
    let url: URL
    var onExport: ((URL) -> Void)?
    @State private var player: AVPlayer?
    @State private var isShowingExport = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let player {
                VideoPlayer(player: player)
                    .accessibilityIdentifier("broadcast-recording-player")
            } else {
                ContentUnavailableView(String(localized: "저장된 파일을 찾을 수 없습니다."), systemImage: "exclamationmark.triangle")
            }
        }
        .navigationTitle(String(localized: "영상 재생"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(String(localized: "닫기")) { dismiss() }
            }
            ToolbarItem(placement: .primaryAction) {
                Button(String(localized: "내보내기")) {
                    player?.pause()
                    if let onExport { onExport(url) }
                    else { isShowingExport = true }
                }
                .disabled(player == nil)
                .accessibilityIdentifier("broadcast-recording-player-export")
            }
        }
        .sheet(isPresented: $isShowingExport) { BroadcastRecordingExportSheet(sourceURL: url) }
        .task(id: url) {
            guard url.isFileURL, FileManager.default.fileExists(atPath: url.path) else { player = nil; return }
            player = AVPlayer(url: url)
            player?.play()
        }
        .onDisappear { player?.pause(); player = nil }
    }
}
