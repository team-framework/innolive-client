import SwiftUI
import UIKit

struct StudioLiveStatusHeader: View {
    @ObservedObject var youtube: YouTubeIntegration
    let onEnd: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 10) {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    HStack(spacing: 8) {
                        Circle().fill(.white.opacity(0.7)).frame(width: 6, height: 6)
                        Text(duration(at: context.date))
                            .font(.title3.weight(.medium)).monospacedDigit()
                            .lineLimit(1).minimumScaleFactor(0.7)
                    }
                    .padding(.horizontal, 12).frame(height: 40)
                    .background(youtube.isYouTubeBroadcastPaused ? Color.orange : Color.red,
                                in: RoundedRectangle(cornerRadius: 6))
                    .accessibilityLabel(String(localized: "방송 중") + " " + duration(at: context.date))
                }
                .fixedSize(horizontal: true, vertical: false)
                Spacer(minLength: 0)
                Button(action: onEnd) {
                    Text(String(localized: "종료"))
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18).frame(minHeight: 44)
                        .background(.black.opacity(0.25), in: RoundedRectangle(cornerRadius: 6))
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(.white.opacity(0.85), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "전체 방송 종료", table: "Simulcast"))
                .accessibilityIdentifier("studio-end-broadcast")
            }
            StudioTransmissionMetrics(uplink: youtube.videoUplink, isDirect: youtube.isDirectPublishSession)
            if let recovery = youtube.videoRecoveryStatus {
                Text(recovery.title).font(.caption).padding(6).background(.black.opacity(0.65), in: Capsule())
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
    @State private var wasBatteryMonitoringEnabled = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 5)) { _ in
            HStack(spacing: 16) {
                Label(isDirect ? "– fps" : uplink.uplinkQuality.framesPerSecond.map { "\($0) fps" } ?? "– fps",
                      systemImage: "waveform.path")
                if !isDirect, let edge = uplink.uplinkQuality.shortEdge {
                    Label { Text(verbatim: "\(edge)p") } icon: { Image(systemName: "antenna.radiowaves.left.and.right") }
                }
                Spacer(minLength: 0)
                if UIDevice.current.batteryLevel >= 0 {
                    Label("\(Int((UIDevice.current.batteryLevel * 100).rounded()))%", systemImage: "battery.100percent")
                }
            }
            .font(.caption.monospacedDigit())
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 8))
        }
        .onAppear {
            wasBatteryMonitoringEnabled = UIDevice.current.isBatteryMonitoringEnabled
            UIDevice.current.isBatteryMonitoringEnabled = true
        }
        .onDisappear {
            UIDevice.current.isBatteryMonitoringEnabled = wasBatteryMonitoringEnabled
        }
    }
}
