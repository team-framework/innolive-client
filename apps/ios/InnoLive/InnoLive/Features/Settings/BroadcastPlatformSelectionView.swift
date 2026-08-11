//
//  BroadcastPlatformSelectionView.swift
//  InnoLive
//

import SwiftUI
import UIKit

struct BroadcastPlatformSelectionView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var connectedPlatforms: Set<BroadcastPlatform> = []
    @State private var selectedPlatforms: Set<BroadcastPlatform> = []

    private var visiblePlatforms: [BroadcastPlatform] {
        BroadcastPlatform.allCases.filter { $0 != .youTube || youtube.isFeatureAvailable }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("여러 플랫폼에 동시송출을 하려면 여러 플랫폼을 선택하세요.")
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
        .navigationTitle("방송할 플랫폼")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            youtube.dismissError()
            await youtube.refreshAvailability()
        }
    }

    @ViewBuilder
    private func platformRow(_ platform: BroadcastPlatform) -> some View {
        if platform == .youTube {
            youTubeRow
        } else if connectedPlatforms.contains(platform) {
            selectionRow(platform)
        } else {
            SettingsGlassRow {
                HStack(spacing: 12) {
                    platformIdentity(platform)
                    Spacer()
                    Button("OAuth 연결") { connectedPlatforms.insert(platform) }
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
                        Text("연결됨")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Button(action: connectYouTube) {
                        if youtube.isConnecting {
                            ProgressView()
                        } else {
                            Text("계정 연결")
                        }
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.blue)
                    .disabled(youtube.isConnecting)
                }
            }
        }
    }

    private func selectionRow(_ platform: BroadcastPlatform) -> some View {
        Button {
            if selectedPlatforms.contains(platform) {
                selectedPlatforms.remove(platform)
            } else {
                selectedPlatforms.insert(platform)
            }
        } label: {
            SettingsGlassRow {
                HStack(spacing: 12) {
                    platformIdentity(platform)
                    Spacer()
                    Label(
                        selectedPlatforms.contains(platform) ? "선택됨" : "선택",
                        systemImage: selectedPlatforms.contains(platform) ? "checkmark.square.fill" : "square"
                    )
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(selectedPlatforms.contains(platform) ? .blue : .secondary)
                }
            }
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selectedPlatforms.contains(platform) ? .blue : .clear, lineWidth: 2)
            }
        }
        .buttonStyle(PressEffectButtonStyle())
        .sensoryFeedback(.selection, trigger: selectedPlatforms.contains(platform))
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

private struct PressEffectButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.72 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
