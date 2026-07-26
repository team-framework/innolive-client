//
//  BroadcastPlatformSelectionView.swift
//  InnoLive
//

import SwiftUI

struct BroadcastPlatformSelectionView: View {
    @State private var connectedPlatforms: Set<BroadcastPlatform> = []
    @State private var selectedPlatforms: Set<BroadcastPlatform> = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("여러 플랫폼에 동시송출을 하려면 여러 플랫폼을 선택하세요.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                GlassEffectContainer {
                    VStack(spacing: 12) {
                        ForEach(BroadcastPlatform.allCases) { platform in
                            platformRow(platform)
                        }
                    }
                }
            }
            .padding(24)
        }
        .navigationTitle("방송할 플랫폼")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func platformRow(_ platform: BroadcastPlatform) -> some View {
        if connectedPlatforms.contains(platform) {
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
                            systemImage: selectedPlatforms.contains(platform)
                                ? "checkmark.square.fill"
                                : "square"
                        )
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(
                            selectedPlatforms.contains(platform) ? .blue : .secondary
                        )
                    }
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(
                            selectedPlatforms.contains(platform) ? .blue : .clear,
                            lineWidth: 2
                        )
                }
            }
            .buttonStyle(PressEffectButtonStyle())
            .sensoryFeedback(
                .selection,
                trigger: selectedPlatforms.contains(platform)
            )
        } else {
            SettingsGlassRow {
                HStack(spacing: 12) {
                    platformIdentity(platform)
                    Spacer()

                    Button("OAuth 연결") {
                        connectedPlatforms.insert(platform)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.blue)
                }
            }
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
}

// 버튼을 누르는 동안 살짝 작아지고 어두워지는 효과
private struct PressEffectButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.72 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
