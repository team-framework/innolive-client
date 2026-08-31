//
//  BroadcastSettingsView.swift
//  InnoLive
//

import SwiftUI

struct BroadcastSettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var draftSettings: YouTubeBroadcastSettings
    @State private var isSaveConfirmationPresented = false
    private let onPrepare: (() -> Void)?

    init(
        authentication: AuthSession,
        youtube: YouTubeIntegration,
        onPrepare: (() -> Void)? = nil
    ) {
        self.authentication = authentication
        self.youtube = youtube
        self.onPrepare = onPrepare
        _draftSettings = State(initialValue: youtube.broadcastSettings)
    }

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

                    broadcastTitleSection
                    broadcastDescriptionSection

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

                    if isPreparingBroadcast, !youtube.isConnected {
                        Label("YouTube 계정을 먼저 연결해 주세요.", systemImage: "exclamationmark.circle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    } else if isPreparingBroadcast, !youtube.isVideoConnected {
                        Label("서버 영상 연결을 확인해 주세요.", systemImage: "exclamationmark.circle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    }

                    if let errorMessage = youtube.errorMessage {
                        BroadcastFeedbackBanner(
                            feedback: BroadcastFeedback(message: errorMessage, isError: true),
                            youtube: youtube,
                            onDismiss: youtube.dismissError
                        )
                    }

                    Button(action: performPrimaryAction) {
                        HStack(spacing: 8) {
                            if isPreparingBroadcast && youtube.isChangingStreamState {
                                ProgressView()
                                    .controlSize(.small)
                            }
                            Text(primaryActionTitle)
                                .font(.body.weight(.semibold))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.blue)
                    .disabled(isPrimaryActionDisabled)
                }
            }
            .padding(24)
        }
        .navigationTitle("방송 설정")
        .navigationBarTitleDisplayMode(.inline)
        .alert("방송 설정 저장 완료", isPresented: $isSaveConfirmationPresented) {
            Button("확인", role: .cancel) {}
        } message: {
            Text("방송 시작 시 YouTube에 적용됩니다.")
        }
    }

    private var broadcastTitleSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text("YouTube 방송 제목")
                    .font(.body.weight(.semibold))
                Text("필수")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(draftSettings.title.count)/\(YouTubeBroadcastSettings.maxTitleLength)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 4)

            settingsCard {
                TextField("방송 제목을 입력해 주세요", text: titleBinding)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .disabled(youtube.isBroadcastSettingsLocked)
            }
        }
    }

    private var broadcastDescriptionSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("YouTube 방송 설명")
                    .font(.body.weight(.semibold))
                Spacer()
                Text("\(draftSettings.description.count)/\(YouTubeBroadcastSettings.maxDescriptionLength)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 4)

            settingsCard {
                ZStack(alignment: .topLeading) {
                    if draftSettings.description.isEmpty {
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
            get: { draftSettings.title },
            set: {
                draftSettings.title = String($0.prefix(YouTubeBroadcastSettings.maxTitleLength))
            }
        )
    }

    private var descriptionBinding: Binding<String> {
        Binding(
            get: { draftSettings.description },
            set: {
                draftSettings.description = String(
                    $0.prefix(YouTubeBroadcastSettings.maxDescriptionLength)
                )
            }
        )
    }

    private var privacyBinding: Binding<YouTubeBroadcastPrivacy> {
        Binding(
            get: { draftSettings.privacy },
            set: { draftSettings.privacy = $0 }
        )
    }

    private var audienceBinding: Binding<YouTubeBroadcastAudience?> {
        Binding(
            get: { draftSettings.audience },
            set: { draftSettings.audience = $0 }
        )
    }

    private var isPreparingBroadcast: Bool {
        onPrepare != nil
    }

    private var primaryActionTitle: String {
        if isPreparingBroadcast && youtube.isChangingStreamState {
            return "방송 준비 중"
        }
        return isPreparingBroadcast ? "방송 준비" : "저장"
    }

    private var isPrimaryActionDisabled: Bool {
        youtube.isBroadcastSettingsLocked
            || draftSettings.normalized.title.isEmpty
            || (isPreparingBroadcast && draftSettings.audience == nil)
            || (isPreparingBroadcast && !youtube.isConnected)
            || (isPreparingBroadcast && !youtube.isVideoConnected)
    }

    private func performPrimaryAction() {
        saveBroadcastSettings()
        guard let onPrepare else {
            isSaveConfirmationPresented = true
            return
        }
        onPrepare()
    }

    private func saveBroadcastSettings() {
        youtube.broadcastSettings = draftSettings.normalized
        draftSettings = youtube.broadcastSettings
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
