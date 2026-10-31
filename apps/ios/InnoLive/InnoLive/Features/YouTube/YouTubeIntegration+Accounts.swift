import Foundation
import UIKit

extension YouTubeIntegration {
    var youtubeAccounts: [StreamingAccountSummary] { streamingAccounts.filter { $0.provider == "youtube" } }

    var selectedYouTubeAccount: StreamingAccountSummary? {
        youtubeAccounts.first { $0.accountID == selectedYouTubeAccountID }
    }

    func canConnectYouTubeChannel(reconnecting: Bool = false) -> Bool {
        !isYouTubeAccountChangeBlocked && (reconnecting || youtubeAccounts.count < 5)
    }

    var canSelectYouTubeChannel: Bool {
        !isChangingStreamState && !isChangingBroadcastMode && !isPreparingSession && !isSwitchingToOnDevice
            && !isEndingSession && !isSavingLiveSettings
            && preparationStatus?.isRunning != true && preparationStatus?.phase != .cancelling
            && !isYouTubeConnectionOperationInProgress
            && !StreamingAccountPolicy.isInUse("youtube", snapshot: responseState)
    }

    func selectYouTubeChannel(_ id: String?, accessToken: String?) {
        guard canSelectYouTubeChannel, let account = youtubeAccounts.first(where: { $0.accountID == id }) else { return }
        selectedYouTubeAccountID = id
        connection = account.youtubeConnection
        configureSettingsEditor(accessToken: accessToken)
    }

    func canDisconnectYouTubeChannel(_ account: StreamingAccountSummary) -> Bool {
        guard !isYouTubeConnectionOperationInProgress, !isChangingStreamState, !isSwitchingToOnDevice,
              !isChangingBroadcastMode, !isPreparingSession, !isEndingSession,
              preparationStatus?.isRunning != true, preparationStatus?.phase != .cancelling else { return false }
        if let target = responseState.details.targets?.first(where: { $0.provider == "youtube" }),
           target.stream.broadcastPhaseValue != .idle {
            return target.accountID != nil && target.accountID != account.accountID
        }
        return !StreamingAccountPolicy.isInUse("youtube", snapshot: responseState)
    }

    func configureSettingsEditor(accessToken: String?) {
        let scope = accessToken.flatMap { try? api.broadcastSessionScope(accessToken: $0) }
        var channels = Dictionary(streamingAccounts.map { ($0.provider, $0.channelID) }, uniquingKeysWith: { _, last in last })
        if let connection { channels["youtube"] = connection.channel.id }
        settingsEditor.configure(scope: scope, channels: channels, youtubeAccountID: selectedYouTubeAccountID)
    }

    func settingsScopeKey(accessToken: String?) -> String {
        let scope = accessToken.flatMap { try? api.broadcastSessionScope(accessToken: $0) }
        return (scope?.storageKey ?? "") + ":" + streamingAccounts.map { $0.provider + ":" + $0.channelID }.sorted().joined(separator: ":")
            + ":" + (connection?.channel.id ?? "") + ":" + (selectedYouTubeAccountID ?? "")
    }

    func isSettingsAccountConnected(_ provider: BroadcastSettingsProvider) -> Bool {
        if provider == .youtube { return connection != nil && connection?.requiresReconnection == false }
        return streamingAccounts.contains { $0.provider == provider.rawValue && !$0.reconnectRequired }
    }

    func hasAcknowledgedTransmission(for provider: BroadcastSettingsProvider) -> Bool {
        switch provider {
        case .youtube: hasAcknowledgedYouTubeTransmission
        case .chzzk: hasAcknowledgedCHZZKTransmission
        }
    }

    func acknowledgeTransmission(_ consent: SignupConsent, for provider: BroadcastSettingsProvider) -> Bool {
        guard consent.isAccepted else { return false }
        switch provider {
        case .youtube:
            consentStore.recordYouTubeTransmission()
            hasAcknowledgedYouTubeTransmission = true
        case .chzzk:
            consentStore.recordCHZZKTransmission()
            hasAcknowledgedCHZZKTransmission = true
        }
        return true
    }

    func acknowledgeYouTubeTransmission(_ consent: SignupConsent) -> Bool {
        acknowledgeTransmission(consent, for: .youtube)
    }

    func clearPersistedYouTubeConnection() {
        preferencesStore.removeConnection()
        consentStore.clearYouTubeTransmission()
        hasAcknowledgedYouTubeTransmission = false
    }

    func clearCHZZKTransmissionAcknowledgement() {
        consentStore.clearCHZZKTransmission()
        hasAcknowledgedCHZZKTransmission = false
    }

    var isYouTubeConnectionOperationInProgress: Bool {
        isConnecting || isRefreshingConnection || isDisconnecting || isConnectingCHZZK || isDisconnectingCHZZK
    }

    var isYouTubeAccountChangeBlocked: Bool {
        isBroadcastSettingsLocked || isChangingBroadcastMode || isHandlingUpgradeOffer
            || isSwitchingToOnDevice || isYouTubeConnectionOperationInProgress
    }

    var canDisconnectYouTubeAccount: Bool {
        connection != nil && !isYouTubeAccountChangeBlocked
    }

    var chzzkAccount: StreamingAccountSummary? {
        streamingAccounts.first { $0.provider == "chzzk" }
    }

    var isCHZZKAccountChangeBlocked: Bool {
        isChangingBroadcastMode || isHandlingUpgradeOffer || isSwitchingToOnDevice || isYouTubeConnectionOperationInProgress || isPreparingSession || isChangingStreamState
            || isEndingSession || preparationStatus?.isRunning == true
            || preparationStatus?.phase == .cancelling
            || StreamingAccountPolicy.isInUse("chzzk", snapshot: responseState)
    }

    var canDisconnectCHZZKAccount: Bool { chzzkAccount != nil && !isCHZZKAccountChangeBlocked }

    func connectCHZZK(presenting viewController: UIViewController, accessToken: String?) async {
        guard !isCHZZKAccountChangeBlocked else { return }
        chzzkAccountMessage = nil
        guard let accessToken, !accessToken.isEmpty else {
            chzzkAccountMessage = YouTubeAPIError.unauthorized.userMessage
            return
        }
        let generation = beginConnectionOperation()
        isConnectingCHZZK = true
        defer { if isCurrentConnectionOperation(generation) { isConnectingCHZZK = false } }
        do {
            let attempt = try CHZZKAuthorizationAttempt.random()
            let configuration = try await api.chzzkConfiguration(state: attempt.state)
            guard isCurrentConnectionOperation(generation) else { return }
            let authorization = try await chzzkAuthorization.authorize(
                configuration: configuration, attempt: attempt, presenting: viewController
            )
            guard isCurrentConnectionOperation(generation), !Task.isCancelled else { return }
            _ = try attempt.remaining()
            guard authorization.state == attempt.state else { throw CHZZKAuthorizationError.stateMismatch }
            let response = try await api.connectCHZZK(authorization: authorization, accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }
            streamingAccounts.removeAll { $0.provider == "chzzk" }
            streamingAccounts.append(response.account)
            // 서버 응답을 반영한 뒤 전체 목록을 다시 읽어 YouTube도 함께 동기화한다.
            await synchronizeAccountsAfterCHZZKChange(accessToken: accessToken, generation: generation)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            chzzkAccountMessage = chzzkMessage(for: error)
        }
    }

    func disconnectCHZZKAccount(accessToken: String?) async {
        guard canDisconnectCHZZKAccount else { return }
        chzzkAccountMessage = nil
        guard let accessToken, !accessToken.isEmpty else {
            chzzkAccountMessage = YouTubeAPIError.unauthorized.userMessage
            return
        }
        let generation = beginConnectionOperation()
        isDisconnectingCHZZK = true
        defer { if isCurrentConnectionOperation(generation) { isDisconnectingCHZZK = false } }
        do {
            try await api.disconnectStreamingAccount(accessToken: accessToken, provider: "chzzk")
            guard isCurrentConnectionOperation(generation) else { return }
            streamingAccounts.removeAll { $0.provider == "chzzk" }
            await synchronizeAccountsAfterCHZZKChange(accessToken: accessToken, generation: generation)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            chzzkAccountMessage = chzzkMessage(for: error)
        }
    }

    private func synchronizeAccountsAfterCHZZKChange(accessToken: String, generation: UInt) async {
        do {
            let accounts = try await api.streamingAccounts(accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }
            applyStreamingAccounts(accounts)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            chzzkAccountMessage = chzzkMessage(for: error)
        }
    }

    private func applyStreamingAccounts(_ accounts: [StreamingAccountSummary]) {
        streamingAccounts = accounts
        let selected = accounts.first { $0.provider == "youtube" && $0.accountID == selectedYouTubeAccountID }
            ?? accounts.first { $0.provider == "youtube" }
        selectedYouTubeAccountID = selected?.accountID
        if let connection = selected?.youtubeConnection {
            self.connection = connection
            persistConnection()
        } else {
            connection = nil
            clearPersistedYouTubeConnection()
        }
        if !accounts.contains(where: { $0.provider == "chzzk" }) {
            clearCHZZKTransmissionAcknowledgement()
        }
    }

    private func chzzkMessage(for error: Error) -> String {
        if let error = error as? CHZZKAuthorizationError { return error.userMessage }
        if let error = error as? YouTubeAPIError {
            if error == .unauthorized { return error.userMessage }
            if case let .api(code, _, _) = error {
                switch code {
                case "unauthorized": return YouTubeAPIError.unauthorized.userMessage
                case "invalid_auth_code": return String(localized: "치지직 인가 코드가 만료되었거나 유효하지 않습니다. 다시 연결해 주세요.", table: "CHZZK")
                case "streaming_account_in_use": return String(localized: "치지직 방송을 종료한 뒤 연결을 해제해 주세요.", table: "CHZZK")
                case "chzzk_scope_missing": return String(localized: "치지직 연결에 필요한 권한을 모두 허용해 주세요.", table: "CHZZK")
                case "chzzk_channel_missing": return String(localized: "이 치지직 계정에 채널이 없습니다. 채널을 먼저 만들어 주세요.", table: "CHZZK")
                case "streaming_reconnect_required": return String(localized: "치지직 계정을 다시 연결해 주세요.", table: "CHZZK")
                default: break
                }
            }
        }
        return String(localized: "치지직 계정 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", table: "CHZZK")
    }

    func refreshAvailability() async {
        do {
            _ = try await api.configuration()
            isFeatureAvailable = true
        } catch let error as YouTubeAPIError where error == .featureUnavailable {
            isFeatureAvailable = false
        } catch {
            // 서버가 일시적으로 응답하지 않아도 버튼을 숨기지 않고, 사용자가 다시 시도할 수 있게 둔다.
        }
    }

    func refreshConnection(accessToken: String?) async {
        guard !isYouTubeConnectionOperationInProgress,
              let accessToken,
              !accessToken.isEmpty else {
            return
        }

        let generation = beginConnectionOperation()
        isRefreshingConnection = true
        defer {
            if connectionOperationGeneration == generation {
                isRefreshingConnection = false
            }
        }

        do {
            let accounts = try await api.streamingAccounts(accessToken: accessToken)
            guard isCurrentConnectionOperation(generation) else { return }

            applyStreamingAccounts(accounts)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            // Keep the last known connection when the refresh is unavailable.
            handle(error)
        }
    }

    func connect(presenting viewController: UIViewController, accessToken: String?) async {
        guard !isYouTubeAccountChangeBlocked else { return }
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }

        let generation = beginConnectionOperation()
        isConnecting = true
        defer {
            if connectionOperationGeneration == generation {
                isConnecting = false
            }
        }
        do {
            let configuration = try await api.configuration()
            guard isCurrentConnectionOperation(generation) else { return }
            let serverAuthCode = try await authorization.authorize(
                configuration: configuration,
                presenting: viewController
            )
            guard isCurrentConnectionOperation(generation) else { return }
            try await connectAuthorizedYouTubeAccount(serverAuthCode: serverAuthCode, accessToken: accessToken)
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            handle(error)
        }
    }

    // Keep the previous selection until the full account list confirms its connection ID.
    func connectAuthorizedYouTubeAccount(serverAuthCode: String, accessToken: String) async throws {
        let generation = connectionOperationGeneration
        _ = try await api.connect(serverAuthCode: serverAuthCode, accessToken: accessToken)
        guard isCurrentConnectionOperation(generation) else { return }
        let accounts = try await api.streamingAccounts(accessToken: accessToken)
        guard isCurrentConnectionOperation(generation) else { return }
        applyStreamingAccounts(accounts)
    }

    func disconnectYouTubeAccount(accessToken: String?, accountID: String? = nil) async {
        let account = youtubeAccounts.first { $0.accountID == (accountID ?? selectedYouTubeAccountID) }
        if accountID != nil && account == nil { return }
        guard account.map(canDisconnectYouTubeChannel) ?? canDisconnectYouTubeAccount else { return }
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }

        let generation = beginConnectionOperation()
        isDisconnecting = true
        defer {
            if connectionOperationGeneration == generation {
                isDisconnecting = false
            }
        }

        do {
            try await api.disconnectStreamingAccount(accessToken: accessToken, accountID: account?.accountID)
            guard isCurrentConnectionOperation(generation) else { return }
            applyStreamingAccounts(streamingAccounts.filter { $0.provider != "youtube" || $0.accountID != account?.accountID })
        } catch {
            guard isCurrentConnectionOperation(generation) else { return }
            // A failed DELETE does not prove that the server removed the link.
            handle(error)
        }
    }
}
