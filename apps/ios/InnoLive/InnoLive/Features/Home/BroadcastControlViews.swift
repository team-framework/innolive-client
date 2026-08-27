import SwiftUI
import UIKit

struct BroadcastControlLabel: View {
    let title: String
    let systemImage: String?
    var isLoading: Bool

    init(title: String, systemImage: String? = nil, isLoading: Bool = false) {
        self.title = title
        self.systemImage = systemImage
        self.isLoading = isLoading
    }

    var body: some View {
        VStack(spacing: 4) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.body.weight(.semibold))
            }
            if !isLoading {
                Text(title)
                    .font(systemImage == nil ? .caption.weight(.semibold) : .caption2.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 52)
        .contentShape(Rectangle())
    }
}

struct SettingsControlLabel: View {
    var body: some View {
        Image(systemName: "gearshape.fill")
            .font(.body.weight(.semibold))
            .frame(width: 52, height: 52)
            .contentShape(Rectangle())
    }
}

struct YouTubeBroadcastControlLabel: View {
    @ObservedObject var youtube: YouTubeIntegration
    let isLoading: Bool

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            BroadcastControlLabel(
                title: buttonTitle(at: context.date),
                systemImage: youtube.hasStartedYouTubeBroadcast ? "stop.fill" : "play.rectangle.fill",
                isLoading: isLoading
            )
        }
    }

    private func buttonTitle(at date: Date) -> String {
        if youtube.broadcastPhase == "prepared" { return "라이브 시작" }
        if youtube.isWaitingForYouTubeBroadcastStart { return "처리 중" }
        guard youtube.isYouTubeBroadcastActive else { return "방송 준비" }
        let duration = formattedDuration(since: youtube.streamStartedAt, now: date)
        if youtube.isYouTubeBroadcastPaused {
            return "방송 종료"
        }
        if youtube.stream?.status == "reconnecting" {
            return "재연결 중 (\(duration))"
        }
        return "방송 중 (\(duration))"
    }

    private func formattedDuration(since startDate: Date?, now: Date) -> String {
        let elapsedSeconds = max(0, Int(now.timeIntervalSince(startDate ?? now)))
        let hours = elapsedSeconds / 3_600
        let minutes = (elapsedSeconds % 3_600) / 60
        let seconds = elapsedSeconds % 60
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%02d:%02d", minutes, seconds)
    }
}

struct YouTubePauseControlLabel: View {
    @ObservedObject var youtube: YouTubeIntegration
    let isLoading: Bool

    var body: some View {
        BroadcastControlLabel(
            title: buttonTitle,
            systemImage: youtube.isYouTubeBroadcastPaused ? "play.fill" : "pause.fill",
            isLoading: isLoading
        )
    }

    private var buttonTitle: String {
        switch youtube.stream?.status {
        case "paused_reconfiguring": return "중지 준비 중"
        case "paused_reconnecting": return "중지 재연결 중"
        case "paused": return "방송 재개"
        default: return "일시 중지"
        }
    }
}

struct BroadcastFeedback {
    let message: String
    let isError: Bool
}

struct BroadcastFeedbackBanner: View {
    let feedback: BroadcastFeedback
    @ObservedObject var youtube: YouTubeIntegration
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                if feedback.isError {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                } else {
                    ProgressView()
                        .controlSize(.small)
                }
                Text(feedback.message)
                    .font(.caption)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if feedback.isError {
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("오류 닫기")
                }
            }

            if let helpURL = youtube.helpURL {
                Link("YouTube 라이브 활성화 안내", destination: helpURL)
                    .font(.caption.weight(.semibold))
            } else if youtube.videoUplink.requiresMediaPermissionSettings,
                      let settingsURL = URL(string: UIApplication.openSettingsURLString) {
                Link("앱 설정 열기", destination: settingsURL)
                    .font(.caption.weight(.semibold))
            }
        }
        .padding(10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
