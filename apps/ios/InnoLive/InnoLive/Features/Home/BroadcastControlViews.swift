import SwiftUI
import UIKit

private enum BroadcastControlLayout {
    static let height: CGFloat = 52
}

struct SettingsControlLabel: View {
    var body: some View {
        Image(systemName: "gearshape.fill")
            .font(.body.weight(.semibold))
            .frame(width: BroadcastControlLayout.height, height: BroadcastControlLayout.height)
            .contentShape(Rectangle())
    }
}

struct AnonymizationControlLabel: View {
    let isLoading: Bool

    var body: some View {
        Group {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else {
                Image(systemName: "faceid")
                    .font(.body.weight(.semibold))
            }
        }
        .frame(width: BroadcastControlLayout.height, height: BroadcastControlLayout.height)
        .contentShape(Rectangle())
    }
}

struct YouTubeBroadcastControlLabel: View {
    @ObservedObject var youtube: YouTubeIntegration
    let isLoading: Bool

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            HStack(spacing: 8) {
                if isLoading {
                    ProgressView()
                        .controlSize(.small)
                } else if youtube.hasStartedYouTubeBroadcast {
                    Circle()
                        .fill(.red)
                        .frame(width: 9, height: 9)
                }

                Text(buttonTitle(at: context.date))
                    .font(.headline.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }
            .frame(maxWidth: .infinity)
            .frame(height: BroadcastControlLayout.height)
            .contentShape(Rectangle())
        }
    }

    private func buttonTitle(at date: Date) -> String {
        if youtube.broadcastPhase == "prepared" { return String(localized: "방송 시작") }
        if youtube.broadcastPhase == "preparing" { return String(localized: "방송 준비 중") }
        if youtube.broadcastPhase == "going_live" { return String(localized: "방송 시작 중") }
        guard youtube.isYouTubeBroadcastActive else { return String(localized: "방송 준비") }
        let duration = formattedDuration(since: youtube.streamStartedAt, now: date)
        if youtube.isYouTubeBroadcastPaused {
            return String(localized: "방송 일시 중지 (\(duration))")
        }
        if youtube.stream?.status == "reconnecting" {
            return String(localized: "재연결 중 (\(duration))")
        }
        return String(localized: "방송 중 (\(duration))")
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

struct ServerConnectionControlLabel: View {
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 8) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else {
                Image(systemName: "arrow.clockwise")
                    .font(.body.weight(.semibold))
            }
            Text(isLoading ? String(localized: "서버 연결 중") : String(localized: "연결 재시도"))
                .font(.headline.weight(.bold))
        }
        .frame(maxWidth: .infinity)
        .frame(height: BroadcastControlLayout.height)
        .contentShape(Rectangle())
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
                    .accessibilityLabel(String(localized: "오류 닫기"))
                }
            }

            if let helpURL = youtube.helpURL {
                Link(String(localized: "YouTube 라이브 활성화 안내"), destination: helpURL)
                    .font(.caption.weight(.semibold))
            } else if youtube.videoUplink.requiresMediaPermissionSettings,
                      let settingsURL = URL(string: UIApplication.openSettingsURLString) {
                Link(String(localized: "앱 설정 열기"), destination: settingsURL)
                    .font(.caption.weight(.semibold))
            }
        }
        .padding(10)
        .glassEffect(.regular, in: .rect(cornerRadius: 14))
    }
}
