//
//  BroadcastPlatformSelectionView.swift
//  InnoLive
//

import SwiftUI
import UIKit

struct BroadcastPlatformSelectionView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var isShowingDisconnectConfirmation = false
    @State private var isShowingCHZZKUnavailableAlert = false

    private var visiblePlatforms: [BroadcastPlatform] {
        BroadcastPlatform.allCases.filter { $0 != .youTube || youtube.isFeatureAvailable }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(String(localized: "여러 플랫폼에 동시송출을 하려면 여러 플랫폼을 선택하세요."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                GlassEffectContainer {
                    VStack(spacing: 12) {
                        ForEach(visiblePlatforms) { platform in
                            platformRow(platform)
                        }
                    }
                }

                if let errorMessage = youtube.errorMessage {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            .padding(24)
        }
        .navigationTitle(String(localized: "방송할 플랫폼"))
        .navigationBarTitleDisplayMode(.inline)
        .alert(String(localized: "현재 준비 중인 기능입니다."), isPresented: $isShowingCHZZKUnavailableAlert) {
            Button(String(localized: "확인"), role: .cancel) {}
        }
        .task {
            youtube.dismissError()
            await youtube.refreshAvailability()
            if youtube.isFeatureAvailable {
                await youtube.refreshConnection(accessToken: authentication.currentAccessToken())
            }
        }
    }

    @ViewBuilder
    private func platformRow(_ platform: BroadcastPlatform) -> some View {
        if platform == .youTube {
            youTubeRow
        } else {
            SettingsGlassRow {
                HStack(spacing: 12) {
                    platformIdentity(platform)
                    Spacer()
                    Button(String(localized: "OAuth 연결")) {
                        isShowingCHZZKUnavailableAlert = true
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.blue)
                }
            }
        }
    }

    private var youTubeRow: some View {
        SettingsGlassRow {
            HStack(spacing: 12) {
                platformIdentity(.youTube)
                Spacer()

                if let connection = youtube.connection {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(connection.channel.title)
                            .font(.callout.weight(.semibold))
                        Text(
                            connection.requiresReconnection
                                ? String(localized: "재연결 필요")
                                : String(localized: "연결됨")
                        )
                            .font(.caption)
                            .foregroundStyle(connection.requiresReconnection ? .orange : .secondary)
                        HStack(spacing: 8) {
                            if youtube.isConnecting || youtube.isRefreshingConnection || youtube.isDisconnecting {
                                ProgressView()
                                    .controlSize(.small)
                            }
                            if connection.requiresReconnection {
                                Button(String(localized: "다시 연결"), action: connectYouTube)
                                    .buttonStyle(.borderless)
                                    .disabled(youtube.isYouTubeAccountChangeBlocked)
                            }
                            Button(String(localized: "연결 해제"), role: .destructive) {
                                isShowingDisconnectConfirmation = true
                            }
                            .buttonStyle(.borderless)
                            .disabled(!youtube.canDisconnectYouTubeAccount)
                        }
                    }
                } else if youtube.isRefreshingConnection {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button(action: connectYouTube) {
                        if youtube.isConnecting {
                            ProgressView()
                        } else {
                            Text(String(localized: "계정 연결"))
                        }
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.blue)
                    .disabled(youtube.isYouTubeAccountChangeBlocked)
                }
            }
        }
        .confirmationDialog(
            String(localized: "YouTube 연결 해제"),
            isPresented: $isShowingDisconnectConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "연결 해제"), role: .destructive) {
                Task {
                    await youtube.disconnectYouTubeAccount(
                        accessToken: authentication.currentAccessToken()
                    )
                }
            }
            .disabled(!youtube.canDisconnectYouTubeAccount)
            Button(String(localized: "취소"), role: .cancel) {}
        } message: {
            Text(String(localized: "연결을 해제하면 다시 방송하기 전에 YouTube 계정을 연결해야 합니다."))
        }
    }

    private func connectYouTube() {
        guard let presentingViewController else { return }
        Task {
            await youtube.connect(
                presenting: presentingViewController,
                accessToken: authentication.currentAccessToken()
            )
        }
    }

    private func platformIdentity(_ platform: BroadcastPlatform) -> some View {
        HStack(spacing: 12) {
            Image(platform.assetName)
                .resizable()
                .scaledToFit()
                .frame(width: 24, height: 24)
            Text(platform.rawValue)
                .font(.body.weight(.semibold))
        }
    }

    private var presentingViewController: UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?
            .rootViewController
    }
}
