import SwiftUI
import UIKit

private enum BroadcastControlLayout {
    static let height: CGFloat = 44
}

struct SettingsControlLabel: View {
    var body: some View {
        Image(systemName: "gearshape.fill")
            .font(.system(size: 16, weight: .semibold))
            .frame(width: BroadcastControlLayout.height, height: BroadcastControlLayout.height)
            .contentShape(Rectangle())
    }
}

struct AnonymizationControlLabel: View {
    let isLoading: Bool
    var isEnabled = false

    var body: some View {
        VStack(spacing: 3) {
            if isLoading {
                ProgressView().controlSize(.small).tint(.white)
            } else {
                Image(systemName: "faceid")
                    .font(.system(size: 16, weight: .semibold))
                    .overlay(alignment: .bottomTrailing) {
                        if isEnabled {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 9, weight: .bold))
                                .offset(x: 5, y: 3)
                        }
                    }
            }
            Text(isEnabled ? String(localized: "가림 켜짐") : String(localized: "가림 꺼짐"))
                .font(.caption2.weight(.medium))
                .lineLimit(1)
        }
        .frame(minWidth: 56, minHeight: BroadcastControlLayout.height)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
    }
}

struct StudioWarningLabel: View {
    let title: String
    let systemImage: String

    var body: some View {
        Label {
            Text(title).foregroundStyle(.white)
        } icon: {
            Image(systemName: systemImage).foregroundStyle(.orange)
        }
            .font(.caption)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .innoLiveGlassBackground(cornerRadius: 12, overVideo: true)
            .environment(\.colorScheme, .dark)
    }
}

struct YouTubeBroadcastControlLabel: View {
    @ObservedObject var youtube: YouTubeIntegration
    let isLoading: Bool
    var isCompact = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

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

                VStack(spacing: 2) {
                    if isCompact {
                        Text(compactStatus)
                            .font(.caption.weight(.semibold))
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(formattedDuration(since: youtube.streamStartedAt, now: context.date))
                            .font(.system(size: 13, weight: .semibold))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                            .layoutPriority(1)
                    } else {
                        Text(buttonTitle(at: context.date))
                            .font(.system(size: 13, weight: .semibold))
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .minimumScaleFactor(0.72)
                    }
                    if youtube.isYouTubeBroadcastActive && !isCompact {
                        Text(String(localized: "남은 시간 \(PlanTimeText.remaining(youtube.broadcastRemainingTime))"))
                            .font(.caption2)
                        if youtube.isRemainingTimeStale {
                            Text(String(localized: "마지막 조회값 · 재시도 중"))
                                .font(.caption2).foregroundStyle(.orange)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .frame(minHeight: BroadcastControlLayout.height)
            .contentShape(Rectangle())
        }
    }

    private var compactStatus: String {
        if let recovery = youtube.videoRecoveryStatus { return recovery.title }
        if youtube.isYouTubeBroadcastPaused { return String(localized: "일시 중지", table: "Simulcast") }
        if youtube.stream?.status == "reconnecting" { return String(localized: "재연결 중") }
        return String(localized: "방송 중")
    }

    private func buttonTitle(at date: Date) -> String {
        if let recovery = youtube.videoRecoveryStatus {
            return recovery.buttonTitle
        }
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
        return String(localized: "방송 중 · 경과 \(duration)")
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

struct BroadcastPreparationControlLabel: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let status: BroadcastPreparationStatus?
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 8) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            }
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: BroadcastControlLayout.height)
        .contentShape(Rectangle())
    }

    private var title: String {
        guard let status else { return String(localized: "방송 준비") }
        if status.isFailed { return String(localized: "다시 시도") }
        return status.phase.title
    }
}

struct BroadcastSessionStatusView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var isShowingUpgradeOffer = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            #if DEBUG
            if youtube.session != nil {
                DirectPublishDebugBadge(youtube: youtube)
            }
            #endif
            if youtube.isDirectPublishSession, youtube.isDirectMicrophoneUnavailable, !youtube.directPublishStates.isEmpty {
                Text(String(localized: "마이크를 열지 못해 음성 없이 송출하고 있습니다. 마이크 권한과 연결을 확인해 주세요.", table: "DirectPublish"))
                    .foregroundStyle(.orange)
            }
            if youtube.upgradeOffer != nil {
                Button { isShowingUpgradeOffer = true } label: {
                    Label(String(localized: "방송 업그레이드 제안", table: "UpgradeOffer"), systemImage: "arrow.up.circle")
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("upgrade-offer-entry")
            }
            if !youtube.visibleBroadcastTargets.isEmpty || youtube.isYouTubeBroadcastActive {
                BroadcastLiveStatusSection(youtube: youtube, uplink: youtube.videoUplink)
                    .broadcastTutorialAnchor(.liveStatus)
            }
            if youtube.hasStartedYouTubeBroadcast {
                NavigationLink {
                    BroadcastModeView(authentication: authentication, youtube: youtube)
                } label: {
                    Label(String(localized: "방송 방식 변경", table: "BroadcastMode"), systemImage: "arrow.triangle.2.circlepath")
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .disabled(!youtube.canChangeBroadcastMode || youtube.isSavingLiveSettings)
                .accessibilityIdentifier("broadcast-mode-entry")
            }
            ForEach(youtube.liveEditingTargets) { provider in
                NavigationLink {
                    BroadcastSettingsView(authentication: authentication, youtube: youtube, liveProvider: provider)
                } label: {
                    Label("\(provider.title) · \(String(localized: "방송 정보 수정"))", systemImage: "pencil")
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .disabled(!youtube.canEditLiveBroadcast(provider) || youtube.isSavingLiveSettings || youtube.isChangingBroadcastMode)
                .accessibilityIdentifier("live-edit-" + provider.rawValue)
            }
            if youtube.responseState.details.resolutionSwitch?.status == "switching" {
                Text(String(localized: "방송 화질을 전환하고 있습니다."))
            }
            if !(youtube.responseState.details.resolutionSwitch?.failedTargets ?? []).isEmpty {
                Text(String(localized: "일부 방송 플랫폼의 화질 전환을 완료하지 못했습니다."))
            }
            ForEach(youtube.liveStartFailures, id: \.provider) { failure in
                let title = BroadcastSettingsProvider(rawValue: failure.provider)?.title ?? failure.provider
                Text("\(title) · \(String(localized: "라이브 시작 실패", table: "Simulcast"))").foregroundStyle(.orange)
            }
            if youtube.hasStartedYouTubeBroadcast,
               youtube.visibleBroadcastTargets.contains(where: { $0.stream.broadcastPhaseValue == .prepared && $0.stream.statusValue != .stopped }) {
                Button(String(localized: "준비된 대상 다시 시작", table: "Simulcast")) {
                    Task { await youtube.startBroadcastCountdown(accessToken: authentication.currentAccessToken()) }
                }
                .disabled(youtube.isChangingStreamState)
            }
            if !youtube.responseState.details.warnings.isEmpty {
                Text(String(localized: "방송은 준비되었지만 일부 설정을 적용하지 못했습니다."))
            }
        }
        .font(.caption)
        .frame(maxWidth: .infinity, alignment: .leading)
        .sheet(isPresented: $isShowingUpgradeOffer) {
            NavigationStack { UpgradeOfferView(authentication: authentication, youtube: youtube) }
        }
        .onChange(of: youtube.upgradeOffer) { _, offer in
            if offer == nil { isShowingUpgradeOffer = false }
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
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
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
        .innoLiveGlassBackground(cornerRadius: 14, overVideo: true)
        .environment(\.colorScheme, .dark)
    }
}

struct StudioBroadcastSummaryLabel: View {
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject var editor: BroadcastSettingsEditor
    @AppStorage("selectedResolution") private var selectedResolution = CameraQualityPreset.defaultValue.rawValue
    @AppStorage(AIProcessingMode.storageKey) private var selectedAIProcessing = AIProcessingMode.server.rawValue

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(BroadcastSettingsProvider.allCases.filter(youtube.selectedBroadcastProviders.contains)) { provider in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 4) {
                            if !youtube.isSettingsAccountConnected(provider) {
                                Image(systemName: "exclamationmark.circle.fill")
                                    .accessibilityHidden(true)
                            }
                            Text(channelTitle(for: provider))
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(youtube.isSettingsAccountConnected(provider) ? Color.white : Color.orange)
                        Text(broadcastTitle(for: provider))
                            .font(.system(size: 12, weight: .semibold)).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right").font(.caption)
                }
            }
            Text(qualitySummary + " · " + processingSummary)
                .font(.caption).foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .innoLiveGlassBackground(cornerRadius: 18, overVideo: true)
        .contentShape(RoundedRectangle(cornerRadius: 18))
        .environment(\.colorScheme, .dark)
    }

    private func broadcastTitle(for provider: BroadcastSettingsProvider) -> String {
        let title = (provider == .youtube ? editor.youtube.title : editor.chzzk.title)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? String(localized: "방송 제목 설정") : title
    }

    private func channelTitle(for provider: BroadcastSettingsProvider) -> String {
        guard youtube.isSettingsAccountConnected(provider) else {
            return provider.title + " · " + String(localized: "계정 연결 필요")
        }
        let channel = provider == .youtube ? youtube.connection?.channel.title
            : youtube.streamingAccounts.first(where: { $0.provider == provider.rawValue })?.displayTitle
        return channel.map { provider.title + " · " + $0 } ?? provider.title
    }

    private var qualitySummary: String {
        if let resolution = youtube.broadcastResolution {
            return resolution == "fhd" ? "1080p" : resolution
        }
        return (CameraQualityPreset(rawValue: selectedResolution) ?? .defaultValue).rawValue
    }

    private var processingSummary: String {
        let mode = youtube.session == nil
            ? (AIProcessingMode(rawValue: selectedAIProcessing) ?? .server)
            : youtube.planProcessingMode
        return mode.title
    }
}
