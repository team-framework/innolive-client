import SwiftUI

struct UpgradeOfferView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var confirmingMode: String?
    @State private var settingsProvider: BroadcastSettingsProvider?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
            if let offer = youtube.upgradeOffer {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    if let expiration = offer.expirationDate {
                        Text(String(localized: "제안 만료까지 \(max(0, Int(ceil(expiration.timeIntervalSince(context.date)))))초", table: "UpgradeOffer"))
                            .font(.footnote).monospacedDigit()
                        Text(String(localized: "만료 시각: \(expiration.formatted(date: .abbreviated, time: .standard))", table: "UpgradeOffer"))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                if offer.selected != nil {
                    Text(String(localized: "설정을 저장하면 선택한 방식으로 전환합니다. 보류 시간은 서버와 동기화됩니다.", table: "UpgradeOffer"))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach((offer.options ?? []).filter { offer.selected == nil || $0.mode == offer.selected }, id: \.mode) { option in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(option.resolutionTitle + " · " + option.platformsTitle).font(.subheadline.weight(.semibold))
                        Text(String(localized: "방송 시간 차감: \(offer.unitsFrom)배 → \(option.unitsTo)배", table: "UpgradeOffer"))
                        Text(option.remainingTimeTitle)
                        if option.restartsBroadcast { Text(option.restartMessage).foregroundStyle(.secondary) }
                        Button {
                            if option.restartsBroadcast { confirmingMode = option.mode }
                            else { choose(option.mode, confirmed: false) }
                        } label: {
                            Text(option.needsSettings
                                 ? String(localized: "추가 플랫폼 설정", table: "UpgradeOffer")
                                 : String(localized: "이 방식으로 전환", table: "UpgradeOffer"))
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .innoLiveGlassButtonStyle(prominent: true)
                        .disabled(!youtube.canChangeBroadcastMode || option.supportedTargets == nil)
                        .accessibilityIdentifier("upgrade-select-" + option.mode)
                    }
                    .font(.footnote)
                    .padding(12)
                    .innoLiveGlassBackground(cornerRadius: 12)
                }
                if offer.options?.isEmpty != false {
                    Text(String(localized: "이 제안의 선택지를 확인할 수 없습니다.", table: "UpgradeOffer")).font(.footnote)
                }
                if let message = youtube.upgradeOfferError ?? youtube.broadcastModeError {
                    Text(message).font(.footnote).foregroundStyle(.red)
                }
                if youtube.isHandlingUpgradeOffer || youtube.isChangingBroadcastMode { ProgressView() }
                Button(String(localized: "제안 거절", table: "UpgradeOffer"), role: .cancel) {
                    Task { await youtube.declineUpgradeOffer(accessToken: authentication.currentAccessToken()) }
                }
                .disabled(youtube.isHandlingUpgradeOffer || youtube.isChangingBroadcastMode || youtube.isSavingLiveSettings || youtube.isChangingStreamState)
                .accessibilityIdentifier("upgrade-decline")
            }
            }.padding(24)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .navigationTitle(String(localized: "방송 업그레이드 제안", table: "UpgradeOffer"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(String(localized: "닫기")) { dismiss() } }
        }
        .accessibilityIdentifier("upgrade-offer")
        .alert(String(localized: "방송을 다시 시작할까요?", table: "UpgradeOffer"), isPresented: Binding(
            get: { confirmingMode != nil }, set: { if !$0 { confirmingMode = nil } }
        ), presenting: youtube.upgradeOffer?.options?.first { $0.mode == confirmingMode }) { option in
            Button(String(localized: "취소"), role: .cancel) { confirmingMode = nil }
            Button(String(localized: "전환", table: "UpgradeOffer")) {
                confirmingMode = nil
                choose(option.mode, confirmed: true)
            }
            .disabled(!youtube.canChangeBroadcastMode)
        } message: { option in
            Text(option.restartMessage)
        }
        .navigationDestination(item: $settingsProvider) { provider in
                BroadcastSettingsView(authentication: authentication, youtube: youtube, modeProvider: provider,
                                      upgradeSettings: true, onModeSettingsSaved: { settingsProvider = nil })
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(String(localized: "닫기")) { settingsProvider = nil }
                        }
                    }
        }
        .onChange(of: youtube.upgradeOffer) { _, offer in
            if offer == nil { confirmingMode = nil; settingsProvider = nil; dismiss() }
        }
    }

    private func choose(_ mode: String, confirmed: Bool) {
        Task {
            let accepted = await youtube.chooseUpgradeOption(mode: mode, restartConfirmed: confirmed,
                                                             accessToken: authentication.currentAccessToken())
            if accepted, youtube.selectedUpgradeOption?.mode == mode {
                settingsProvider = youtube.upgradeSettingsProviders.first
            }
        }
    }
}
