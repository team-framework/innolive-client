//
//  SettingsView.swift
//  InnoLive
//

import SwiftUI

struct SettingsView: View {
    @ScaledMetric(relativeTo: .body) private var iconWidth: CGFloat = 28
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    var body: some View {
        ScrollView {
            // Liquid Glass 효과를 자연스럽게 묶음
            InnoLiveGlassContainer {
                VStack(spacing: 12) {
                    NavigationLink {
                        CameraAudioSettingsView(youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRowContent(
                                title: String(localized: "카메라 및 오디오 설정"),
                                systemImage: "camera.fill"
                            )
                        }
                    }
                    .buttonStyle(.plain)

                    NavigationLink {
                        AISettingsView(authentication: authentication, youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRowContent(
                                title: String(localized: "AI & Faces Setting"),
                                systemImage: "sparkles"
                            )
                        }
                    }
                    .buttonStyle(.plain)
                    
                    NavigationLink {
                        BroadcastSettingsView(authentication: authentication, youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRowContent(
                                title: String(localized: "방송 설정"),
                                systemImage: "dot.radiowaves.left.and.right"
                            )
                        }
                    }
                    .buttonStyle(.plain)

                    NavigationLink {
                        AccountSettingsView(authentication: authentication, youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRowContent(
                                title: String(localized: "계정 설정"),
                                systemImage: "person.crop.circle.fill"
                            )
                        }
                    }
                    .buttonStyle(.plain)

                    if let privacyPolicyURL = InnoLiveLinks.privacyPolicyURL {
                        Link(destination: privacyPolicyURL) {
                            SettingsGlassRow {
                                HStack(spacing: 12) {
                                    Image(systemName: "doc.text")
                                        .font(.body)
                                        .frame(width: iconWidth)

                                    Text(String(localized: "개인정보처리방침"))
                                        .font(.body.weight(.semibold))
                                        .fixedSize(horizontal: false, vertical: true)

                                    Spacer()

                                    Image(systemName: "arrow.up.right")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                
                Spacer()
            }
            .padding(24)
            .navigationTitle(String(localized: "설정"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // 공용 Glass 행 안에 들어갈 설정 항목의 우측
    private func settingsRowContent(title: String, systemImage: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.body)
                .frame(width: iconWidth)

            Text(title)
                .font(.body.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }
}

#Preview {
    NavigationStack {
        SettingsView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
    .environment(CameraManager())
}
