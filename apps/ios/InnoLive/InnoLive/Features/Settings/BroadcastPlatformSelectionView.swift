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
    @State private var disconnectAccount: StreamingAccountSummary?
    @State private var isShowingCHZZKDisconnectConfirmation = false

    private var visiblePlatforms: [BroadcastPlatform] {
        BroadcastPlatform.allCases.filter { $0 != .youTube || youtube.isFeatureAvailable }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(String(localized: "여러 플랫폼에 동시송출을 하려면 여러 플랫폼을 선택하세요."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                BroadcastTargetSelectionView(youtube: youtube)

                InnoLiveGlassContainer {
                    VStack(spacing: 12) {
                        ForEach(visiblePlatforms) { platform in
                            platformRow(platform)
                        }
                    }
                }

                if let message = youtube.chzzkAccountMessage {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("chzzk-account-message")
                }

                if let errorMessage = youtube.errorMessage {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            .padding(InnoLiveSettingsLayout.pageInset)
        }
        .innoLiveSettingsPage()
        .navigationTitle(String(localized: "방송할 플랫폼"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            youtube.dismissError()
            await youtube.refreshAvailability()
            await youtube.refreshConnection(accessToken: authentication.currentAccessToken())
        }
    }

    @ViewBuilder
    private func platformRow(_ platform: BroadcastPlatform) -> some View {
        if platform == .youTube {
            youTubeRow
        } else {
            chzzkRow
        }
    }

    private var chzzkRow: some View {
        platformAccountCard(.chzzk) {
            if let account = youtube.chzzkAccount {
                accountDetails(account) {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { chzzkAccountActions(requiresReconnection: account.reconnectRequired, allowsWrapping: false) }
                        VStack(alignment: .leading, spacing: 8) { chzzkAccountActions(requiresReconnection: account.reconnectRequired, allowsWrapping: true) }
                    }
                }
            } else {
                Button(action: connectCHZZK) { Text(String(localized: "계정 연결")) }
                    .innoLiveGlassButtonStyle(prominent: true)
                    .tint(.blue)
                    .disabled(youtube.isCHZZKAccountChangeBlocked)
                    .accessibilityIdentifier("chzzk-connect")
            }
            if youtube.isConnectingCHZZK || youtube.isDisconnectingCHZZK || youtube.isRefreshingConnection {
                ProgressView().controlSize(.small)
            }
            if youtube.chzzkAccount != nil && youtube.isCHZZKAccountChangeBlocked
                && !youtube.isYouTubeConnectionOperationInProgress {
                Text(String(localized: "치지직 방송을 종료한 뒤 계정 연결을 변경해 주세요.", table: "CHZZK"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .confirmationDialog(
            String(localized: "치지직 연결 해제", table: "CHZZK"),
            isPresented: $isShowingCHZZKDisconnectConfirmation, titleVisibility: .visible
        ) {
            Button(String(localized: "연결 해제"), role: .destructive) {
                Task { await youtube.disconnectCHZZKAccount(accessToken: authentication.currentAccessToken()) }
            }
            .disabled(!youtube.canDisconnectCHZZKAccount)
            Button(String(localized: "취소"), role: .cancel) {}
        } message: {
            Text(String(localized: "연결을 해제하면 다시 방송하기 전에 치지직 계정을 연결해야 합니다.", table: "CHZZK"))
        }
    }

    @ViewBuilder
    private func chzzkAccountActions(requiresReconnection: Bool, allowsWrapping: Bool) -> some View {
        if requiresReconnection {
            Button(action: connectCHZZK) {
                accountActionLabel(String(localized: "다시 연결"), allowsWrapping: allowsWrapping)
            }
            .buttonStyle(.borderless)
            .disabled(youtube.isCHZZKAccountChangeBlocked)
            .accessibilityIdentifier("chzzk-reconnect")
        }
        Button(role: .destructive) {
            isShowingCHZZKDisconnectConfirmation = true
        } label: {
            accountActionLabel(String(localized: "연결 해제"), allowsWrapping: allowsWrapping)
        }
        .buttonStyle(.borderless)
        .disabled(!youtube.canDisconnectCHZZKAccount)
        .accessibilityIdentifier("chzzk-disconnect")
    }

    private func connectCHZZK() {
        guard let presentingViewController else { return }
        Task {
            await youtube.connectCHZZK(presenting: presentingViewController,
                                       accessToken: authentication.currentAccessToken())
        }
    }

    private var youTubeRow: some View {
        platformAccountCard(.youTube) {
            if youtube.youtubeAccounts.isEmpty, let connection = youtube.connection {
                VStack(alignment: .leading, spacing: 8) {
                    Text(connection.channel.title)
                        .font(.callout.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(String(localized: "마지막으로 확인한 채널", table: "YouTubeChannels"))
                        .font(.caption).foregroundStyle(.secondary)
                    Button(String(localized: "다시 시도")) {
                        Task { await youtube.refreshConnection(accessToken: authentication.currentAccessToken()) }
                    }
                    .disabled(youtube.isYouTubeConnectionOperationInProgress)
                }
            }
            ForEach(youtube.youtubeAccounts, id: \.channelID) { account in
                accountDetails(account) {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { youTubeAccountActions(account, allowsWrapping: false) }
                        VStack(alignment: .leading, spacing: 8) { youTubeAccountActions(account, allowsWrapping: true) }
                    }
                }
            }
            Button(action: connectYouTube) {
                Text(youtube.youtubeAccounts.isEmpty && youtube.connection == nil
                     ? String(localized: "계정 연결")
                     : String(localized: "채널 추가 연결", table: "YouTubeChannels"))
            }
            .innoLiveGlassButtonStyle(prominent: true)
            .tint(.blue)
            .disabled(!youtube.canConnectYouTubeChannel())
            .accessibilityIdentifier("youtube-connect")
            if youtube.isYouTubeConnectionOperationInProgress {
                ProgressView().controlSize(.small)
            }
        }
        .confirmationDialog(String(localized: "YouTube 연결 해제"),
                            isPresented: $isShowingDisconnectConfirmation, titleVisibility: .visible) {
            Button(String(localized: "연결 해제"), role: .destructive) {
                guard let account = disconnectAccount else { return }
                Task { await youtube.disconnectYouTubeAccount(accessToken: authentication.currentAccessToken(), accountID: account.accountID) }
            }
            Button(String(localized: "취소"), role: .cancel) {}
        } message: {
            Text(disconnectAccount?.displayTitle ?? "YouTube")
        }
    }

    private func platformAccountCard<Content: View>(
        _ platform: BroadcastPlatform,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            platformIdentity(platform, allowsWrapping: true)
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .innoLiveGlassBackground(cornerRadius: InnoLiveSettingsLayout.cornerRadius)
    }

    private func accountDetails<Actions: View>(
        _ account: StreamingAccountSummary,
        @ViewBuilder actions: () -> Actions
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(account.displayTitle)
                .font(.callout.weight(.semibold))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            Text(account.reconnectRequired ? String(localized: "재연결 필요") : String(localized: "연결됨"))
                .font(.caption)
                .foregroundStyle(account.reconnectRequired ? .orange : .secondary)
                .fixedSize(horizontal: false, vertical: true)
            actions()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func youTubeAccountActions(_ account: StreamingAccountSummary, allowsWrapping: Bool) -> some View {
        if account.reconnectRequired {
            Button(action: connectYouTube) {
                accountActionLabel(String(localized: "다시 연결"), allowsWrapping: allowsWrapping)
            }
            .buttonStyle(.borderless)
            .disabled(!youtube.canConnectYouTubeChannel(reconnecting: true))
        }
        Button(role: .destructive) {
            disconnectAccount = account
            isShowingDisconnectConfirmation = true
        } label: {
            accountActionLabel(String(localized: "연결 해제"), allowsWrapping: allowsWrapping)
        }
        .buttonStyle(.borderless)
        .disabled(!youtube.canDisconnectYouTubeChannel(account))
    }

    @ViewBuilder
    private func accountActionLabel(_ title: String, allowsWrapping: Bool) -> some View {
        if allowsWrapping {
            Text(title).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
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
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text(platform.rawValue)
                    .font(.subheadline.weight(.semibold))
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

struct BroadcastTargetSelectionView: View {
    @ObservedObject var youtube: YouTubeIntegration

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "송출할 플랫폼", table: "Simulcast")).font(.subheadline.weight(.semibold))
            ForEach(BroadcastSettingsProvider.allCases) { provider in
                Toggle(isOn: Binding(
                    get: { youtube.selectedBroadcastProviders.contains(provider) },
                    set: { selected in
                        if selected { youtube.selectedBroadcastProviders.insert(provider) }
                        else { youtube.selectedBroadcastProviders.remove(provider) }
                    }
                )) {
                    VStack(alignment: .leading) {
                        Text(provider.title)
                        if !youtube.isSettingsAccountConnected(provider) {
                            Text(String(localized: "계정 연결 필요", table: "Simulcast")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(youtube.isBroadcastSettingsLocked
                          || (!youtube.isSettingsAccountConnected(provider) && !youtube.selectedBroadcastProviders.contains(provider)))
                .accessibilityIdentifier("broadcast-target-" + provider.rawValue)
            }
            if youtube.selectedBroadcastProviders.isEmpty {
                Text(String(localized: "플랫폼을 선택하지 않으면 계정 없이 비식별화만 실행합니다. 현재 AI 처리 방식(서버·온디바이스)을 그대로 사용합니다."))
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("broadcast-target-privacy-only")
            }
        }
    }
}
