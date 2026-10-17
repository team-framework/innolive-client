import AVKit
import SwiftUI

struct BroadcastRecordingPlayback: Identifiable {
    let url: URL
    var id: URL { url }
}

struct BroadcastRecordingLibraryView: View {
    @ObservedObject var store: RecordingSessionStore
    @Environment(\.dismiss) private var dismiss
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
            }
        }
        .navigationTitle(String(localized: "저장된 방송"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(String(localized: "닫기")) { dismiss() }
            }
        }
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
            if let onExport {
                ToolbarItem(placement: .primaryAction) {
                    Button(String(localized: "내보내기")) { onExport(url) }
                        .disabled(player == nil)
                }
            }
        }
        .task(id: url) {
            guard url.isFileURL, FileManager.default.fileExists(atPath: url.path) else { player = nil; return }
            player = AVPlayer(url: url)
            player?.play()
        }
        .onDisappear { player?.pause(); player = nil }
    }
}
