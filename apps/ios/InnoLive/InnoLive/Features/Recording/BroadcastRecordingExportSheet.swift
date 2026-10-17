import SwiftUI

struct BroadcastRecordingExportSheet: View {
    let sourceURL: URL
    @Environment(\.dismiss) private var dismiss
    @State private var exporter = BroadcastRecordingExporter()
    @State private var package: BroadcastRecordingExportPackage?
    @State private var operation: Task<Void, Never>?
    @State private var isBusy = false
    @State private var errorMessage: String?
    @State private var photoSaved = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        prepare(split: false)
                    } label: {
                        Label(String(localized: "영상과 음성 · MP4", table: "BroadcastRecording"), systemImage: "film")
                    }
                    Button {
                        prepare(split: true)
                    } label: {
                        Label(String(localized: "영상·음성 분리 · MP4 + M4A", table: "BroadcastRecording"), systemImage: "square.stack")
                    }
                } header: {
                    Text(String(localized: "내보낼 형식", table: "BroadcastRecording"))
                }
                .disabled(isBusy)

                if isBusy {
                    Section {
                        ProgressView(String(localized: "파일을 준비하는 중", table: "BroadcastRecording"))
                        Button(String(localized: "취소", table: "BroadcastRecording")) { operation?.cancel() }
                    }
                }
                if let package, !isBusy {
                    Section {
                        ForEach(package.urls, id: \.self) { url in
                            Text(exportLabel(for: url, in: package)).font(.subheadline)
                        }
                        ShareLink(items: package.urls) {
                            Label(String(localized: "공유 또는 파일에 저장", table: "BroadcastRecording"), systemImage: "square.and.arrow.up")
                        }
                        .accessibilityIdentifier("broadcast-recording-share")
                        if package.urls.count == 1 {
                            Button {
                                saveToPhotos()
                            } label: {
                                Label(String(localized: "사진 앱에 저장", table: "BroadcastRecording"), systemImage: "photo.on.rectangle")
                            }
                            .accessibilityIdentifier("broadcast-recording-photos")
                        }
                    }
                }
                if photoSaved {
                    Section {
                        Label(String(localized: "사진 앱에 저장했어요", table: "BroadcastRecording"), systemImage: "checkmark.circle")
                            .accessibilityIdentifier("broadcast-recording-photos-saved")
                    }
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .accessibilityIdentifier("broadcast-recording-export-error")
                        Text(String(localized: "형식을 다시 선택해 재시도할 수 있습니다.", table: "BroadcastRecording"))
                            .font(.footnote)
                    }
                }
            }
            .navigationTitle(String(localized: "방송 영상 내보내기", table: "BroadcastRecording"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "닫기", table: "BroadcastRecording")) {
                        operation?.cancel()
                        dismiss()
                    }
                }
            }
            .onAppear { if package == nil && operation == nil { prepare(split: false) } }
            .onDisappear { operation?.cancel() }
        }
    }

    private func exportLabel(for url: URL, in package: BroadcastRecordingExportPackage) -> String {
        if url.pathExtension.lowercased() == "m4a" {
            return String(localized: "음성 · M4A", table: "BroadcastRecording")
        }
        if package.urls.count == 1 {
            return String(localized: "영상과 음성 · MP4", table: "BroadcastRecording")
        }
        return String(localized: "영상 · MP4", table: "BroadcastRecording")
    }

    private func prepare(split: Bool) {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        photoSaved = false
        operation = Task { @MainActor in
            defer { isBusy = false; operation = nil }
            do {
                let output: BroadcastRecordingExportPackage
                if split { output = try await exporter.split(sourceURL) }
                else { output = try await exporter.original(sourceURL) }
                if Task.isCancelled {
                    exporter.release(output)
                    return
                }
                // Keep previous shared exports alive: ShareLink does not expose completion.
                package = output
            } catch is CancellationError {
                // Source remains available and the next format selection retries the operation.
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func saveToPhotos() {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        photoSaved = false
        operation = Task { @MainActor in
            defer { isBusy = false; operation = nil }
            do {
                try await exporter.saveOriginalToPhotos(sourceURL)
                photoSaved = true
            } catch is CancellationError {
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
