//
//  BroadcastSettingsView.swift
//  InnoLive
//

import SwiftUI

struct BroadcastSettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    var body: some View {
        ScrollView {
            GlassEffectContainer {
                VStack(spacing: 12) {
                    NavigationLink {
                        BroadcastPlatformSelectionView(authentication: authentication, youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRow(title: "방송할 플랫폼")
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(24)
        }
        .navigationTitle("방송 설정")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func settingsRow(title: String) -> some View {
        HStack {
            Text(title)
                .font(.body.weight(.semibold))
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }
}

#Preview {
    NavigationStack {
        BroadcastSettingsView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
}
