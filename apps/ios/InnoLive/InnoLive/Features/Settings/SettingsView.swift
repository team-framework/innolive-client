//
//  SettingsView.swift
//  InnoLive
//

import SwiftUI

struct SettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    var body: some View {
        ScrollView {
            // Liquid Glass 효과를 자연스럽게 묶음
            GlassEffectContainer {
                VStack(spacing: 12) {
                    NavigationLink {
                        CameraAudioSettingsView(youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRowContent(
                                title: "카메라 및 오디오 설정",
                                systemImage: "camera.fill"
                            )
                        }
                    }
                    .buttonStyle(.plain)

                    NavigationLink {
                        FaceManagementView(authentication: authentication, youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRowContent(
                                title: "얼굴 관리",
                                systemImage: "faceid"
                            )
                        }
                    }
                    .buttonStyle(.plain)
                    
                    NavigationLink {
                        BroadcastSettingsView(authentication: authentication, youtube: youtube)
                    } label: {
                        SettingsGlassRow {
                            settingsRowContent(
                                title: "방송 설정",
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
                                title: "계정 설정",
                                systemImage: "person.crop.circle.fill"
                            )
                        }
                    }
                    .buttonStyle(.plain)
                }
                
                Spacer()
            }
            .padding(24)
            .navigationTitle("설정")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // 공용 Glass 행 안에 들어갈 설정 항목의 우측
    private func settingsRowContent(title: String, systemImage: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.body)
                .frame(width: 28)

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
        SettingsView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
    .environment(CameraManager())
}
