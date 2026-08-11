//
//  BroadcastControllsView.swift
//  InnoLive
//
//  Created by chaeyn on 7/26/26.
//

import SwiftUI

struct BroadcastControllsView: View {
    @Binding var isBroadcasting: Bool
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
                            title: isBroadcasting ? "비식별화 On" : "비식별화 Off",
                            systemImage: isBroadcasting ? "checkmark.circle.fill" : "circle",
                            isLoading: isPreparingAnonymization
                        )
                    }
                    .buttonStyle(.glassProminent)
                    .tint(isBroadcasting ? .green : .blue)
                    .disabled(isPreparingAnonymization || youtube.videoUplink.isSwitchingCamera)
                    .accessibilityHint("카메라와 마이크를 서버의 비식별화 처리에 연결합니다.")

                    Button(action: toggleYouTubeStream) {
                        YouTubeBroadcastControlLabel(
                            youtube: youtube,
                            isLoading: youtube.isChangingStreamState
                        )
                    }
                    .buttonStyle(.glass)
                    .tint(isYouTubeStreaming ? .red : nil)
                    .disabled(
                        youtube.isChangingStreamState
                            || !isBroadcasting
                            || !youtube.isFeatureAvailable
                            || !youtube.isConnected
                    )
                    .accessibilityHint(
                        youtube.isConnected
                            ? "YouTube 송출을 시작하거나 중지합니다."
                            : "설정에서 YouTube 계정을 먼저 연결해 주세요."
                    )
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

    private var isPreparingAnonymization: Bool {
        youtube.isPreparingSession
            || youtube.isConnectingVideo
            || youtube.isRecoveringVideoFailure
            || youtube.videoUplink.isConnecting
    }

    private var isYouTubeStreaming: Bool {
        youtube.isYouTubeBroadcastActive
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
                localFeedbackMessage = "YouTube 송출 중에는 비식별화를 끌 수 없습니다. 방송을 유지한 채 전환하려면 서버 지원이 필요합니다."
                return
            }
            Task {
                await youtube.endBroadcast(accessToken: authentication.currentAccessToken())
                cameraManager.startDefaultCamera()
                isBroadcasting = false
            }
            return
        }
        Task {
            if await youtube.prepareSession(accessToken: authentication.currentAccessToken()) {
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
                    cameraManager.startDefaultCamera()
                }
            }
        }
    }

    private func toggleYouTubeStream() {
        localFeedbackMessage = nil
        Task {
            if isYouTubeStreaming {
                await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken())
            } else {
                await youtube.startYouTubeStream(accessToken: authentication.currentAccessToken())
            }
        }
    }
}
