import SwiftUI

/// 방송 중 플랫폼별 송출 상태와 앱의 업로드 품질을 한곳에 보여 준다.
struct BroadcastLiveStatusPanel: View {
    let targets: [BroadcastTargetState]
    var broadcastResolution: String?
    /// nil이면 업로드 품질을 숨긴다. 방송이 끝난 뒤에는 의미가 없기 때문이다.
    var uplinkQuality: BroadcastUplinkQuality?
    var remainingTime: BroadcastRemainingTime?
    @ScaledMetric(relativeTo: .caption) private var dotSize: CGFloat = 7

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(targets) { target in
                targetRow(target)
            }

            if !detailChips.isEmpty {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 6) { chips }
                    VStack(alignment: .leading, spacing: 4) { chips }
                }
            }

            if let limitation = uplinkQuality?.limitation {
                Label(Self.warningText(limitation), systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .font(.caption)
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .innoLiveGlassBackground(cornerRadius: 14)
        .onChange(of: uplinkQuality?.limitation) { _, limitation in
            guard let limitation else { return }
            AccessibilityNotification.Announcement(Self.warningText(limitation)).post()
        }
    }

    private func targetRow(_ target: BroadcastTargetState) -> some View {
        let policy = YouTubeBroadcastStatePolicy(stream: target.stream, isChangingStreamState: false)
        let tone = policy.targetTone
        return HStack(spacing: 8) {
            Circle()
                .fill(Self.dotColor(tone))
                .frame(width: dotSize, height: dotSize)
            Text(BroadcastSettingsProvider(rawValue: target.provider)?.title ?? target.title)
                .fontWeight(.semibold)
            Spacer(minLength: 8)
            Text(policy.targetLabel)
                .foregroundStyle(tone == .attention ? Color.orange : Color.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var chips: some View {
        ForEach(detailChips) { chip in
            Label {
                Text(chip.text)
            } icon: {
                if let systemImage = chip.systemImage {
                    Image(systemName: systemImage)
                }
            }
            .labelStyle(BroadcastLiveStatusChipLabelStyle())
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(.fill.tertiary, in: Capsule())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(chip.accessibilityLabel)
        }
    }

    private var detailChips: [BroadcastLiveStatusChip] {
        var chips: [BroadcastLiveStatusChip] = []
        if let broadcastResolution {
            // 업로드 화질("720p")과 같은 표기로 맞춘다.
            let resolution = broadcastResolution.lowercased()
            chips.append(.init(
                id: "resolution",
                text: resolution,
                systemImage: "tv",
                accessibilityLabel: String(localized: "방송 화질 \(resolution)", table: "BroadcastGuide")
            ))
        }
        if let uplinkQuality {
            let text = uplinkQuality.summary ?? String(localized: "업로드 확인 중", table: "BroadcastGuide")
            chips.append(.init(
                id: "uplink",
                text: text,
                systemImage: "arrow.up",
                accessibilityLabel: String(localized: "업로드 \(text)", table: "BroadcastGuide")
            ))
        }
        switch remainingTime {
        case .seconds(let seconds):
            let text = String(localized: "남은 방송 시간 \(max(0, seconds) / 60)분")
            chips.append(.init(id: "remaining", text: text, systemImage: "clock", accessibilityLabel: text))
        case .unlimitedOrInactive:
            let text = String(localized: "방송 시간 제한 없음")
            chips.append(.init(id: "remaining", text: text, systemImage: "clock", accessibilityLabel: text))
        case .missing, nil:
            break
        }
        return chips
    }

    /// 동시 송출 이전 응답처럼 targets가 없으면 단일 송출 상태를 YouTube 한 줄로 보여 준다.
    static func rows(
        targets: [BroadcastTargetState],
        fallbackStream: YouTubeStreamState?,
        isBroadcastActive: Bool
    ) -> [BroadcastTargetState] {
        if !targets.isEmpty { return targets }
        guard isBroadcastActive, let fallbackStream else { return [] }
        return [BroadcastTargetState(provider: BroadcastSettingsProvider.youtube.rawValue, stream: fallbackStream)]
    }

    private static func warningText(_ limitation: BroadcastUplinkLimitation) -> String {
        switch limitation {
        case .network:
            return String(localized: "인터넷이 느려 화질을 잠시 낮췄어요. 와이파이를 쓰면 더 안정적이에요.", table: "BroadcastGuide")
        case .device:
            return String(localized: "기기가 바빠 화질을 잠시 낮췄어요. 다른 앱을 닫으면 나아질 수 있어요.", table: "BroadcastGuide")
        }
    }

    private static func dotColor(_ tone: BroadcastTargetTone) -> Color {
        switch tone {
        case .live: return .red
        case .ready: return .green
        case .attention: return .orange
        case .progress, .ended: return .secondary
        }
    }
}

private struct BroadcastLiveStatusChip: Identifiable {
    let id: String
    let text: String
    let systemImage: String?
    let accessibilityLabel: String
}

private struct BroadcastLiveStatusChipLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 3) {
            configuration.icon
            configuration.title
        }
    }
}

/// 업로드 품질은 영상 업링크가 따로 발행하므로 두 모델을 함께 관찰한다.
struct BroadcastLiveStatusSection: View {
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject var uplink: WebRTCVideoUplink

    var body: some View {
        BroadcastLiveStatusPanel(
            targets: BroadcastLiveStatusPanel.rows(
                targets: youtube.visibleBroadcastTargets,
                fallbackStream: youtube.stream,
                isBroadcastActive: youtube.isYouTubeBroadcastActive
            ),
            broadcastResolution: youtube.isYouTubeBroadcastActive ? youtube.broadcastResolution : nil,
            uplinkQuality: youtube.isYouTubeBroadcastActive ? uplink.uplinkQuality : nil,
            remainingTime: youtube.hasStartedYouTubeBroadcast ? youtube.broadcastRemainingTime : nil
        )
    }
}
