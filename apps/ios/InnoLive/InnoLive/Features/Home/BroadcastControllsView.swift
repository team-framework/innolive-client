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
    @Environment(CameraManager.self) private var cameraManager
    @State private var localFeedbackMessage: String?

    var body: some View {
        GlassEffectContainer {
            VStack(spacing: 10) {
                HStack(spacing: 8) {
                    NavigationLink {
                        SettingsView(authentication: authentication, youtube: youtube)
                    } label: {
                        SettingsControlLabel()
                    }
                    .buttonStyle(.glass)
                    .accessibilityLabel("설정")

                    Button(action: toggleInnoLiveBroadcast) {
                        BroadcastControlLabel(
                            title: isBroadcasting ? "연결 종료" : "연결 시작",
                            isLoading: isPreparingConnection
                        )
                    }
                    .buttonStyle(.glassProminent)
                    .tint(isBroadcasting ? .green : .blue)
                    .disabled(isPreparingConnection || youtube.videoUplink.isSwitchingCamera)
                    .accessibilityHint("카메라와 마이크를 서버에 연결하거나 연결을 종료합니다.")

                    Button(action: toggleYouTubeStream) {
                        YouTubeBroadcastControlLabel(
                            youtube: youtube,
                            isLoading: youtube.isChangingStreamState
                        )
                    }
                    .buttonStyle(.glass)
                    .tint(isYouTubeStreaming ? .red : youtube.broadcastPhase == "prepared" ? .orange : nil)
                    .disabled(
                        youtube.isChangingStreamState
                            || previewTransition == .stopping
                            || !isBroadcasting
                            || !youtube.isFeatureAvailable
                            || !youtube.isConnected
                    )
                    .accessibilityHint(
                        youtube.broadcastPhase == "prepared"
                            ? "준비된 YouTube 방송을 시청자에게 공개합니다."
                            : youtube.isWaitingForYouTubeBroadcastStart
                            ? "서버가 YouTube 방송 상태를 변경하고 있습니다."
                            : youtube.isConnected
                            ? "YouTube 방송을 준비하거나 종료합니다."
                            : "설정에서 YouTube 계정을 먼저 연결해 주세요."
                    )

                    if youtube.broadcastPhase == "prepared" {
                        Button(action: cancelYouTubePreparation) {
                            BroadcastControlLabel(
                                title: "준비 취소",
                                systemImage: "xmark.circle.fill",
                                isLoading: youtube.isChangingStreamState
                            )
                        }
                        .buttonStyle(.glass)
                        .disabled(youtube.isChangingStreamState)
                        .accessibilityHint("준비한 YouTube 방송을 삭제하고 서버 연결은 유지합니다.")
                    }

                    if isBroadcasting {
                        Button(action: toggleAnonymization) {
                            // 아이콘은 다른 제어 버튼과 같이 눌렀을 때 일어날 동작을 나타낸다.
                            BroadcastControlLabel(
                                title: youtube.isAnonymizationEnabled ? "비식별화 OFF" : "비식별화 ON",
                                systemImage: youtube.isAnonymizationEnabled ? "eye.fill" : "eye.slash.fill",
                                isLoading: youtube.isTogglingAnonymization
                            )
                        }
                        .buttonStyle(.glass)
                        .tint(youtube.isAnonymizationEnabled ? .purple : nil)
                        .disabled(youtube.isTogglingAnonymization)
                        .accessibilityHint("방송을 유지한 채 AI 비식별화 처리를 켜거나 끕니다.")
                    }

                    if isYouTubeStreaming {
                        Button(action: toggleYouTubePause) {
                            YouTubePauseControlLabel(
                                youtube: youtube,
                                isLoading: youtube.isChangingStreamState
                            )
                        }
                        .buttonStyle(.glass)
                        .tint(youtube.isYouTubeBroadcastPaused ? .orange : .yellow)
                        .disabled(
                            youtube.isChangingStreamState
                                || !youtube.canChangeYouTubePauseState
                        )
                        .accessibilityHint(
                            youtube.isYouTubeBroadcastPaused
                                ? "YouTube 송출을 재개합니다. 카메라 비식별화 연결은 유지됩니다."
                                : "YouTube 송출을 일시 중지합니다. 카메라 비식별화 연결은 유지됩니다."
                        )
                    }
                }

                if let feedback {
                    BroadcastFeedbackBanner(feedback: feedback, youtube: youtube) {
                        localFeedbackMessage = nil
                        youtube.dismissError()
                    }
                }
            }
        }
    }

    private var isPreparingConnection: Bool {
        previewTransition != .none
            || youtube.isPreparingSession
            || youtube.isConnectingVideo
            || youtube.isRecoveringVideoFailure
            || youtube.videoUplink.isConnecting
    }

    private var isYouTubeStreaming: Bool {
        youtube.hasStartedYouTubeBroadcast
    }

    private var feedback: BroadcastFeedback? {
        if let errorMessage = youtube.errorMessage {
            return BroadcastFeedback(message: errorMessage, isError: true)
        }
        if youtube.videoUplink.state == .failed,
           let errorMessage = youtube.videoUplink.errorMessage {
            return BroadcastFeedback(message: errorMessage, isError: true)
        }
        if let localFeedbackMessage {
            return BroadcastFeedback(message: localFeedbackMessage, isError: true)
        }
        guard youtube.videoUplink.state == .preparing
                || youtube.videoUplink.state == .connecting else {
            return nil
        }
        return BroadcastFeedback(message: youtube.videoUplink.statusText, isError: false)
    }

    private func toggleInnoLiveBroadcast() {
        localFeedbackMessage = nil
        if isBroadcasting {
            guard !youtube.isYouTubeBroadcastActive else {
                localFeedbackMessage = "YouTube 송출 중에는 연결을 종료할 수 없습니다. 먼저 방송을 종료해 주세요."
                return
            }
            Task {
                previewTransition = .stopping
                defer { previewTransition = .none }
                await youtube.endBroadcast(accessToken: authentication.currentAccessToken())
                await cameraManager.startDefaultCamera()
                isBroadcasting = false
            }
            return
        }
        Task {
            if await youtube.prepareSession(accessToken: authentication.currentAccessToken()) {
                previewTransition = .starting
                defer { previewTransition = .none }
                await cameraManager.stopSession()
                if await youtube.connectVideo(
                    accessToken: authentication.currentAccessToken(),
                    preferredCameraID: cameraManager.currentCameraID,
                    preferredAudioID: UserDefaults.standard.string(forKey: "selectedAudioID"),
                    preferredVideoQuality: CameraQualityPreset(
                        rawValue: UserDefaults.standard.string(forKey: "selectedResolution") ?? ""
                    ) ?? .defaultValue
                ) {
                    if let activeCameraID = youtube.videoUplink.currentCameraID {
                        _ = await cameraManager.switchCamera(to: activeCameraID)
                    }
                    isBroadcasting = true
                } else {
                    await cameraManager.startDefaultCamera()
                }
            }
        }
    }

    private func toggleYouTubeStream() {
        localFeedbackMessage = nil
        Task {
            if isYouTubeStreaming {
                previewTransition = .stopping
                defer { previewTransition = .none }
                await youtube.endBroadcast(accessToken: authentication.currentAccessToken())
                await cameraManager.startDefaultCamera()
                isBroadcasting = false
            } else if youtube.broadcastPhase == "prepared" {
                await youtube.goLiveYouTubeStream(accessToken: authentication.currentAccessToken())
            } else {
                await youtube.prepareYouTubeStream(accessToken: authentication.currentAccessToken())
            }
        }
    }

    private func toggleYouTubePause() {
        localFeedbackMessage = nil
        Task {
            if youtube.isYouTubeBroadcastPaused {
                await youtube.resumeYouTubeStream(accessToken: authentication.currentAccessToken())
            } else {
                await youtube.pauseYouTubeStream(accessToken: authentication.currentAccessToken())
            }
        }
    }

    private func toggleAnonymization() {
        localFeedbackMessage = nil
        Task {
            await youtube.toggleAnonymization(accessToken: authentication.currentAccessToken())
        }
    }

    private func cancelYouTubePreparation() {
        localFeedbackMessage = nil
        Task {
            await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken())
        }
    }
}
