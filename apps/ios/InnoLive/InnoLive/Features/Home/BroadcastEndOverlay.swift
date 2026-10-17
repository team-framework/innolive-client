import AVFoundation
import SwiftUI
import UIKit

struct BroadcastEndOverlay: View {
    let phase: BroadcastLifecyclePhase
    let result: BroadcastLifecycleResult?
    var recording: BroadcastRecordingState = .idle
    // Step 4 does not have a writer. Only its actual integration enables save status.
    var isRecordingEnabled = false
    let onClose: () -> Void
    var onPlay: ((URL) -> Void)?
    var onSave: ((URL) -> Void)?

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var thumbnail: UIImage?

    private var visibleRecording: BroadcastRecordingState { isRecordingEnabled ? recording : .idle }
    private var savedURL: URL? {
        guard case .saved(let url) = visibleRecording, url.isFileURL,
              FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }
    private var isEnding: Bool { phase == .ending }
    private var isFinalizing: Bool { visibleRecording == .finalizing }

    var body: some View {
        ZStack {
            Color.black.opacity(reduceTransparency || contrast == .increased ? 0.94 : 0.76)
                .ignoresSafeArea()
                .accessibilityHidden(true)
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 24) {
                        if isEnding {
                            ProgressView().controlSize(.large).tint(.white)
                                .accessibilityHidden(true)
                            Text(String(localized: "방송을 종료하는 중"))
                                .font(.title2.weight(.semibold))
                                .accessibilityAddTraits(.isHeader)
                                .accessibilityIdentifier("broadcast-ending-title")
                            Text(String(localized: "플랫폼의 종료 응답을 기다리고 있습니다."))
                                .font(.body)
                        } else {
                            resultContent
                        }
                    }
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(28)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity, minHeight: geometry.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityIdentifier(isEnding ? "broadcast-ending-overlay" : "broadcast-result-overlay")
        .task(id: savedURL) { await loadThumbnail() }
    }

    private var resultContent: some View {
        VStack(spacing: 24) {
            if let thumbnail {
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 230)
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                    .accessibilityLabel(String(localized: "저장된 방송 영상 미리보기"))
            } else {
                Image(systemName: result?.terminationWarning == nil ? "checkmark.circle" : "exclamationmark.circle")
                    .font(.largeTitle)
                    .accessibilityHidden(true)
            }
            VStack(spacing: 10) {
                Text(result?.terminationWarning == nil ? String(localized: "방송을 종료했어요") : String(localized: "기기 송출을 중단했어요"))
                    .font(.title2.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("broadcast-result-title")
                if let result {
                    Text(durationText(result.duration))
                        .font(.title.monospacedDigit())
                        .accessibilityLabel(String(localized: "방송 시간") + " " + durationText(result.duration))
                        .accessibilityIdentifier("broadcast-result-duration")
                }
                if let warning = result?.terminationWarning {
                    Text(warning).font(.body)
                        .accessibilityIdentifier("broadcast-result-termination-warning")
                }
                recordingStatus
            }
            .fixedSize(horizontal: false, vertical: true)

            InnoLiveGlassContainer {
                VStack(spacing: 12) {
                    if isRecordingEnabled, let onPlay {
                        Button {
                            if let savedURL { onPlay(savedURL) }
                        } label: {
                            Label(String(localized: "영상 재생"), systemImage: "play.fill")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .innoLiveGlassButtonStyle()
                        .disabled(savedURL == nil)
                        .accessibilityIdentifier("broadcast-result-play")
                    }
                    if isRecordingEnabled, let onSave {
                        Button {
                            if let savedURL { onSave(savedURL) }
                        } label: {
                            Label(String(localized: "내보내기"), systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .innoLiveGlassButtonStyle(prominent: true)
                        .disabled(savedURL == nil)
                        .accessibilityIdentifier("broadcast-result-export")
                    }
                    Button(action: onClose) {
                        Text(String(localized: "닫기"))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .innoLiveGlassButtonStyle()
                    .accessibilityIdentifier("broadcast-result-close")
                }
                .buttonBorderShape(.capsule)
            }
            .frame(maxWidth: 340)
        }
    }

    @ViewBuilder
    private var recordingStatus: some View {
        switch visibleRecording {
        case .finalizing:
            VStack(spacing: 10) {
                ProgressView().tint(.white).accessibilityHidden(true)
                Text(String(localized: "영상을 저장하는 중"))
            }
            .accessibilityIdentifier("broadcast-result-saving")
        case .saved:
            if savedURL != nil {
                Text(String(localized: "원본 영상을 앱에 저장했어요"))
                    .accessibilityIdentifier("broadcast-result-saved")
            } else {
                Text(String(localized: "저장된 파일을 찾을 수 없습니다."))
                    .accessibilityIdentifier("broadcast-result-file-missing")
            }
        case .failed(let message):
            VStack(spacing: 8) {
                Text(String(localized: "영상을 저장하지 못했어요"))
                    .font(.headline)
                Text(message).font(.body)
            }
            .accessibilityIdentifier("broadcast-result-save-failed")
        case .idle, .armed, .recording:
            EmptyView()
        }
    }

    private func durationText(_ duration: TimeInterval) -> String {
        let seconds = Int(max(0, duration))
        return seconds >= 3600
            ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    private func loadThumbnail() async {
        thumbnail = nil
        guard let url = savedURL else { return }
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 800, height: 800)
        do {
            let image = try await generator.image(at: .zero)
            guard !Task.isCancelled, savedURL == url else { return }
            thumbnail = UIImage(cgImage: image.image)
        } catch {
            // A thumbnail failure must not hide a verified recording's export actions.
        }
    }
}

/// Observe the nested lifecycle directly. Dismissal never cancels file finalization.
struct BroadcastEndPresentation: View {
    @ObservedObject var lifecycle: BroadcastLifecycle
    var isRecordingEnabled = false
    var onClose: () -> Void = {}
    var onPlay: ((URL) -> Void)?
    var onSave: ((URL) -> Void)?

    var body: some View {
        if lifecycle.isPresentingEnd {
            BroadcastEndOverlay(
                phase: lifecycle.phase, result: lifecycle.result,
                recording: lifecycle.recording, isRecordingEnabled: isRecordingEnabled,
                onClose: {
                    lifecycle.dismissResult()
                    onClose()
                },
                onPlay: onPlay, onSave: onSave
            )
        }
    }
}
