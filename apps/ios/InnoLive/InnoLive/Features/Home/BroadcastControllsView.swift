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
    let isStartingServerConnection: Bool
    let onRetryConnection: () -> Void

    @State private var isShowingBroadcastSettings = false
    @State private var isShowingBroadcastActions = false

    var body: some View {
        VStack(spacing: 10) {
            if !isShowingBroadcastSettings, let feedback {
                BroadcastFeedbackBanner(feedback: feedback, youtube: youtube) {
                    youtube.dismissError()
                }
            }

            GlassEffectContainer {
                VStack(spacing: 8) {
                    HStack(spacing: 10) {
                        NavigationLink {
                            SettingsView(authentication: authentication, youtube: youtube)
                        } label: {
                            SettingsControlLabel()
                        }
                        .buttonStyle(.glass)
                        .buttonBorderShape(.circle)
                        .accessibilityLabel(String(localized: "설정"))

                        Button(action: performPrimaryAction) {
                            if isBroadcasting {
                                YouTubeBroadcastControlLabel(
                                    youtube: youtube,
                                    isLoading: youtube.isChangingStreamState
                                )
                            } else {
                                ServerConnectionControlLabel(isLoading: isPreparingConnection)
                            }
                        }
                        .buttonStyle(.glassProminent)
                        .buttonBorderShape(.capsule)
                        .tint(primaryButtonTint)
                        .frame(maxWidth: .infinity)
                        .disabled(isPrimaryActionDisabled)
                        .layoutPriority(1)
                        .accessibilityHint(primaryActionHint)
                        .contextMenu {
                            if youtube.broadcastPhase == "prepared" {
                                Button(role: .destructive, action: cancelYouTubePreparation) {
                                    Label(String(localized: "방송 준비 취소"), systemImage: "xmark.circle.fill")
                                }
                            }
                        }

                        Button(action: toggleAnonymization) {
                            AnonymizationControlLabel(
                                isLoading: youtube.isTogglingAnonymization
                            )
                        }
                        .buttonStyle(.glass)
                        .buttonBorderShape(.circle)
                        .tint(youtube.isAnonymizationEnabled ? .purple : nil)
                        .disabled(!isBroadcasting || youtube.isTogglingAnonymization)
                        .accessibilityLabel(
                            youtube.isAnonymizationEnabled ? String(localized: "비식별화 켜짐") : String(localized: "비식별화 꺼짐")
                        )
                        .accessibilityHint(String(localized: "서버 영상 연결을 유지한 채 AI 비식별화 처리를 켜거나 끕니다."))
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingBroadcastSettings) {
            NavigationStack {
                BroadcastSettingsView(
                    authentication: authentication,
                    youtube: youtube,
                    onPrepare: prepareYouTubeStream
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "닫기")) {
                            isShowingBroadcastSettings = false
                        }
                        .disabled(youtube.isChangingStreamState)
                    }
                }
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .interactiveDismissDisabled(youtube.isChangingStreamState)
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
            Button(String(localized: "방송 종료"), role: .destructive, action: stopYouTubeStream)
            Button(String(localized: "취소"), role: .cancel) { }
        } message: {
            Text(String(localized: "YouTube에 송출되는 화면만 일시 중단되고, 서버와의 연결은 끊기지 않아요."))
        }
    }

    private var isPreparingConnection: Bool {
        isStartingServerConnection
            || previewTransition != .none
            || youtube.isPreparingSession
            || youtube.isConnectingVideo
            || youtube.isRecoveringVideoFailure
            || youtube.videoUplink.isConnecting
    }

    private var isYouTubeStreaming: Bool {
        youtube.hasStartedYouTubeBroadcast
    }

    private var isPrimaryActionDisabled: Bool {
        isPreparingConnection
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
            return String(localized: "카메라와 마이크를 서버에 다시 연결합니다.")
        }
        if isYouTubeStreaming {
            return String(localized: "현재 방송 시간을 표시합니다. 누르면 방송 종료를 확인합니다.")
        }
        if youtube.broadcastPhase == "prepared" {
            return String(localized: "준비된 YouTube 방송을 시청자에게 공개합니다.")
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

        guard isBroadcasting else {
            onRetryConnection()
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

    private func prepareYouTubeStream() {
        Task {
            await youtube.prepareYouTubeStream(accessToken: authentication.currentAccessToken())
            if youtube.broadcastPhase == "prepared" {
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

    private func cancelYouTubePreparation() {
        Task {
            await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken())
        }
    }
}
