//
//  BroadcastControllsView.swift
//  InnoLive
//
//  Created by chaeyn on 7/26/26.
//

import SwiftUI

enum BroadcastControlsDisplayMode {
    case basic
    case minimal
}

struct BroadcastControllsView: View {
    @Binding var isBroadcasting: Bool
    @Binding var previewTransition: BroadcastPreviewTransition
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    let onPrepareBroadcast: (BroadcastSettingsProvider) async -> Void
    let onCancelPreparation: () async -> Void
    var tutorial: BroadcastTutorialCoordinator?
    var displayMode: BroadcastControlsDisplayMode = .basic
    var faceRegistrationControl: AnyView? = nil
    var onPresentationChange: (Bool) -> Void = { _ in }
    var onBottomHeightChange: (CGFloat) -> Void = { _ in }
    var onTopHeightChange: (CGFloat) -> Void = { _ in }

    var isConnectionProgressPresented = false

    @State private var isShowingBroadcastSettings = false
    @State private var isShowingBroadcastActions = false
    @State private var isConfirmingBroadcastEnd = false
    @State private var pendingStopProvider: BroadcastSettingsProvider?
    @State private var isShowingAnonymizationActions = false

    var body: some View {
        VStack(spacing: 10) {
            if youtube.hasStartedYouTubeBroadcast {
                liveHeader
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { _, height in
                        onTopHeightChange(height)
                    }
            }
            Spacer(minLength: 8)
            bottomControls
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { _, height in
                    onBottomHeightChange(height)
                }
        }
        .frame(maxHeight: .infinity)

        .onChange(of: youtube.hasStartedYouTubeBroadcast, initial: true) { _, started in
            if !started { onTopHeightChange(0) }
        }
        .onChange(of: isPresentingControls, initial: true) { _, isPresented in
            onPresentationChange(isPresented)
        }
        .onChange(of: youtube.preparationStatus) { _, status in
            if status?.isRunning == true { isShowingBroadcastSettings = false }
        }
        .sheet(isPresented: $isShowingBroadcastSettings) {
            NavigationStack {
                BroadcastSettingsView(
                    authentication: authentication,
                    youtube: youtube,
                    onPrepare: prepareYouTubeStream,
                    onCancelPreparation: cancelPreparation,
                    onContinuePreparation: { isShowingBroadcastSettings = false }
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "닫기")) {
                            isShowingBroadcastSettings = false
                        }
                        .disabled(isPreparationSheetLocked)
                    }
                }
                .broadcastTutorialHost(tutorial, host: .settingsSheet)
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .interactiveDismissDisabled(isPreparationSheetLocked)
        }
        .confirmationDialog(
            String(localized: "방송 제어"),
            isPresented: $isShowingBroadcastActions,
            titleVisibility: .visible
        ) {
            Button(
                pauseActionTitle,
                action: toggleYouTubePause
            )
            .disabled(!youtube.canChangeYouTubePauseState)
            ForEach(BroadcastSettingsProvider.allCases) { provider in
                if activeProviders.count > 1, youtube.targetPolicy(provider).isBroadcastActive {
                    if youtube.targetPolicy(provider).canPauseBroadcast {
                        Button("\(provider.title) · \(String(localized: "일시 중지", table: "Simulcast"))") {
                            Task {
                                guard !isControlActionLocked, youtube.targetPolicy(provider).canPauseBroadcast else { return }
                                await youtube.pauseYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider)
                            }
                        }
                        .disabled(isControlActionLocked)
                    }
                    if youtube.targetPolicy(provider).canResumeBroadcast {
                        Button("\(provider.title) · \(String(localized: "재개", table: "Simulcast"))") {
                            Task {
                                guard !isControlActionLocked, youtube.targetPolicy(provider).canResumeBroadcast else { return }
                                await youtube.resumeYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider)
                            }
                        }
                        .disabled(isControlActionLocked)
                    }
                    Button("\(provider.title) · \(String(localized: "송출 종료", table: "Simulcast"))", role: .destructive) {
                        requestBroadcastEnd(provider)
                    }
                    .disabled(isStopActionLocked)
                }
            }
            Button(activeProviders.count > 1 ? String(localized: "전체 방송 종료", table: "Simulcast") : String(localized: "방송 종료"), role: .destructive) { requestBroadcastEnd(nil) }
                .disabled(isStopActionLocked)
            Button(String(localized: "취소"), role: .cancel) { }
        }
        .alert(String(localized: "방송을 종료할까요?"), isPresented: $isConfirmingBroadcastEnd) {
            Button(String(localized: "취소"), role: .cancel) {}
            Button(String(localized: "방송 종료"), role: .destructive, action: confirmBroadcastEnd)
                .disabled(isStopActionLocked)
        } message: {
            if let provider = pendingStopProvider {
                Text(String(localized: "\(provider.title) 송출을 종료합니다."))
            } else {
                Text(String(localized: "방송 중인 모든 플랫폼의 송출을 종료합니다."))
            }
        }
        .onChange(of: tutorialSnapshot, initial: true) { _, snapshot in
            tutorial?.update(snapshot)
        }
    }

    private var bottomControls: some View {
        VStack(spacing: 10) {
            if !isShowingBroadcastSettings, let feedback {
                BroadcastFeedbackBanner(feedback: feedback, youtube: youtube) {
                    youtube.dismissError()
                }
            } else if !isShowingBroadcastSettings, !warningMessages.isEmpty {
                StudioWarningLabel(title: warningMessages.joined(separator: "\n"), systemImage: "exclamationmark.triangle.fill")
                    .accessibilityIdentifier("broadcast-warning-summary")
            }

            if displayMode == .basic {
                if !youtube.hasStartedYouTubeBroadcast {
                    broadcastSummary
                }
                VStack(spacing: 8) {
                    HStack(spacing: 8) {
                        if let faceRegistrationControl {
                            faceRegistrationControl
                            Rectangle().fill(.white.opacity(0.2)).frame(width: 1, height: 24)
                        }
                        primaryControl
                        if canCancelPreparation {
                            Button(role: .destructive, action: cancelPreparation) {
                                Image(systemName: "xmark")
                                    .font(.system(size: 13, weight: .semibold))
                                    .frame(width: 44, height: 44)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.white)
                            .background(Color.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(.white.opacity(0.25)))
                            .accessibilityLabel(String(localized: "방송 준비 취소"))
                            .accessibilityIdentifier("studio-cancel-preparation")
                            .disabled(youtube.preparationStatus?.phase == .cancelling)
                        }
                        if isBroadcasting {
                            Rectangle().fill(.white.opacity(0.2)).frame(width: 1, height: 24)
                            anonymizationControl
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .innoLiveGlassSurface(in: RoundedRectangle(cornerRadius: 12), overVideo: true)
                }
            }
        }
    }

    private var primaryControl: some View {
        Button(action: performPrimaryAction) {
            if isBroadcasting {
                if youtube.hasStartedYouTubeBroadcast {
                    Label(String(localized: "방송 제어"), systemImage: "slider.horizontal.3")
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                } else {
                    YouTubeBroadcastControlLabel(
                        youtube: youtube,
                        isLoading: youtube.isChangingStreamState
                    )
                }
            } else {
                BroadcastPreparationControlLabel(
                    status: youtube.preparationStatus,
                    isLoading: isPreparingConnection && !isConnectionProgressPresented
                )
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
        .background(isReadyToStart ? Color.red : Color.clear, in: RoundedRectangle(cornerRadius: 10))
        .disabled(isPrimaryActionDisabled)
        .layoutPriority(1)
        .broadcastTutorialAnchor(.primaryButton)
        .accessibilityHint(primaryActionHint)
        .contextMenu {
            if canCancelPreparation {
                Button(role: .destructive, action: cancelPreparation) {
                    Label(String(localized: "방송 준비 취소"), systemImage: "xmark.circle.fill")
                }
            }
        }
    }

    private var anonymizationControl: some View {
        Button { isShowingAnonymizationActions = true } label: {
            AnonymizationControlLabel(
                isLoading: youtube.isTogglingAnonymization || youtube.isTogglingScreenBlur,
                isEnabled: youtube.isAnonymizationEnabled || youtube.isScreenBlurEnabled
            )
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .disabled(!isBroadcasting || youtube.isTogglingAnonymization || youtube.isTogglingScreenBlur)
        .accessibilityLabel(String(localized: "비식별화"))
        .accessibilityValue(anonymizationStatuses.joined(separator: ", "))
        .accessibilityHint(String(localized: "얼굴 비식별화와 화면 블러 중 전환할 항목을 선택합니다."))
        .accessibilityIdentifier("broadcast-anonymization-button")
        .confirmationDialog(
            String(localized: "비식별화"),
            isPresented: $isShowingAnonymizationActions,
            titleVisibility: .visible
        ) {
            Button(
                youtube.isAnonymizationEnabled ? String(localized: "얼굴 비식별화 끄기") : String(localized: "얼굴 비식별화 켜기"),
                action: toggleAnonymization
            )
            Button(
                youtube.isScreenBlurEnabled ? String(localized: "화면 블러 끄기") : String(localized: "화면 블러 켜기"),
                action: toggleScreenBlur
            )
            .disabled(!youtube.canToggleScreenBlur)
            Button(String(localized: "취소"), role: .cancel) { }
        } message: {
            Text(anonymizationActionsMessage)
        }
    }

    private var isPresentingControls: Bool {
        isShowingBroadcastSettings || isShowingBroadcastActions
            || isConfirmingBroadcastEnd || isShowingAnonymizationActions
    }

    private var liveHeader: some View {
        StudioLiveStatusHeader(youtube: youtube) { requestBroadcastEnd(nil) }
            .disabled(isStopActionLocked)
            .broadcastTutorialAnchor(.liveStatus)
    }

    private var broadcastSummary: some View {
        Button { isShowingBroadcastSettings = true } label: {
            StudioBroadcastSummaryLabel(youtube: youtube, editor: youtube.settingsEditor)
        }
        .buttonStyle(.plain)
        .disabled(isPreparationSheetLocked)
        .accessibilityLabel(String(localized: "방송 정보"))
        .accessibilityIdentifier("studio-broadcast-summary")
        .accessibilityValue(missingAccountSummary)
    }

    private var missingAccountSummary: String {
        let providers = BroadcastSettingsProvider.allCases.filter {
            youtube.selectedBroadcastProviders.contains($0) && !youtube.isSettingsAccountConnected($0)
        }
        guard !providers.isEmpty else { return "" }
        return String(localized: "계정 연결 필요") + ": " + providers.map(\.title).joined(separator: ", ")
    }

    private var warningMessages: [String] {
        var messages: [String] = []
        if youtube.isDirectPublishSession, youtube.isDirectMicrophoneUnavailable, !youtube.directPublishStates.isEmpty {
            messages.append(String(localized: "마이크를 열지 못해 음성 없이 송출하고 있습니다. 마이크 권한과 연결을 확인해 주세요.", table: "DirectPublish"))
        }
        if !youtube.liveStartFailures.isEmpty {
            messages.append(String(localized: "일부 플랫폼의 방송 시작에 실패했습니다. 방송 설정에서 확인하세요."))
        }
        if !(youtube.responseState.details.resolutionSwitch?.failedTargets ?? []).isEmpty {
            messages.append(String(localized: "일부 방송 플랫폼의 화질 전환을 완료하지 못했습니다."))
        }
        if let message = youtube.recordingCoordinator.notice {
            messages.append(String(localized: "원본 저장") + ": " + message)
        }
        return messages.reduce(into: []) { result, message in
            if !result.contains(message) { result.append(message) }
        }
    }

    /// 안내 단계는 시트 표시 여부를 아는 이 화면에서 앱 상태를 모아 전달한다.
    private var tutorialSnapshot: BroadcastTutorialSnapshot {
        BroadcastTutorialSnapshot(
            isSettingsSheetPresented: isShowingBroadcastSettings,
            selectedAccountsConnected: youtube.selectedBroadcastProviders.allSatisfy(youtube.isSettingsAccountConnected),
            preparation: youtube.preparationStatus,
            phase: YouTubeBroadcastPhase(rawValue: youtube.broadcastPhase),
            hasStartedBroadcast: youtube.hasStartedYouTubeBroadcast
        )
    }

    private var isPreparingConnection: Bool {
        previewTransition != .none
            || youtube.isPreparingSession
            || youtube.isConnectingVideo
            || youtube.isRecoveringVideoFailure
            || youtube.videoUplink.isConnecting
            || youtube.preparationStatus?.isRunning == true
            || youtube.preparationStatus?.phase == .cancelling
    }

    private var isPreparationSheetLocked: Bool {
        youtube.isChangingStreamState
            || youtube.preparationStatus?.isRunning == true
            || youtube.preparationStatus?.phase == .cancelling
    }

    private var canCancelPreparation: Bool {
        if youtube.hasStartedYouTubeBroadcast { return false }
        if youtube.broadcastPhase == "live" || youtube.broadcastPhase == "going_live" { return false }
        if youtube.preparationStatus != nil { return true }
        return youtube.broadcastPhase == "prepared" || youtube.isVideoConnected
    }

    private var isYouTubeStreaming: Bool {
        youtube.hasStartedYouTubeBroadcast
    }

    private var isPrimaryActionDisabled: Bool {
        let preparationFailed = youtube.preparationStatus?.isFailed == true
        return youtube.lifecycle.phase == .starting
            || { if case .countdown = youtube.lifecycle.phase { return true }; return false }()
            || (!preparationFailed && isPreparingConnection)
            || youtube.videoUplink.isSwitchingCamera
            || youtube.isChangingStreamState
            || previewTransition == .stopping
            || ["preparing", "going_live"].contains(youtube.broadcastPhase)
            || (isBroadcasting && !youtube.isFeatureAvailable)
    }

    private var isReadyToStart: Bool {
        isBroadcasting && !youtube.hasStartedYouTubeBroadcast && youtube.broadcastPhase == "prepared"
            && !isPrimaryActionDisabled
    }

    private var primaryActionHint: String {
        if !isBroadcasting {
            if youtube.preparationStatus?.isFailed == true {
                return String(localized: "실패한 준비 단계부터 다시 시도합니다.")
            }
            return String(localized: "방송 설정을 확인하고 세션과 서버 연결을 시작합니다.")
        }
        if isYouTubeStreaming {
            return String(localized: "방송 일시 중지, 재개, 종료 메뉴를 엽니다.")
        }
        if youtube.broadcastPhase == "prepared" {
            return String(localized: "준비된 플랫폼의 방송을 시청자에게 공개합니다.", table: "Simulcast")
        }
        return String(localized: "방송 설정 시트를 엽니다.")
    }

    var feedback: BroadcastFeedback? {
        // 연결/복구가 끝난 뒤 Integration이 확정한 오류만 표시한다.
        // 업링크의 임시 오류를 먼저 표시하면 복구 후 같은 배너가 다시 나타난다.
        guard !isPreparingConnection, let errorMessage = youtube.errorMessage else {
            return nil
        }
        return BroadcastFeedback(message: errorMessage, isError: true)
    }

    private func performPrimaryAction() {
        youtube.dismissError()

        if youtube.preparationStatus?.isFailed == true, let provider = youtube.lastPreparationProvider {
            prepareYouTubeStream(provider)
            return
        }

        guard isBroadcasting else {
            isShowingBroadcastSettings = true
            return
        }

        if isYouTubeStreaming {
            isShowingBroadcastActions = true
        } else if youtube.broadcastPhase == "prepared" {
            Task {
                await youtube.startBroadcastCountdown(accessToken: authentication.currentAccessToken())
            }
        } else {
            isShowingBroadcastSettings = true
        }
    }

    private func prepareYouTubeStream(_ provider: BroadcastSettingsProvider) {
        isShowingBroadcastSettings = false
        Task {
            await onPrepareBroadcast(provider)
            if youtube.preparationStatus?.isFailed != true, youtube.broadcastPhase == "prepared" {
                isShowingBroadcastSettings = false
            }
        }
    }

    private var activeProviders: [BroadcastSettingsProvider] {
        BroadcastSettingsProvider.allCases.filter { youtube.targetPolicy($0).isBroadcastActive }
    }

    private var isControlActionLocked: Bool {
        youtube.isChangingStreamState || youtube.isChangingBroadcastMode || youtube.isHandlingUpgradeOffer
    }

    private var pauseActionTitle: String {
        if activeProviders.count > 1 {
            return youtube.isYouTubeBroadcastPaused ? String(localized: "모든 플랫폼 재개") : String(localized: "모든 플랫폼 일시 중지")
        }
        return youtube.isYouTubeBroadcastPaused ? String(localized: "방송 재개") : String(localized: "방송 일시 중지")
    }

    private var isStopActionLocked: Bool {
        !youtube.canStopBroadcastTargets || previewTransition == .stopping
    }

    private func requestBroadcastEnd(_ provider: BroadcastSettingsProvider?) {
        guard !isStopActionLocked else { return }
        pendingStopProvider = provider
        isConfirmingBroadcastEnd = true
    }

    private func confirmBroadcastEnd() {
        let provider = pendingStopProvider
        Task {
            guard !isStopActionLocked else { return }
            if let provider {
                guard youtube.targetPolicy(provider).isBroadcastActive else { return }
            } else {
                guard youtube.hasStartedYouTubeBroadcast else { return }
            }
            await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken(), provider: provider)
        }
    }

    private func toggleYouTubePause() {
        Task {
            guard !isControlActionLocked, youtube.canChangeYouTubePauseState else { return }
            if youtube.isYouTubeBroadcastPaused {
                await youtube.resumeYouTubeStream(accessToken: authentication.currentAccessToken())
            } else {
                await youtube.pauseYouTubeStream(accessToken: authentication.currentAccessToken())
            }
        }
    }

    private func toggleAnonymization() {
        Task {
            await youtube.toggleAnonymization(accessToken: authentication.currentAccessToken())
        }
    }

    private func toggleScreenBlur() {
        Task {
            await youtube.toggleScreenBlur(accessToken: authentication.currentAccessToken())
        }
    }

    private var anonymizationStatuses: [String] {
        [
            youtube.isAnonymizationEnabled ? String(localized: "얼굴 비식별화 켜짐") : String(localized: "얼굴 비식별화 꺼짐"),
            youtube.isScreenBlurEnabled ? String(localized: "화면 블러 켜짐") : String(localized: "화면 블러 꺼짐")
        ]
    }

    private var anonymizationActionsMessage: String {
        // 화면 블러는 서버가 적용한다. 온디바이스에서는 비활성 항목이 팝오버에서 숨겨질 수 있어 이유를 함께 적는다.
        var lines = anonymizationStatuses
        if youtube.session?.processingMode == .onDevice {
            lines.append(String(localized: "화면 블러는 서버 처리 모드에서만 사용할 수 있어요."))
        }
        return lines.joined(separator: "\n")
    }

    private func cancelPreparation() {
        Task {
            await onCancelPreparation()
            if youtube.session == nil {
                isShowingBroadcastSettings = false
            }
        }
    }
}
