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

                    broadcastTitleCard
                    broadcastDescriptionCard

                    SettingsGlassRow {
                        HStack {
                            Text("공개 범위")
                                .font(.body.weight(.semibold))
                            Spacer()
                            Picker("공개 범위", selection: privacyBinding) {
                                ForEach(YouTubeBroadcastPrivacy.allCases) { privacy in
                                    Text(privacy.title).tag(privacy)
                                }
                            }
                            .labelsHidden()
                            .pickerStyle(.menu)
                        }
                    }
                    .disabled(youtube.isBroadcastSettingsLocked)

                    SettingsGlassRow {
                        HStack {
                            Text("YouTube 시청자층")
                                .font(.body.weight(.semibold))
                            Spacer()
                            Picker("YouTube 시청자층", selection: audienceBinding) {
                                Text("선택 필요").tag(Optional<YouTubeBroadcastAudience>.none)
                                ForEach(YouTubeBroadcastAudience.allCases) { audience in
                                    Text(audience.title).tag(Optional(audience))
                                }
                            }
                            .labelsHidden()
                            .pickerStyle(.menu)
                        }
                    }
                    .disabled(youtube.isBroadcastSettingsLocked)

                    if youtube.isBroadcastSettingsLocked {
                        Text("방송을 준비하거나 송출하는 동안에는 YouTube 방송 정보를 변경할 수 없습니다.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    }
                }
            }
            .padding(24)
        }
        .navigationTitle("방송 설정")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var broadcastTitleCard: some View {
        settingsCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("YouTube 방송 제목")
                        .font(.body.weight(.semibold))
                    Spacer()
                    Text("\(youtube.broadcastSettings.title.count)/\(YouTubeBroadcastSettings.maxTitleLength)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                TextField("방송 제목을 입력해 주세요", text: titleBinding)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .disabled(youtube.isBroadcastSettingsLocked)
                Text("필수")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var broadcastDescriptionCard: some View {
        settingsCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("YouTube 방송 설명")
                        .font(.body.weight(.semibold))
                    Spacer()
                    Text("\(youtube.broadcastSettings.description.count)/\(YouTubeBroadcastSettings.maxDescriptionLength)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                ZStack(alignment: .topLeading) {
                    if youtube.broadcastSettings.description.isEmpty {
                        Text("방송 설명을 입력해 주세요")
                            .foregroundStyle(.tertiary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 8)
                    }
                    TextEditor(text: descriptionBinding)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 96)
                        .disabled(youtube.isBroadcastSettingsLocked)
                }
            }
        }
    }

    private var titleBinding: Binding<String> {
        Binding(
            get: { youtube.broadcastSettings.title },
            set: {
                youtube.broadcastSettings.title = String($0.prefix(YouTubeBroadcastSettings.maxTitleLength))
            }
        )
    }

    private var descriptionBinding: Binding<String> {
        Binding(
            get: { youtube.broadcastSettings.description },
            set: {
                youtube.broadcastSettings.description = String(
                    $0.prefix(YouTubeBroadcastSettings.maxDescriptionLength)
                )
            }
        )
    }

    private var privacyBinding: Binding<YouTubeBroadcastPrivacy> {
        Binding(
            get: { youtube.broadcastSettings.privacy },
            set: { youtube.broadcastSettings.privacy = $0 }
        )
    }

    private var audienceBinding: Binding<YouTubeBroadcastAudience?> {
        Binding(
            get: { youtube.broadcastSettings.audience },
            set: { youtube.broadcastSettings.audience = $0 }
        )
    }

    private func settingsCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(.regular, in: .rect(cornerRadius: 16))
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
