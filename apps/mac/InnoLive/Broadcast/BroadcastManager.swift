//
//  BroadcastManager.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import Combine
import AppKit
import Foundation

@MainActor
final class BroadcastManager: ObservableObject {
    @Published var mode: BroadcastMode = BroadcastDefaults.mode {
        didSet { saveSettings() }
    }
    @Published var state: BroadcastState = .idle
    @Published var streamTitle = "InnoLive" {
        didSet { saveSettings() }
    }
    @Published var broadcasterID = "host" {
        didSet { saveSettings() }
    }
    @Published var signalingURLString = BroadcastDefaults.signalingURLString {
        didSet { saveSettings() }
    }
    @Published var processedVideoURLString = BroadcastDefaults.processedVideoURLString {
        didSet { saveSettings() }
    }
    @Published var automaticallyReconnect = true {
        didSet { saveSettings() }
    }
    @Published var enableWebRTCMediaUplink: Bool {
        didSet { saveSettings() }
    }
    @Published var destinations: [StreamDestination] = [
        StreamDestination(platform: .youtube, isEnabled: true),
        StreamDestination(platform: .twitch, isEnabled: false),
        StreamDestination(platform: .customRTMP, isEnabled: false)
    ] {
        didSet { saveSettings() }
    }
    @Published private(set) var metrics = BroadcastMetrics()
    @Published private(set) var statusText = "방송 대기"
    @Published private(set) var lastErrorMessage: String?
    @Published private(set) var startedAt: Date?
    @Published private(set) var sessionID = UUID().uuidString
    @Published private(set) var mediaUplinkState: WebRTCMediaUplinkState = .idle
    @Published private(set) var mediaUplinkStatusText = "WebRTC 업링크 대기"

    private let signalingClient = WebRTCSignalingClient()
    private let mediaUplinkClient: WebRTCMediaUplinkClient?
    private let userDefaults: UserDefaults
    private let settingsKey = "BroadcastSettings.v1"
    private var metricsTimer: Timer?
    private var heartbeatTimer: Timer?
    private var connectionTimeoutTimer: Timer?
    private var connectionTestTimeoutTimer: Timer?
    private var reconnectTimer: Timer?
    private var serverSessionTask: Task<Void, Never>?
    private var isRestoringSettings = false
    private var isTestingConnection = false
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 3
    private var mediaUplinkCameraName: String?

    init(userDefaults: UserDefaults = .standard, enableMediaUplink: Bool = false) {
        self.userDefaults = userDefaults
        self.enableWebRTCMediaUplink = enableMediaUplink
        self.mediaUplinkClient = enableMediaUplink ? WebRTCMediaUplinkClient() : nil
        restoreSettings()

        signalingClient.onMessage = { [weak self] message in
            Task { @MainActor in
                self?.handleServerMessage(message)
            }
        }
        signalingClient.onDisconnect = { [weak self] error in
            Task { @MainActor in
                self?.handleServerDisconnect(error)
            }
        }

        mediaUplinkClient?.onOffer = { [weak self] sdp in
            Task { @MainActor in
                self?.sendMediaUplinkSignal(.offer(sdp))
            }
        }
        mediaUplinkClient?.onIceCandidate = { [weak self] signal in
            Task { @MainActor in
                self?.sendMediaUplinkSignal(signal)
            }
        }
        mediaUplinkClient?.onStateChanged = { [weak self] uplinkState, statusText in
            Task { @MainActor in
                guard let self else {
                    return
                }

                self.mediaUplinkState = uplinkState
                self.mediaUplinkStatusText = statusText

                if uplinkState == .failed, self.state == .connecting {
                    self.handleServerStartFailure(statusText)
                }
            }
        }
    }

    var isActive: Bool {
        state == .connecting || state == .live || state == .stopping
    }

    var isConnected: Bool {
        mode == .mockLocal ? state == .live : signalingClient.isConnected
    }

    var enabledDestinations: [StreamDestination] {
        destinations.filter(\.isEnabled)
    }

    var processedVideoURL: URL? {
        guard !processedVideoURLString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return URL(string: processedVideoURLString)
    }

    func serverEndpointURL(path: String) -> URL? {
        serverURL(path: path)
    }

    var responseBadge: String {
        if usesNativeWebRTCSignaling {
            return mediaUplinkState == .connected ? "WebRTC" : "Ready"
        }
        if processedVideoURL != nil {
            return state == .live ? "Server" : "Ready"
        }
        return "Delay 3s"
    }

    var responseSubtitle: String {
        if usesNativeWebRTCSignaling {
            return mediaUplinkState == .connected ? "서버 WebRTC 처리 영상을 수신 중" : "서버 WebRTC 처리 영상 대기 중"
        }
        if processedVideoURL != nil {
            return state == .live ? "서버 처리 영상을 수신 중" : "서버 처리 영상 주소 준비됨"
        }
        return "서버 미연결 상태에서는 카메라 프레임을 3초 지연 표시"
    }

    var hasWebRTCResponsePreview: Bool {
        usesNativeWebRTCSignaling
    }

    func attachWebRTCResponsePreview(to containerView: NSView) {
        mediaUplinkClient?.attachPreview(to: containerView)
    }

    func detachWebRTCResponsePreview(from containerView: NSView) {
        mediaUplinkClient?.detachPreview(from: containerView)
    }

    func toggle(cameraManager: CameraManager) {
        if isActive {
            stop()
        } else {
            start(cameraManager: cameraManager)
        }
    }

    func start(cameraManager: CameraManager) {
        guard !isActive else {
            return
        }

        mediaUplinkCameraName = cameraManager.selectedCameraName

        if !cameraManager.isRunning {
            cameraManager.start()
        }

        lastErrorMessage = nil
        sessionID = UUID().uuidString
        reconnectAttempts = 0
        state = .connecting
//        statusText = "방송 연결 준비 중"

        switch mode {
        case .mockLocal:
            startMockBroadcast()
        case .serverBridge:
            startServerBridge()
        }
    }

    func updateMediaUplinkCamera(name: String?) {
        mediaUplinkCameraName = name

        guard usesNativeWebRTCSignaling,
              state == .connecting || state == .live else {
            return
        }

        mediaUplinkClient?.switchCamera(preferredVideoDeviceName: name)
    }

    func stop() {
        guard isActive else {
            return
        }

        state = .stopping
//        statusText = "방송 중지 중"
        stopTimers()

        switch mode {
        case .mockLocal:
            finishStopped()
        case .serverBridge:
            let stoppedSessionID = sessionID

            if usesNativeWebRTCSignaling {
                finishStopped()
                deleteServerSessionInBackground(stoppedSessionID)
                return
            }

            signalingClient.sendStop(sessionID: stoppedSessionID) { [weak self] _ in
                guard let self else {
                    return
                }

                self.signalingClient.disconnect()
                self.finishStopped()
                self.deleteServerSessionInBackground(stoppedSessionID)
            }
        }
    }

    func testServerConnection() {
        guard let url = signalingURL else {
            state = .failed
//            lastErrorMessage = "서버 주소를 확인해 주세요."
//            statusText = "서버 주소가 올바르지 않습니다."
            return
        }

        if BroadcastDefaults.requiresOfferSignaling(signalingURLString) {
            state = .connecting
//            statusText = "서버 세션 API 테스트 중"
            lastErrorMessage = nil
            isTestingConnection = true

            serverSessionTask?.cancel()
            serverSessionTask = Task { @MainActor [weak self] in
                guard let self else {
                    return
                }

                do {
                    let serverSessionID = try await self.createServerSession()
                    guard !Task.isCancelled else {
                        return
                    }

                    self.deleteServerSessionInBackground(serverSessionID)
                    self.finishConnectionTest(success: true, message: "")
                    self.serverSessionTask = nil
                } catch {
                    guard !Task.isCancelled else {
                        return
                    }

                    self.finishConnectionTest(success: false, message: error.localizedDescription)
                    self.serverSessionTask = nil
                }
            }
            return
        }

        state = .connecting
//        statusText = "서버 연결 테스트 중"
        lastErrorMessage = nil
        isTestingConnection = true
        signalingClient.connect(to: url)

        let message = BroadcastControlMessage(
            type: "ping",
            sessionID: sessionID,
            title: streamTitle,
            broadcasterID: broadcasterID,
            mosaicPolicy: mosaicPolicy,
            destinations: destinationPayloads,
            timestamp: Date()
        )

        signalingClient.send(message) { [weak self] error in
            guard let self else {
                return
            }

            if let error {
                self.finishConnectionTest(success: false, message: error.localizedDescription)
                return
            }

//            self.statusText = "서버 응답 대기 중"
            self.startConnectionTestTimeout()
        }
    }

    func resetMetrics() {
        metrics = BroadcastMetrics()
    }

    func durationText(now: Date = Date()) -> String {
        guard let startedAt else {
            return "00:00:00"
        }

        let seconds = max(Int(now.timeIntervalSince(startedAt)), 0)
        let hours = seconds / 3_600
        let minutes = (seconds % 3_600) / 60
        let remainingSeconds = seconds % 60
        return String(format: "%02d:%02d:%02d", hours, minutes, remainingSeconds)
    }

    private var signalingURL: URL? {
        URL(string: signalingURLString.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private var mosaicPolicy: String {
        "mosaic_all_faces_except_broadcaster"
    }

    private var destinationPayloads: [BroadcastControlMessage.DestinationPayload] {
        enabledDestinations.map {
            BroadcastControlMessage.DestinationPayload(
                platform: $0.platform,
                ingestURL: $0.ingestURL,
                streamKey: $0.streamKey
            )
        }
    }

    private func startMockBroadcast() {
        statusText = "로컬 테스트 방송 연결 중"

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            guard let self, self.state == .connecting else {
                return
            }

            self.state = .live
            self.startedAt = Date()
            self.statusText = "로컬 테스트 방송 중"
            self.metrics = BroadcastMetrics(
                bitrateKbps: 4_500,
                latencyMilliseconds: 3_000,
                framesPerSecond: 30,
                droppedFrames: 0,
                viewerCount: 0
            )
            self.startMetricsTimer(isMock: true)
        }
    }

    private func startServerBridge() {
        guard let url = signalingURL else {
            state = .failed
            lastErrorMessage = "서버 주소를 확인해 주세요."
            statusText = "서버 주소가 올바르지 않습니다."
            return
        }

        if BroadcastDefaults.requiresOfferSignaling(signalingURLString),
           mediaUplinkClient != nil {
            enableWebRTCMediaUplink = true
        }

        if usesNativeWebRTCSignaling {
            startNativeWebRTCBridge(signalingURL: url)
            return
        }

        signalingClient.connect(to: url)

        let message = makeControlMessage(type: "startBroadcast")

        signalingClient.send(message) { [weak self] error in
            guard let self else {
                return
            }

            if let error {
                self.handleServerStartFailure(error.localizedDescription)
                return
            }

            self.statusText = "서버 방송 승인 대기 중"
            self.startConnectionTimeout()

            if self.enableWebRTCMediaUplink {
                self.startMediaUplink()
            }
        }
    }

    private var usesNativeWebRTCSignaling: Bool {
        enableWebRTCMediaUplink && mediaUplinkClient != nil
    }

    private func startNativeWebRTCBridge(signalingURL: URL) {
        statusText = "서버 WebRTC 세션 생성 중"

        serverSessionTask?.cancel()
        serverSessionTask = Task { @MainActor [weak self] in
            guard let self else {
                return
            }

            do {
                let serverSessionID = try await self.createServerSession()
                guard self.state == .connecting, !Task.isCancelled else {
                    return
                }

                self.sessionID = serverSessionID
                self.signalingClient.connect(to: signalingURL)
                self.statusText = "WebRTC offer 생성 중"
                self.startConnectionTimeout(
                    interval: 30,
                    message: "서버 WebRTC answer 시간이 초과되었습니다."
                )
                self.startMediaUplink()
            } catch {
                guard !Task.isCancelled else {
                    return
                }

                self.handleServerStartFailure(error.localizedDescription)
            }
        }
    }

    private func startMetricsTimer(isMock: Bool) {
        metricsTimer?.invalidate()
        metricsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let manager = self else {
                return
            }

            Task { @MainActor in
                if isMock {
                    manager.metrics.bitrateKbps = 4_400 + Int.random(in: -120...180)
                    manager.metrics.latencyMilliseconds = 2_850 + Int.random(in: -120...180)
                    manager.metrics.viewerCount = max(0, manager.metrics.viewerCount + Int.random(in: 0...1))
                }
            }
        }
    }

    private func startHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            guard let manager = self else {
                return
            }

            Task { @MainActor in
                guard manager.state == .live else {
                    return
                }

                let message = BroadcastControlMessage(
                    type: "heartbeat",
                    sessionID: manager.sessionID,
                    title: manager.streamTitle,
                    broadcasterID: manager.broadcasterID,
                    mosaicPolicy: manager.mosaicPolicy,
                    destinations: manager.destinationPayloads,
                    timestamp: Date()
                )
                manager.signalingClient.send(message)
            }
        }
    }

    private func handleServerMessage(_ message: BroadcastServerMessage) {
        if let error = message.error {
            failBroadcast(message: error.message)
            return
        }

        switch message.type {
        case "answer":
            guard let sdp = message.sdp else {
                failBroadcast(message: "서버 WebRTC answer에 SDP가 없습니다.")
                return
            }

            mediaUplinkClient?.acceptAnswer(sdp)
            markServerBridgeLive(message: "서버 WebRTC answer 수신")
            return
        case "ice_candidate", "candidate":
            guard let candidate = message.candidate else {
                return
            }

            mediaUplinkClient?.addRemoteCandidate(
                MediaUplinkSignal.iceCandidate(
                    candidate: candidate,
                    sdpMid: message.sdpMid,
                    sdpMLineIndex: message.sdpMLineIndex
                )
            )
            return
        case "ice_candidate_added":
            if let connectionState = message.connectionState {
                mediaUplinkStatusText = "서버 ICE 상태: \(connectionState)"
            }
            return
        default:
            break
        }

        handleMediaUplinkSignal(message.mediaUplink)

        if isTestingConnection {
            finishConnectionTest(
                success: true,
                message: message.message ?? "서버 제어 채널 응답 확인"
            )
            return
        }

        if let processedVideoURL = message.processedVideoURL, !processedVideoURL.isEmpty {
            processedVideoURLString = processedVideoURL
        }

        if let bitrateKbps = message.bitrateKbps {
            metrics.bitrateKbps = bitrateKbps
        }
        if let latencyMilliseconds = message.latencyMilliseconds {
            metrics.latencyMilliseconds = latencyMilliseconds
        }
        if let framesPerSecond = message.framesPerSecond {
            metrics.framesPerSecond = framesPerSecond
        }
        if let droppedFrames = message.droppedFrames {
            metrics.droppedFrames = droppedFrames
        }
        if let viewerCount = message.viewerCount {
            metrics.viewerCount = viewerCount
        }

        if let status = message.status {
            switch status {
            case "live":
                markServerBridgeLive(message: message.message)
            case "failed", "error":
                failBroadcast(message: message.message ?? "서버가 방송 시작을 거절했습니다.")
            case "stopped":
                finishStopped()
            default:
                break
            }
        } else if state == .connecting, message.type == "metrics" || message.type == "processedVideo" {
            markServerBridgeLive(message: message.message)
        }

        if let text = message.message {
            statusText = text
        }
    }

    private func handleServerDisconnect(_ error: Error?) {
        if isTestingConnection {
            finishConnectionTest(
                success: false,
                message: error?.localizedDescription ?? "서버 연결 테스트가 종료되었습니다."
            )
            return
        }

        guard state == .live || state == .connecting else {
            return
        }

        stopTimers()
        signalingClient.disconnect()

        let message = error?.localizedDescription ?? "서버 연결이 종료되었습니다."
        if scheduleReconnectIfNeeded(reason: message) {
            return
        }

        state = .failed
        startedAt = nil
        lastErrorMessage = message
        statusText = "서버 연결이 종료되었습니다."
    }

    private func markServerBridgeLive(message: String?) {
        guard state != .live else {
            return
        }

        connectionTimeoutTimer?.invalidate()
        connectionTimeoutTimer = nil
        reconnectAttempts = 0
        lastErrorMessage = nil
        state = .live
        startedAt = Date()
        statusText = message ?? "서버 브리지 방송 중"
        metrics = BroadcastMetrics(
            bitrateKbps: metrics.bitrateKbps,
            latencyMilliseconds: metrics.latencyMilliseconds,
            framesPerSecond: max(metrics.framesPerSecond, 30),
            droppedFrames: metrics.droppedFrames,
            viewerCount: metrics.viewerCount
        )
        startMetricsTimer(isMock: false)
        if !usesNativeWebRTCSignaling {
            startHeartbeat()
        }
    }

    private func failBroadcast(message: String) {
        stopTimers()
        signalingClient.disconnect()
        stopMediaUplink()
        reconnectAttempts = 0
        state = .failed
        startedAt = nil
        lastErrorMessage = message
        statusText = message
    }

    private func handleServerStartFailure(_ message: String) {
        let failedSessionID = sessionID
        stopTimers()
        signalingClient.disconnect()
        stopMediaUplink()
        if usesNativeWebRTCSignaling {
            deleteServerSessionInBackground(failedSessionID)
        }

        if scheduleReconnectIfNeeded(reason: message) {
            return
        }

        state = .failed
        startedAt = nil
        lastErrorMessage = message
        statusText = "서버 방송 시작 실패"
    }

    private func scheduleReconnectIfNeeded(reason: String) -> Bool {
        guard mode == .serverBridge,
              automaticallyReconnect,
              reconnectAttempts < maxReconnectAttempts else {
            return false
        }

        reconnectAttempts += 1
        state = .connecting
        startedAt = nil
        lastErrorMessage = reason
        statusText = "서버 재연결 시도 중 (\(reconnectAttempts)/\(maxReconnectAttempts))"

        reconnectTimer?.invalidate()
        reconnectTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: false) { [weak self] _ in
            guard let manager = self else {
                return
            }

            Task { @MainActor in
                guard manager.state == .connecting else {
                    return
                }

                manager.startServerBridge()
            }
        }

        return true
    }

    private func startConnectionTimeout(
        interval: TimeInterval = 10,
        message: String = "서버 방송 승인 시간이 초과되었습니다."
    ) {
        connectionTimeoutTimer?.invalidate()
        connectionTimeoutTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            guard let manager = self else {
                return
            }

            Task { @MainActor in
                guard manager.state == .connecting else {
                    return
                }

                manager.handleServerStartFailure(message)
            }
        }
    }

    private func finishConnectionTest(success: Bool, message: String) {
        connectionTestTimeoutTimer?.invalidate()
        connectionTestTimeoutTimer = nil
        isTestingConnection = false
        signalingClient.disconnect()
        reconnectAttempts = 0

        if success {
            state = .idle
            lastErrorMessage = nil
            statusText = message
        } else {
            state = .failed
            lastErrorMessage = message
            statusText = "서버 연결 테스트 실패"
        }
    }

    private func startConnectionTestTimeout() {
        connectionTestTimeoutTimer?.invalidate()
        connectionTestTimeoutTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in
            guard let manager = self else {
                return
            }

            Task { @MainActor in
                guard manager.isTestingConnection else {
                    return
                }

                manager.finishConnectionTest(success: false, message: "서버 응답 시간이 초과되었습니다.")
            }
        }
    }

    private func finishStopped() {
        signalingClient.disconnect()
        stopMediaUplink()
        reconnectAttempts = 0
        state = .idle
        startedAt = nil
        lastErrorMessage = nil
        statusText = "방송 대기"
        metrics = BroadcastMetrics()
    }

    private func makeControlMessage(type: String, mediaUplink: MediaUplinkSignal? = nil) -> BroadcastControlMessage {
        BroadcastControlMessage(
            type: type,
            sessionID: sessionID,
            title: streamTitle,
            broadcasterID: broadcasterID,
            mosaicPolicy: mosaicPolicy,
            destinations: destinationPayloads,
            timestamp: Date(),
            mediaUplink: mediaUplink
        )
    }

    private func startMediaUplink() {
        guard let mediaUplinkClient else {
            mediaUplinkState = .failed
            mediaUplinkStatusText = "WebRTC 업링크 클라이언트를 사용할 수 없습니다."
            return
        }

        mediaUplinkClient.start(
            originURL: serverURL(path: "/"),
            preferredVideoDeviceName: mediaUplinkCameraName
        )
    }

    private func stopMediaUplink() {
        mediaUplinkClient?.stop()
        mediaUplinkState = .idle
        mediaUplinkStatusText = "WebRTC 업링크 대기"
    }

    private func sendMediaUplinkSignal(_ signal: MediaUplinkSignal) {
        guard state == .connecting || state == .live else {
            return
        }

        if usesNativeWebRTCSignaling {
            switch signal.type {
            case "offer":
                guard let sdp = signal.sdp else {
                    failBroadcast(message: "WebRTC offer SDP를 생성하지 못했습니다.")
                    return
                }

                signalingClient.send(ServerSignalingMessage.offer(sessionID: sessionID, sdp: sdp)) { [weak self] error in
                    guard let self, let error else {
                        return
                    }

                    self.handleServerStartFailure(error.localizedDescription)
                }
            case "iceCandidate":
                signalingClient.send(ServerSignalingMessage.iceCandidate(sessionID: sessionID, signal: signal))
            default:
                break
            }
            return
        }

        signalingClient.send(makeControlMessage(type: "mediaUplink", mediaUplink: signal))
    }

    private func handleMediaUplinkSignal(_ signal: MediaUplinkSignal?) {
        guard let signal else {
            return
        }

        switch signal.type {
        case "answer":
            guard let sdp = signal.sdp else {
                return
            }
            mediaUplinkClient?.acceptAnswer(sdp)
        case "iceCandidate":
            mediaUplinkClient?.addRemoteCandidate(signal)
        case "status":
            if let connectionState = signal.connectionState {
                mediaUplinkStatusText = signal.message ?? "WebRTC 상태: \(connectionState)"
            }
        case "error":
            mediaUplinkState = .failed
            mediaUplinkStatusText = signal.message ?? "서버 WebRTC 오류"
        default:
            break
        }
    }

    private func stopTimers() {
        metricsTimer?.invalidate()
        metricsTimer = nil
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        connectionTimeoutTimer?.invalidate()
        connectionTimeoutTimer = nil
        connectionTestTimeoutTimer?.invalidate()
        connectionTestTimeoutTimer = nil
        reconnectTimer?.invalidate()
        reconnectTimer = nil
        serverSessionTask?.cancel()
        serverSessionTask = nil
    }

    private var serverSessionCollectionURL: URL? {
        serverURL(path: "/sessions")
    }

    private func serverSessionURL(sessionID: String) -> URL? {
        serverURL(path: "/sessions/\(sessionID)")
    }

    private func serverURL(path: String) -> URL? {
        guard let signalingURL,
              var components = URLComponents(url: signalingURL, resolvingAgainstBaseURL: false) else {
            return nil
        }

        components.scheme = signalingURL.scheme == "wss" ? "https" : "http"
        components.path = path
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private func createServerSession() async throws -> String {
        guard let url = serverSessionCollectionURL else {
            throw URLError(.badURL)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(
            ServerSessionCreateRequest(
                metadata: [
                    "title": streamTitle,
                    "broadcaster_id": broadcasterID,
                    "client": "innolive-mac"
                ]
            )
        )

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200..<300).contains(httpResponse.statusCode) else {
            let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw BroadcastServerHTTPError(statusCode: statusCode, operation: "세션 생성")
        }

        return try JSONDecoder().decode(ServerSessionResponse.self, from: data).sessionID
    }

    private func deleteServerSessionInBackground(_ sessionID: String) {
        guard let url = serverSessionURL(sessionID: sessionID) else {
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        URLSession.shared.dataTask(with: request).resume()
    }

    private func restoreSettings() {
        guard let data = userDefaults.data(forKey: settingsKey),
              let snapshot = try? JSONDecoder().decode(BroadcastSettingsSnapshot.self, from: data) else {
            return
        }

        isRestoringSettings = true
        mode = BroadcastDefaults.mode(for: snapshot)
        streamTitle = snapshot.streamTitle
        broadcasterID = snapshot.broadcasterID
        signalingURLString = BroadcastDefaults.signalingURLString(for: snapshot)
        processedVideoURLString = BroadcastDefaults.processedVideoURLString(for: snapshot)
        automaticallyReconnect = snapshot.automaticallyReconnect
        enableWebRTCMediaUplink = BroadcastDefaults.enableWebRTCMediaUplink(
            for: snapshot,
            fallback: enableWebRTCMediaUplink
        )
        destinations = snapshot.destinations
        isRestoringSettings = false
    }

    private func saveSettings() {
        guard !isRestoringSettings else {
            return
        }

        let snapshot = BroadcastSettingsSnapshot(
            mode: mode,
            streamTitle: streamTitle,
            broadcasterID: broadcasterID,
            signalingURLString: signalingURLString,
            processedVideoURLString: processedVideoURLString,
            automaticallyReconnect: automaticallyReconnect,
            enableWebRTCMediaUplink: enableWebRTCMediaUplink,
            destinations: destinations
        )

        guard let data = try? JSONEncoder().encode(snapshot) else {
            return
        }

        userDefaults.set(data, forKey: settingsKey)
    }
}

private struct BroadcastSettingsSnapshot: Codable {
    var mode: BroadcastMode
    var streamTitle: String
    var broadcasterID: String
    var signalingURLString: String
    var processedVideoURLString: String
    var automaticallyReconnect: Bool
    var enableWebRTCMediaUplink: Bool?
    var destinations: [StreamDestination]
}

private struct ServerSessionCreateRequest: Codable {
    var metadata: [String: String]
}

private struct ServerSessionResponse: Codable {
    var sessionID: String

    enum CodingKeys: String, CodingKey {
        case sessionID = "session_id"
    }
}

private struct BroadcastServerHTTPError: LocalizedError {
    let statusCode: Int
    let operation: String

    var errorDescription: String? {
        "\(operation) 실패: HTTP \(statusCode)"
    }
}

private enum BroadcastDefaults {
    static let mode: BroadcastMode = .serverBridge
    static let signalingURLString = ServerEnvironment.current.signalingURLString
    static let processedVideoURLString = ServerEnvironment.current.processedVideoURLString

    private static let legacyMode: BroadcastMode = .mockLocal
    private static let legacySignalingURLString = "ws://localhost:8080/live/signaling"
    private static let rejectedSignallingURLString = "ws://127.0.0.1:8000/signalling"
    private static let legacyProcessedVideoURLString = ""

    static func mode(for snapshot: BroadcastSettingsSnapshot) -> BroadcastMode {
        guard snapshot.mode == legacyMode,
              snapshot.signalingURLString == legacySignalingURLString,
              snapshot.processedVideoURLString == legacyProcessedVideoURLString else {
            return snapshot.mode
        }

        return mode
    }

    static func signalingURLString(for snapshot: BroadcastSettingsSnapshot) -> String {
        if ServerEnvironment.current.isConfiguredExternally {
            return signalingURLString
        }

        if snapshot.signalingURLString == legacySignalingURLString ||
            snapshot.signalingURLString == rejectedSignallingURLString {
            return signalingURLString
        }

        return snapshot.signalingURLString
    }

    static func enableWebRTCMediaUplink(
        for snapshot: BroadcastSettingsSnapshot,
        fallback: Bool
    ) -> Bool {
        if requiresOfferSignaling(signalingURLString(for: snapshot)) {
            return true
        }

        return snapshot.enableWebRTCMediaUplink ?? fallback
    }

    static func processedVideoURLString(for snapshot: BroadcastSettingsSnapshot) -> String {
        if ServerEnvironment.current.isConfiguredExternally {
            return processedVideoURLString
        }

        if snapshot.processedVideoURLString == legacyProcessedVideoURLString {
            return processedVideoURLString
        }

        return snapshot.processedVideoURLString
    }

    static func requiresOfferSignaling(_ signalingURLString: String) -> Bool {
        guard let components = URLComponents(string: signalingURLString.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.path == "/signaling" else {
            return false
        }

        return components.scheme == "ws" || components.scheme == "wss"
    }
}
