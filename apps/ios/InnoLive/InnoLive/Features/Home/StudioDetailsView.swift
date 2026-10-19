import SwiftUI

/// Studio destinations are presented above the persistent camera and broadcast session.
struct StudioDetailsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    var tutorial: BroadcastTutorialCoordinator? = nil
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var destination: Destination?

    private enum Destination: Equatable, Identifiable {
        case status, broadcast, camera, video, ai, recordings, usage, settings
        case liveBroadcast(BroadcastSettingsProvider)
        var id: String {
            switch self {
            case .status: "status"
            case .broadcast: "broadcast"
            case .camera: "camera"
            case .video: "video"
            case .ai: "ai"
            case .recordings: "recordings"
            case .usage: "usage"
            case .settings: "settings"
            case .liveBroadcast(let provider): "live-edit-" + provider.rawValue
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                section(String(localized: "송출 대상")) {
                    if !youtube.visibleBroadcastTargets.isEmpty || youtube.isYouTubeBroadcastActive {
                        BroadcastLiveStatusSection(youtube: youtube, uplink: youtube.videoUplink)
                            .environment(\.colorScheme, .dark)
                            .padding(.bottom, 4)
                    }
                    if youtube.session != nil || youtube.isYouTubeBroadcastActive || !youtube.visibleBroadcastTargets.isEmpty {
                        row(String(localized: "방송 상태 및 대상 관리"), image: "dot.radiowaves.left.and.right", destination: .status)
                    }
                    if youtube.liveEditingTargets.isEmpty {
                        row(String(localized: "방송 정보"), image: "pencil", destination: .broadcast)
                            .disabled(youtube.isBroadcastSettingsLocked)
                    } else {
                        ForEach(youtube.liveEditingTargets) { provider in
                            row("\(provider.title) · \(String(localized: "방송 정보 수정"))", image: "pencil", destination: .liveBroadcast(provider))
                                .disabled(!youtube.canEditLiveBroadcast(provider) || youtube.isSavingLiveSettings || youtube.isChangingBroadcastMode)
                        }
                    }
                }

                section(String(localized: "영상 및 오디오")) {
                    row(String(localized: "카메라 및 오디오 설정"), image: "camera.fill", destination: .camera)
                    row(String(localized: "영상 조절"), image: "slider.horizontal.3", destination: .video)
                }

                section(String(localized: "비식별화")) {
                    row(String(localized: "AI 및 얼굴 설정"), image: "faceid", destination: .ai)
                }

                section(String(localized: "모니터링 및 저장")) {
                    recordingNotice
                    row(String(localized: "저장된 방송"), image: "play.rectangle", destination: .recordings)
                        .disabled(youtube.isYouTubeBroadcastActive || youtube.lifecycle.isPresentingStart || youtube.isChangingStreamState)
                }

                section(String(localized: "이용 정보")) {
                    if let message = youtube.responseState.details.notices.last(where: { $0.planMessage != nil })?.planMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .accessibilityIdentifier("studio-plan-notice")
                    }
                    row(String(localized: "요금제 및 사용량"), image: "clock", destination: .usage)
                    row(String(localized: "앱 정보 및 계정"), image: "info.circle", destination: .settings)
                }
            }
            .padding(16)
            .frame(maxWidth: verticalSizeClass == .compact ? .infinity : 600)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("studio-details-scroll")
        .onChange(of: youtube.isYouTubeBroadcastActive) { _, active in
            if active, destination == .recordings { destination = nil }
        }
        .sheet(item: $destination) { destination in
            if destination == .video {
                // This existing editor owns its navigation stack and compact detent.
                BroadcastVideoControlsView(uplink: youtube.videoUplink, youtube: youtube,
                                           accessToken: { authentication.currentAccessToken() })
            } else {
                NavigationStack {
                    destinationView(destination)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button(String(localized: "닫기")) { self.destination = nil }
                                    .accessibilityIdentifier("studio-details-sheet-close")
                            }
                        }
                }
                .presentationDragIndicator(.visible)
            }
        }
    }

    private var recordingNotice: some View {
        StudioRecordingNotice(coordinator: youtube.recordingCoordinator)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            content()
        }
        .foregroundStyle(.white)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            if reduceTransparency {
                RoundedRectangle(cornerRadius: 20).fill(.black)
            } else {
                RoundedRectangle(cornerRadius: 20)
                    .fill(.regularMaterial)
                    .environment(\.colorScheme, .dark)
            }
        }
    }

    private func row(_ title: String, image: String, destination: Destination) -> some View {
        Button { self.destination = destination } label: {
            HStack(spacing: 12) {
                Image(systemName: image)
                    .font(.system(size: 20))
                    .frame(width: 24)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.65))
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("studio-details-" + destination.id)
    }

    @ViewBuilder
    private func destinationView(_ destination: Destination) -> some View {
        switch destination {
        case .status:
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    BroadcastSessionStatusView(authentication: authentication, youtube: youtube)
                    StudioTargetControls(authentication: authentication, youtube: youtube)
                }
                .padding(24)
            }
            .navigationTitle(String(localized: "방송 상태 및 대상 관리"))
            .navigationBarTitleDisplayMode(.inline)
        case .broadcast:
            BroadcastSettingsView(authentication: authentication, youtube: youtube, tutorial: tutorial)
        case .liveBroadcast(let provider):
            BroadcastSettingsView(authentication: authentication, youtube: youtube, liveProvider: provider)
        case .camera:
            CameraAudioSettingsView(youtube: youtube)
        case .ai:
            AISettingsView(authentication: authentication, youtube: youtube, tutorial: tutorial)
        case .recordings:
            BroadcastRecordingLibraryView(store: youtube.recordingStore)
        case .usage:
            PlanUsageView(authentication: authentication, youtube: youtube)
        case .settings:
            SettingsView(authentication: authentication, youtube: youtube, tutorial: tutorial)
        case .video:
            EmptyView()
        }
    }
}

private struct StudioRecordingNotice: View {
    @ObservedObject var coordinator: BroadcastRecordingCoordinator

    var body: some View {
        if let notice = coordinator.notice {
            Label(notice, systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .foregroundStyle(.orange)
                .accessibilityIdentifier("studio-recording-notice")
        }
    }
}

/// Target actions use the same integration policies as the common broadcast controls.
struct StudioTargetControls: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var pendingStop: BroadcastSettingsProvider?

    private var isLocked: Bool {
        youtube.isChangingStreamState || youtube.isChangingBroadcastMode
            || youtube.isSavingLiveSettings || youtube.isHandlingUpgradeOffer
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(BroadcastSettingsProvider.allCases) { provider in
                if youtube.targetPolicy(provider).isBroadcastActive {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(provider.title).font(.headline)
                        if youtube.targetPolicy(provider).canPauseBroadcast {
                            Button(String(localized: "일시 중지", table: "Simulcast")) {
                                Task {
                                    guard !isLocked, youtube.targetPolicy(provider).canPauseBroadcast else { return }
                                    await youtube.pauseYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider)
                                }
                            }
                            .accessibilityIdentifier("studio-target-pause-" + provider.rawValue)
                        }
                        if youtube.targetPolicy(provider).canResumeBroadcast {
                            Button(String(localized: "재개", table: "Simulcast")) {
                                Task {
                                    guard !isLocked, youtube.targetPolicy(provider).canResumeBroadcast else { return }
                                    await youtube.resumeYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider)
                                }
                            }
                            .accessibilityIdentifier("studio-target-resume-" + provider.rawValue)
                        }
                        Button(String(localized: "송출 종료", table: "Simulcast"), role: .destructive) {
                            pendingStop = provider
                        }
                        .accessibilityIdentifier("studio-target-stop-" + provider.rawValue)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isLocked)
                }
            }
        }
        .alert(String(localized: "방송을 종료할까요?"), isPresented: Binding(
            get: { pendingStop != nil },
            set: { if !$0 { pendingStop = nil } }
        ), presenting: pendingStop) { provider in
            Button(String(localized: "취소"), role: .cancel) { pendingStop = nil }
            Button("\(provider.title) · \(String(localized: "송출 종료", table: "Simulcast"))", role: .destructive) {
                pendingStop = nil
                Task {
                    guard !isLocked, youtube.targetPolicy(provider).isBroadcastActive else { return }
                    await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider)
                }
            }
            .disabled(isLocked || !youtube.targetPolicy(provider).isBroadcastActive)
        } message: { provider in
            Text(String(localized: "\(provider.title) 송출을 종료합니다."))
        }
    }
}
