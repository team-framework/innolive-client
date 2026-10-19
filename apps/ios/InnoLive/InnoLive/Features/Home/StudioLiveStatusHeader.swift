import SwiftUI

struct StudioLiveStatusHeader: View {
    @ObservedObject var youtube: YouTubeIntegration
    let onEnd: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 12) {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    HStack(spacing: 6) {
                        Circle().fill(youtube.isYouTubeBroadcastPaused ? Color.orange : Color.red)
                            .frame(width: 5, height: 5)
                        Text(duration(at: context.date)).monospacedDigit()
                    }
                    .accessibilityLabel(String(localized: "방송 중") + " " + duration(at: context.date))
                }
                StudioTransmissionMetrics(uplink: youtube.videoUplink, isDirect: youtube.isDirectPublishSession)
                Spacer(minLength: 0)
                Button(action: onEnd) {
                    Text(String(localized: "종료"))
                        .padding(.horizontal, 8)
                        .frame(height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "전체 방송 종료", table: "Simulcast"))
                .accessibilityIdentifier("studio-end-broadcast")
            }
            .font(.system(size: 12, weight: .medium))
            .lineLimit(1)
            .padding(.horizontal, 10)
            .innoLiveGlassSurface(in: RoundedRectangle(cornerRadius: 8), overVideo: true)
            if let recovery = youtube.videoRecoveryStatus {
                Text(recovery.title).font(.caption).padding(6).innoLiveGlassSurface(in: Capsule(), overVideo: true)
            } else if youtube.isYouTubeBroadcastPaused {
                Text(String(localized: "일시 중지", table: "Simulcast")).font(.caption)
            }
        }
        .foregroundStyle(.white)
    }

    private func duration(at date: Date) -> String {
        let elapsed = max(0, Int(date.timeIntervalSince(youtube.streamStartedAt ?? date)))
        if elapsed >= 3600 { return String(format: "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60) }
        return String(format: "%02d:%02d", elapsed / 60, elapsed % 60)
    }
}

private struct StudioTransmissionMetrics: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    let isDirect: Bool
    var body: some View {
        Text(isDirect ? "– fps" : uplink.uplinkQuality.framesPerSecond.map { "\($0) fps" } ?? "– fps")
            .monospacedDigit()
            .foregroundStyle(.white.opacity(0.8))
    }
}
