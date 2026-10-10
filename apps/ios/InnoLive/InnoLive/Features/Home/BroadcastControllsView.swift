//
//  BroadcastControllsView.swift
//  InnoLive
//
//  Created by chaeyn on 7/26/26.
//

import SwiftUI

struct BroadcastControllsView: View {
    @Binding var isBroadcasting: Bool
    @Binding var previewTransition: BroadcastPreviewTransition
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    let onPrepareBroadcast: (BroadcastSettingsProvider) async -> Void
    let onCancelPreparation: () async -> Void
    var tutorial: BroadcastTutorialCoordinator?

    @State private var isShowingBroadcastSettings = false
    @State private var isShowingBroadcastActions = false

    var body: some View {
        VStack(spacing: 10) {
            BroadcastSessionStatusView(authentication: authentication, youtube: youtube)
            if !isShowingBroadcastSettings, let feedback {
                BroadcastFeedbackBanner(feedback: feedback, youtube: youtube) {
                    youtube.dismissError()
                }
            }

            InnoLiveGlassContainer {
                VStack(spacing: 8) {
                    HStack(spacing: 10) {
                        NavigationLink {
                            SettingsView(authentication: authentication, youtube: youtube, tutorial: tutorial)
                        } label: {
                            SettingsControlLabel()
                        }
                        .innoLiveGlassButtonStyle()
                        .buttonBorderShape(.circle)
                        .accessibilityLabel(String(localized: "설정"))

                        Button(action: performPrimaryAction) {
                            if isBroadcasting {
                                YouTubeBroadcastControlLabel(
                                    youtube: youtube,
                                    isLoading: youtube.isChangingStreamState
                                )
                            } else {
                                BroadcastPreparationControlLabel(
                                    status: youtube.preparationStatus,
                                    isLoading: isPreparingConnection
                                )
                            }
                        }
                        .innoLiveGlassButtonStyle(prominent: true)
                        .buttonBorderShape(.capsule)
                        .tint(primaryButtonTint)
                        .frame(maxWidth: .infinity)
                        .disabled(isPrimaryActionDisabled)
                        .layoutPriority(1)
                        .broadcastTutorialAnchor(.primaryButton)
                        .accessibilityHint(primaryActionHint)
                        .contextMenu {
                            if canCancelPreparation {
                                Button(role: .destructive, action: cancelPreparation) {
                                    Label(String(localized: "방송 준비 취소"), systemImage: "xmark.circle.fill")
                                }
                            }
                        }

                        Button(action: toggleAnonymization) {
                            AnonymizationControlLabel(
                                isLoading: youtube.isTogglingAnonymization
                            )
                        }
                        .innoLiveGlassButtonStyle()
                        .buttonBorderShape(.circle)
                        .tint(youtube.isAnonymizationEnabled ? .purple : nil)
                        .disabled(!isBroadcasting || youtube.isTogglingAnonymization)
                        .accessibilityLabel(
                            youtube.isAnonymizationEnabled ? String(localized: "비식별화 켜짐") : String(localized: "비식별화 꺼짐")
                        )
                        .accessibilityHint(String(localized: "서버 영상 연결을 유지한 채 AI 비식별화 처리를 켜거나 끕니다."))
                    }

                    if canCancelPreparation {
                        Button(role: .destructive, action: cancelPreparation) {
                            Text(youtube.preparationStatus?.phase == .cancelling
                                 ? String(localized: "준비 취소 중")
                                 : String(localized: "준비 취소"))
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, minHeight: 36)
                        }
                        .disabled(youtube.preparationStatus?.phase == .cancelling)
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingBroadcastSettings) {
            NavigationStack {
                BroadcastSettingsView(
                    authentication: authentication,
                    youtube: youtube,
                    onPrepare: prepareYouTubeStream,
                    onCancelPreparation: cancelPreparation,
                    onContinuePreparation: { isShowingBroadcastSettings = false }
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "닫기")) {
                            isShowingBroadcastSettings = false
                        }
                        .disabled(isPreparationSheetLocked)
                    }
                }
                .broadcastTutorialHost(tutorial, host: .settingsSheet)
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .interactiveDismissDisabled(isPreparationSheetLocked)
        }
        .confirmationDialog(
            String(localized: "방송 제어"),
            isPresented: $isShowingBroadcastActions,
            titleVisibility: .visible
        ) {
            Button(
                youtube.isYouTubeBroadcastPaused ? String(localized: "방송 재개") : String(localized: "방송 일시 중지"),
                action: toggleYouTubePause
            )
            .disabled(!youtube.canChangeYouTubePauseState)
            ForEach(BroadcastSettingsProvider.allCases) { provider in
                if youtube.targetPolicy(provider).isBroadcastActive {
                    if youtube.targetPolicy(provider).canPauseBroadcast {
                        Button("\(provider.title) · \(String(localized: "일시 중지", table: "Simulcast"))") {
                            Task { await youtube.pauseYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider) }
                        }
                    }
                    if youtube.targetPolicy(provider).canResumeBroadcast {
                        Button("\(provider.title) · \(String(localized: "재개", table: "Simulcast"))") {
                            Task { await youtube.resumeYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider) }
                        }
                    }
                    Button("\(provider.title) · \(String(localized: "송출 종료", table: "Simulcast"))", role: .destructive) {
                        Task { await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider) }
                    }
                }
            }
            Button(String(localized: "전체 방송 종료", table: "Simulcast"), role: .destructive, action: stopYouTubeStream)
            Button(String(localized: "취소"), role: .cancel) { }
        } message: {
            Text(String(localized: "방송 플랫폼에 송출되는 화면만 일시 중단되고, 서버와의 연결은 끊기지 않아요."))
        }
        .onChange(of: tutorialSnapshot, initial: true) { _, snapshot in
            tutorial?.update(snapshot)
        }
    }

    /// 안내 단계는 시트 표시 여부를 아는 이 화면에서 앱 상태를 모아 전달한다.
    private var tutorialSnapshot: BroadcastTutorialSnapshot {
        BroadcastTutorialSnapshot(
            isSettingsSheetPresented: isShowingBroadcastSettings,
            selectedAccountsConnected: youtube.selectedBroadcastProviders.allSatisfy(youtube.isSettingsAccountConnected),
            preparation: youtube.preparationStatus,
            phase: YouTubeBroadcastPhase(rawValue: youtube.broadcastPhase),
            hasStartedBroadcast: youtube.hasStartedYouTubeBroadcast
        )
    }

    private var isPreparingConnection: Bool {
        previewTransition != .none
            || youtube.isPreparingSession
            || youtube.isConnectingVideo
            || youtube.isRecoveringVideoFailure
            || youtube.videoUplink.isConnecting
            || youtube.preparationStatus?.isRunning == true
            || youtube.preparationStatus?.phase == .cancelling
    }

    private var isPreparationSheetLocked: Bool {
        youtube.isChangingStreamState
            || youtube.preparationStatus?.isRunning == true
            || youtube.preparationStatus?.phase == .cancelling
    }

    private var canCancelPreparation: Bool {
        if youtube.hasStartedYouTubeBroadcast { return false }
        if youtube.broadcastPhase == "live" || youtube.broadcastPhase == "going_live" { return false }
        if youtube.preparationStatus != nil { return true }
        return youtube.broadcastPhase == "prepared" || youtube.isVideoConnected
    }

    private var isYouTubeStreaming: Bool {
        youtube.hasStartedYouTubeBroadcast
    }

    private var isPrimaryActionDisabled: Bool {
        let preparationFailed = youtube.preparationStatus?.isFailed == true
        return (!preparationFailed && isPreparingConnection)
            || youtube.videoUplink.isSwitchingCamera
            || youtube.isChangingStreamState
            || previewTransition == .stopping
            || ["preparing", "going_live"].contains(youtube.broadcastPhase)
            || (isBroadcasting && !youtube.isFeatureAvailable)
    }

    private var primaryButtonTint: Color {
        if !isBroadcasting {
            return .blue
        }
        if youtube.isYouTubeBroadcastPaused {
            return .orange
        }
        if isYouTubeStreaming {
            return .red
        }
        if youtube.broadcastPhase == "prepared" {
            return .green
        }
        return .blue
    }

    private var primaryActionHint: String {
        if !isBroadcasting {
            if youtube.preparationStatus?.isFailed == true {
                return String(localized: "실패한 준비 단계부터 다시 시도합니다.")
            }
            return String(localized: "방송 설정을 확인하고 세션과 서버 연결을 시작합니다.")
        }
        if isYouTubeStreaming {
            return String(localized: "현재 방송 시간을 표시합니다. 누르면 방송 종료를 확인합니다.")
        }
        if youtube.broadcastPhase == "prepared" {
            return String(localized: "준비된 플랫폼의 방송을 시청자에게 공개합니다.", table: "Simulcast")
        }
        return String(localized: "방송 설정 시트를 엽니다.")
    }

    var feedback: BroadcastFeedback? {
        // 연결/복구가 끝난 뒤 Integration이 확정한 오류만 표시한다.
        // 업링크의 임시 오류를 먼저 표시하면 복구 후 같은 배너가 다시 나타난다.
        guard !isPreparingConnection, let errorMessage = youtube.errorMessage else {
            return nil
        }
        return BroadcastFeedback(message: errorMessage, isError: true)
    }

    private func performPrimaryAction() {
        youtube.dismissError()

        if youtube.preparationStatus?.isFailed == true, let provider = youtube.lastPreparationProvider {
            prepareYouTubeStream(provider)
            return
        }

        guard isBroadcasting else {
            isShowingBroadcastSettings = true
            return
        }

        if isYouTubeStreaming {
            isShowingBroadcastActions = true
        } else if youtube.broadcastPhase == "prepared" {
            Task {
                await youtube.goLiveYouTubeStream(accessToken: authentication.currentAccessToken())
            }
        } else {
            isShowingBroadcastSettings = true
        }
    }

    private func prepareYouTubeStream(_ provider: BroadcastSettingsProvider) {
        Task {
            await onPrepareBroadcast(provider)
            if youtube.preparationStatus?.isFailed != true, youtube.broadcastPhase == "prepared" {
                isShowingBroadcastSettings = false
            }
        }
    }

    private func stopYouTubeStream() {
        Task {
            await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken())
        }
    }

    private func toggleYouTubePause() {
        Task {
            if youtube.isYouTubeBroadcastPaused {
                await youtube.resumeYouTubeStream(accessToken: authentication.currentAccessToken())
            } else {
                await youtube.pauseYouTubeStream(accessToken: authentication.currentAccessToken())
            }
        }
    }

    private func toggleAnonymization() {
        Task {
            await youtube.toggleAnonymization(accessToken: authentication.currentAccessToken())
        }
    }

    private func cancelPreparation() {
        Task {
            await onCancelPreparation()
            if youtube.session == nil {
                isShowingBroadcastSettings = false
            }
        }
    }
}
