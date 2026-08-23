import Combine
import Foundation
import UIKit

@MainActor
final class YouTubeIntegration: ObservableObject {
    @Published private(set) var connection: YouTubeConnection?
    @Published private(set) var session: YouTubeBroadcastSession?
    @Published private(set) var stream: YouTubeStreamState?
    @Published private(set) var videoTrack: YouTubeVideoTrackState?
    @Published private(set) var isFeatureAvailable = true
    @Published private(set) var isConnecting = false
    @Published private(set) var isPreparingSession = false
    @Published private(set) var isConnectingVideo = false
    @Published private(set) var isChangingStreamState = false
    @Published private(set) var isRecoveringVideoFailure = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var helpURL: URL?

    let videoUplink = WebRTCVideoUplink()

    private let api = YouTubeAPI()
    private let authorization = YouTubeAuthorization()
    private var pollingTask: Task<Void, Never>?
    private var pollingGeneration = 0
    private var streamStartFallback: Date?
    private var reconnectTask: Task<Void, Never>?
    private var isReconnectingVideo = false
    private var videoConnectionConfiguration: VideoConnectionConfiguration?
    private let maximumVideoReconnectAttempts = 3

    init() {
        connection = Self.loadConnection()
        videoUplink.onConnectionInterrupted = { [weak self] in
            self?.reconnectVideoUsingExistingSession()
        }
    }

    func configureAuthentication(_ authentication: AuthSession) {
        api.configureAuthentication(
            accessTokenProvider: { [weak authentication] in
                authentication?.currentAccessToken()
            },
            refreshSession: { [weak authentication] in
                guard let authentication else { return .invalid }
                return await authentication.refreshSession()
            },
            onInvalidRefresh: { [weak self, weak authentication] in
                self?.reset()
                authentication?.expireSession()
            }
        )
    }

    var isConnected: Bool { connection != nil }
    // 송출을 시작하기 전에는 서버의 stream.publisher_active가 항상 false다.
    // 카메라 업링크 준비 여부는 세션 media.raw_video_track으로 판단한다.
    var isVideoConnected: Bool { videoUplink.state == .connected && videoTrack?.readyState == "live" }

    var streamStartedAt: Date? {
        stream?.startedAtDate ?? streamStartFallback
    }

    var isYouTubeBroadcastActive: Bool {
        streamStartedAt != nil && stream?.status != "stopped"
    }

    // 시작 요청 직후에는 서버가 egress 출력 형식을 확정할 때까지 started_at이 비어 있다.
    // 이 구간은 실제 송출 전 준비 상태이므로 버튼의 방송 중 UI와 분리한다.
    var hasStartedYouTubeBroadcast: Bool {
        stream?.startedAtDate != nil && stream?.status != "stopped"
    }

    var isWaitingForYouTubeBroadcastStart: Bool {
        streamStartFallback != nil
            && stream?.startedAtDate == nil
            && stream?.status != "stopped"
    }

    var isYouTubeBroadcastPaused: Bool {
        switch stream?.status {
        case "paused", "paused_reconfiguring", "paused_reconnecting":
            return true
        default:
            return false
        }
    }

    var canPauseYouTubeBroadcast: Bool {
        switch stream?.status {
        case "streaming", "reconfiguring":
            return true
        default:
            return false
        }
    }

    var canResumeYouTubeBroadcast: Bool {
        stream?.status == "paused"
    }

    var canChangeYouTubePauseState: Bool {
        canPauseYouTubeBroadcast || canResumeYouTubeBroadcast
    }

    var streamStatusText: String {
        switch stream?.status {
        case "streaming": return "YouTube 송출 중"
        case "reconnecting": return "YouTube 재연결 중…"
        case "paused": return pausedStatusText(prefix: "YouTube 송출 일시 중지됨")
        case "paused_reconfiguring": return pausedStatusText(prefix: "YouTube 일시 중지 준비 중…")
        case "paused_reconnecting": return pausedStatusText(prefix: "YouTube 일시 중지 화면 재연결 중…")
        case "idle": return "YouTube 준비 중…"
        case "stopped": return "YouTube 송출 중지됨"
        default: return "YouTube 송출 대기"
        }
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

    func connect(presenting viewController: UIViewController, accessToken: String?) async {
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }

        isConnecting = true
        defer { isConnecting = false }
        do {
            let configuration = try await api.configuration()
            let serverAuthCode = try await authorization.authorize(
                configuration: configuration,
                presenting: viewController
            )
            let response = try await api.connect(serverAuthCode: serverAuthCode, accessToken: accessToken)
            connection = YouTubeConnection(provider: response.provider, channel: response.channel)
            persistConnection()
        } catch {
            handle(error)
        }
    }

    func prepareSession(accessToken: String?) async -> Bool {
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return false
        }
        if session != nil { return true }

        isPreparingSession = true
        defer { isPreparingSession = false }
        do {
            session = try await api.createSession(accessToken: accessToken)
            stream = session?.stream
            return true
        } catch {
            handle(error)
            return false
        }
    }

    func connectVideo(
        accessToken: String?,
        preferredCameraID: String?,
        preferredAudioID: String?,
        preferredVideoQuality: CameraQualityPreset
    ) async -> Bool {
        clearError()
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return false
        }
        guard let session else {
            showError(.sessionRequired)
            return false
        }
        guard let serverURL = AuthenticationConfiguration.serverURL(path: "/") else {
            showError(.configuration)
            return false
        }

        isConnectingVideo = true
        defer { isConnectingVideo = false }
        var currentAccessToken = accessToken
        var shouldRetryAfterRefresh = true
        var terminalError: Error?

        while true {
            do {
                let iceServers = try await api.webrtcConfiguration(accessToken: currentAccessToken)
                let refreshedAccessToken = api.currentAccessToken(fallback: currentAccessToken)
                try await videoUplink.start(
                    session: WebRTCSessionCredentials(sessionID: session.sessionID, ownerToken: session.ownerToken),
                    accessToken: refreshedAccessToken,
                    serverURL: serverURL,
                    iceServers: iceServers,
                    preferredCameraID: preferredCameraID,
                    preferredAudioID: preferredAudioID,
                    preferredVideoQuality: preferredVideoQuality
                )
                let isReady = try await waitForVideoTrack(session: session, accessToken: refreshedAccessToken)
                guard isReady, videoUplink.state == .connected else {
                    throw WebRTCVideoUplinkError.failed(
                        videoUplink.errorMessage ?? "카메라 영상 연결이 끊겼습니다. 다시 시작해 주세요."
                    )
                }
                videoConnectionConfiguration = VideoConnectionConfiguration(
                    preferredCameraID: preferredCameraID,
                    preferredAudioID: preferredAudioID,
                    preferredVideoQuality: preferredVideoQuality
                )
                return true
            } catch WebRTCVideoUplinkError.unauthorized where shouldRetryAfterRefresh {
                shouldRetryAfterRefresh = false
                await videoUplink.stopAndWait()

                guard await api.refreshAuthentication() == .refreshed else {
                    terminalError = WebRTCVideoUplinkError.unauthorized
                    break
                }
                currentAccessToken = api.currentAccessToken(fallback: currentAccessToken)
            } catch {
                terminalError = error
                break
            }
        }

        await videoUplink.stopAndWait()
        videoConnectionConfiguration = nil
        self.session = nil
        self.stream = nil
        self.streamStartFallback = nil
        self.videoTrack = nil
        handle(terminalError ?? WebRTCVideoUplinkError.failed("카메라 영상 연결을 완료하지 못했습니다."))
        return false
    }

    func switchCamera(to cameraID: String) async -> Bool {
        clearError()
        do {
            try await videoUplink.switchCamera(to: cameraID)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? "카메라를 전환하지 못했습니다. 다시 시도해 주세요."
            return false
        }
    }

    func switchAudioInput(to audioInputID: String) -> Bool {
        clearError()
        do {
            try videoUplink.switchAudioInput(to: audioInputID)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? "오디오 기기를 전환하지 못했습니다. 다시 시도해 주세요."
            return false
        }
    }

    func switchVideoQuality(to quality: CameraQualityPreset) async -> Bool {
        clearError()
        do {
            try await videoUplink.switchVideoQuality(to: quality)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? "화질을 변경하지 못했습니다. 다시 시도해 주세요."
            return false
        }
    }

    func endBroadcast(accessToken: String?) async {
        if isYouTubeBroadcastActive {
            await stopYouTubeStream(accessToken: accessToken)
        }
        await videoUplink.stopAndWait()
        reconnectTask?.cancel()
        reconnectTask = nil
        videoConnectionConfiguration = nil
        stopPolling()
        session = nil
        stream = nil
        streamStartFallback = nil
        videoTrack = nil
    }

    func recoverFromVideoUplinkFailure(accessToken: String?) async {
        guard !isRecoveringVideoFailure else { return }
        isRecoveringVideoFailure = true
        defer { isRecoveringVideoFailure = false }

        let failureMessage = videoUplink.errorMessage
            ?? "카메라 영상 연결이 끊겼습니다. 비식별화를 다시 시작해 주세요."
        let failedSession = session
        let shouldStopYouTube = isYouTubeBroadcastActive

        await videoUplink.stopAndWait()
        reconnectTask?.cancel()
        reconnectTask = nil
        videoConnectionConfiguration = nil
        stopPolling()
        session = nil
        stream = nil
        streamStartFallback = nil
        videoTrack = nil
        errorMessage = failureMessage
        helpURL = nil

        if shouldStopYouTube,
           let accessToken,
           !accessToken.isEmpty,
           let failedSession {
            Task { [api] in
                _ = try? await api.stopStream(session: failedSession, accessToken: accessToken)
            }
        }
    }

    func startYouTubeStream(accessToken: String?) async {
        clearError()
        guard !isChangingStreamState else { return }
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }
        guard connection != nil else {
            showError(.streamingNotConnected)
            return
        }
        guard isVideoConnected else {
            showError(.videoNotConnected)
            return
        }
        guard let session else {
            showError(.sessionRequired)
            return
        }

        isChangingStreamState = true
        defer { isChangingStreamState = false }
        do {
            let startedStream = try await api.startStream(session: session, accessToken: accessToken)
            streamStartFallback = startedStream.startedAtDate ?? Date()
            stream = startedStream
            beginPolling(accessToken: accessToken)
        } catch {
            handle(error)
        }
    }

    func stopYouTubeStream(accessToken: String?) async {
        clearError()
        guard !isChangingStreamState else { return }
        guard let accessToken, !accessToken.isEmpty,
              let session else {
            return
        }

        isChangingStreamState = true
        defer { isChangingStreamState = false }
        do {
            let stoppedStream = try await api.stopStream(session: session, accessToken: accessToken)
            // YouTube 종료 반영에는 시간이 걸릴 수 있다. API 요청이 성공한 순간부터
            // 앱에서는 송출을 종료로 처리해 타이머와 비식별화 제어 상태를 즉시 복구한다.
            stream = stoppedStream.markedStoppedByUser()
            streamStartFallback = nil
            stopPolling()
        } catch {
            handle(error)
        }
    }

    func pauseYouTubeStream(accessToken: String?) async {
        await changePausedState(accessToken: accessToken, shouldPause: true)
    }

    func resumeYouTubeStream(accessToken: String?) async {
        await changePausedState(accessToken: accessToken, shouldPause: false)
    }

    func reset() {
        stopPolling()
        reconnectTask?.cancel()
        reconnectTask = nil
        videoUplink.stop()
        videoConnectionConfiguration = nil
        connection = nil
        session = nil
        stream = nil
        streamStartFallback = nil
        videoTrack = nil
        errorMessage = nil
        helpURL = nil
        UserDefaults.standard.removeObject(forKey: Self.connectionStorageKey)
    }

    func dismissError() {
        clearError()
    }

    private func beginPolling(accessToken: String) {
        stopPolling()
        let generation = pollingGeneration
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(3))
                } catch {
                    break
                }
                guard let self,
                      let session = self.session,
                      self.isYouTubeBroadcastActive,
                      !Task.isCancelled,
                      self.pollingGeneration == generation else {
                    break
                }
                do {
                    let snapshot = try await self.api.sessionStatus(session: session, accessToken: accessToken)
                    guard !Task.isCancelled,
                          self.pollingGeneration == generation,
                          self.session?.sessionID == session.sessionID else {
                        break
                    }
                    self.stream = snapshot.stream
                    self.videoTrack = snapshot.media.rawVideoTrack
                    if snapshot.stream.status == "stopped" {
                        self.streamStartFallback = nil
                    }
                } catch {
                    guard !Task.isCancelled, self.pollingGeneration == generation else {
                        break
                    }
                    // 폴링 실패는 이미 시작된 송출 상태를 지우지 않는다. 다음 주기에 재시도한다.
                }
            }
        }
    }

    private func stopPolling() {
        pollingGeneration &+= 1
        pollingTask?.cancel()
        pollingTask = nil
    }

    private func waitForVideoTrack(session: YouTubeBroadcastSession, accessToken: String) async throws -> Bool {
        for _ in 0..<15 {
            guard videoUplink.state == .connected else {
                throw WebRTCVideoUplinkError.failed(
                    videoUplink.errorMessage ?? "카메라 영상 연결이 끊겼습니다. 다시 시작해 주세요."
                )
            }
            let snapshot = try await api.sessionStatus(session: session, accessToken: accessToken)
            stream = snapshot.stream
            videoTrack = snapshot.media.rawVideoTrack
            if snapshot.media.rawVideoTrack?.readyState == "live" {
                guard videoUplink.state == .connected else {
                    throw WebRTCVideoUplinkError.failed(
                        videoUplink.errorMessage ?? "카메라 영상 연결이 끊겼습니다. 다시 시작해 주세요."
                    )
                }
                videoUplink.markPublisherReady()
                return true
            }
            try await Task.sleep(for: .seconds(1))
        }
        throw YouTubeAPIError.videoTrackUnavailable
    }

    private func reconnectVideoUsingExistingSession() {
        guard !isReconnectingVideo,
              reconnectTask == nil,
              let session,
              let configuration = videoConnectionConfiguration,
              let serverURL = AuthenticationConfiguration.serverURL(path: "/") else {
            return
        }

        isReconnectingVideo = true
        reconnectTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                self.isReconnectingVideo = false
                self.reconnectTask = nil
            }

            for attempt in 1...self.maximumVideoReconnectAttempts {
                guard !Task.isCancelled else { return }
                if attempt > 1 {
                    try? await Task.sleep(for: .seconds(2))
                }

                do {
                    let accessToken = self.api.currentAccessToken(fallback: self.videoUplink.accessToken ?? "")
                    guard !accessToken.isEmpty else {
                        throw WebRTCVideoUplinkError.unauthorized
                    }
                    let iceServers = try await self.api.webrtcConfiguration(accessToken: accessToken)
                    guard !Task.isCancelled else { return }
                    await self.videoUplink.stopAndWait()
                    guard !Task.isCancelled else { return }
                    try await self.videoUplink.start(
                        session: WebRTCSessionCredentials(
                            sessionID: session.sessionID,
                            ownerToken: session.ownerToken
                        ),
                        accessToken: self.api.currentAccessToken(fallback: accessToken),
                        serverURL: serverURL,
                        iceServers: iceServers,
                        preferredCameraID: configuration.preferredCameraID,
                        preferredAudioID: configuration.preferredAudioID,
                        preferredVideoQuality: configuration.preferredVideoQuality
                    )
                    guard !Task.isCancelled else { return }
                    guard try await self.waitForVideoTrack(session: session, accessToken: accessToken) else {
                        throw WebRTCVideoUplinkError.failed("카메라 영상 연결을 복구하지 못했습니다.")
                    }
                    self.videoUplink.markPublisherReady()
                    return
                } catch {
                    await self.videoUplink.stopAndWait()
                }
            }

            self.videoUplink.markReconnectFailed("네트워크 연결을 복구하지 못했습니다. 비식별화를 다시 시작해 주세요.")
        }
    }

    private func clearError() {
        errorMessage = nil
        helpURL = nil
        videoUplink.dismissError()
    }

    private func changePausedState(accessToken: String?, shouldPause: Bool) async {
        clearError()
        guard !isChangingStreamState else { return }
        guard let accessToken, !accessToken.isEmpty else {
            showError(.unauthorized)
            return
        }
        guard let session, isYouTubeBroadcastActive else {
            showError(.api(code: "stream_not_active", fallback: "YouTube 송출 중이 아닙니다.", helpURL: nil))
            return
        }
        guard shouldPause ? canPauseYouTubeBroadcast : canResumeYouTubeBroadcast else {
            return
        }

        isChangingStreamState = true
        defer { isChangingStreamState = false }
        do {
            stream = try await (shouldPause
                ? api.pauseStream(session: session, accessToken: accessToken)
                : api.resumeStream(session: session, accessToken: accessToken))
            // 일시 중지와 재개는 RTMP egress만 전환한다. WebRTC 업링크와 세션은 유지한다.
            beginPolling(accessToken: accessToken)
        } catch {
            handle(error)
        }
    }

    private func pausedStatusText(prefix: String) -> String {
        guard let pausedAt = stream?.pausedAtDate else { return prefix }
        return "\(prefix) (\(pausedAt.formatted(date: .omitted, time: .shortened)))"
    }

    private func handle(_ error: Error) {
        if let error = error as? YouTubeAPIError {
            if error == .featureUnavailable { isFeatureAvailable = false }
            showError(error)
            return
        }
        if let uplinkError = videoUplink.errorMessage {
            errorMessage = uplinkError
            return
        }
        errorMessage = "YouTube 연결을 완료하지 못했습니다. 다시 시도해 주세요."
    }

    private func showError(_ error: YouTubeAPIError) {
        errorMessage = error.userMessage
        helpURL = error.helpURL
    }

    private static let connectionStorageKey = "com.framework.innolive.youtube.connection"

    private static func loadConnection() -> YouTubeConnection? {
        guard let data = UserDefaults.standard.data(forKey: connectionStorageKey) else { return nil }
        return try? JSONDecoder().decode(YouTubeConnection.self, from: data)
    }

    private func persistConnection() {
        guard let connection,
              let data = try? JSONEncoder().encode(connection) else { return }
        UserDefaults.standard.set(data, forKey: Self.connectionStorageKey)
    }
}

private struct VideoConnectionConfiguration {
    let preferredCameraID: String?
    let preferredAudioID: String?
    let preferredVideoQuality: CameraQualityPreset
}
