//
//  SettingsView.swift
//  InnoLive
//

import SwiftUI

struct SettingsView: View {
    @ScaledMetric(relativeTo: .body) private var iconWidth: CGFloat = 28
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    var tutorial: BroadcastTutorialCoordinator? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var isAdministrator = false
    @State private var faceAnonymization = FaceAnonymizationMode.selected
    var body: some View {
        ScrollView {
            // Liquid Glass 효과를 자연스럽게 묶음
            InnoLiveGlassContainer {
                VStack(spacing: 12) {
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

                    if let tutorial {
                        // 방송을 준비하거나 송출하는 중에는 처음부터 안내할 수 없으므로 막는다.
                        Button {
                            tutorial.restart()
                            dismiss()
                        } label: {
                            SettingsGlassRow {
                                settingsRowContent(
                                    title: String(localized: "방송 준비 안내 다시 보기", table: "BroadcastGuide"),
                                    systemImage: "questionmark.circle"
                                )
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(youtube.session != nil)
                    }

                    if let privacyPolicyURL = InnoLiveLinks.privacyPolicyURL {
                        Link(destination: privacyPolicyURL) {
                            SettingsGlassRow {
                                HStack(spacing: 12) {
                                    Image(systemName: "doc.text")
                                        .font(.body)
                                        .frame(width: iconWidth)

                                    Text(String(localized: "개인정보처리방침"))
                                        .font(.subheadline.weight(.semibold))
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

                    if isAdministrator {
                        labSection
                    }
                }
                
                Spacer()
            }
            .padding(InnoLiveSettingsLayout.pageInset)
            .innoLiveSettingsPage()
            .navigationTitle(String(localized: "앱 정보 및 계정"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .task { isAdministrator = await youtube.isAdministrator(accessToken: authentication.currentAccessToken()) }
    }

    // 관리자 전용 실험 기능이다. 비식별화 방법은 세션 생성 때 고정되므로 방송 준비 전에만 바꾼다.
    private var labSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(String(localized: "실험실"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)

            SettingsGlassRow {
                HStack(spacing: 12) {
                    Image(systemName: "flask")
                        .font(.body)
                        .frame(width: iconWidth)

                    Text(String(localized: "비식별화 방법"))
                        .font(.subheadline.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)

                    Spacer()

                    Picker(String(localized: "비식별화 방법"), selection: $faceAnonymization) {
                        ForEach(FaceAnonymizationMode.allCases) { option in
                            Text(option.title).tag(option)
                        }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .accessibilityIdentifier("lab-face-anonymization")
                }
            }
            .disabled(youtube.session != nil)
            .onChange(of: faceAnonymization) { _, mode in
                UserDefaults.standard.set(mode.rawValue, forKey: FaceAnonymizationMode.storageKey)
            }

            Text(youtube.session != nil
                 ? String(localized: "방송 준비를 취소한 뒤 비식별화 방법을 변경할 수 있습니다.")
                 : String(localized: "다음 방송 준비부터 적용됩니다. 합성은 서버 AI 처리에서만 사용됩니다."))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // 공용 Glass 행 안에 들어갈 설정 항목의 우측
    private func settingsRowContent(title: String, systemImage: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.body)
                .frame(width: iconWidth)

            Text(title)
                .font(.subheadline.weight(.semibold))
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
