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
        Group {
            if let connection = youtube.connection {
                connectedYouTubeAccountRow(connection)
            } else {
                SettingsGlassRow {
                    HStack(spacing: 12) {
                        platformIdentity(.youTube)
                        Spacer()

                        if youtube.isRefreshingConnection {
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

    private func connectedYouTubeAccountRow(_ connection: YouTubeConnection) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            platformIdentity(.youTube, allowsWrapping: true)

            VStack(alignment: .leading, spacing: 2) {
                Text(connection.channel.title)
                    .font(.callout.weight(.semibold))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Text(
                    connection.requiresReconnection
                        ? String(localized: "재연결 필요")
                        : String(localized: "연결됨")
                )
                .font(.caption)
                .foregroundStyle(connection.requiresReconnection ? .orange : .secondary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)

                youTubeConnectedAccountActions(requiresReconnection: connection.requiresReconnection)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .glassEffect(.regular, in: .rect(cornerRadius: 16))
    }

    private func youTubeConnectedAccountActions(requiresReconnection: Bool) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                youTubeConnectedAccountActionControls(
                    requiresReconnection: requiresReconnection,
                    allowsWrapping: false
                )
            }
            VStack(alignment: .leading, spacing: 8) {
                youTubeConnectedAccountActionControls(
                    requiresReconnection: requiresReconnection,
                    allowsWrapping: true
                )
            }
        }
    }

    @ViewBuilder
    private func youTubeConnectedAccountActionControls(
        requiresReconnection: Bool,
        allowsWrapping: Bool
    ) -> some View {
        if youtube.isConnecting || youtube.isRefreshingConnection || youtube.isDisconnecting {
            ProgressView()
                .controlSize(.small)
        }
        if requiresReconnection {
            Button(action: connectYouTube) {
                youTubeAccountActionLabel(String(localized: "다시 연결"), allowsWrapping: allowsWrapping)
            }
            .buttonStyle(.borderless)
            .disabled(youtube.isYouTubeAccountChangeBlocked)
        }
        Button(role: .destructive) {
            isShowingDisconnectConfirmation = true
        } label: {
            youTubeAccountActionLabel(String(localized: "연결 해제"), allowsWrapping: allowsWrapping)
        }
        .buttonStyle(.borderless)
        .disabled(!youtube.canDisconnectYouTubeAccount)
    }

    @ViewBuilder
    private func youTubeAccountActionLabel(_ title: String, allowsWrapping: Bool) -> some View {
        if allowsWrapping {
            Text(title)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            Text(title)
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

    private func platformIdentity(
        _ platform: BroadcastPlatform,
        allowsWrapping: Bool = false
    ) -> some View {
        HStack(spacing: 12) {
            Image(platform.assetName)
                .resizable()
                .scaledToFit()
                .frame(width: 24, height: 24)
            if allowsWrapping {
                Text(platform.rawValue)
                    .font(.body.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text(platform.rawValue)
                    .font(.body.weight(.semibold))
            }
        }
    }

    private var presentingViewController: UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?
            .rootViewController
    }
}
