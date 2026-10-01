#if DEBUG
import SwiftUI

/// Explicit launch-argument smoke test for an authorized private broadcast.
/// Uses the app's existing authentication, never exports tokens or camera images.
struct PrivateBroadcastValidationView: View {
    @StateObject private var authentication = AuthSession()
    @StateObject private var youtube: YouTubeIntegration
    private let api: YouTubeAPI
    @State private var report = "비공개 온디바이스 송출 준비"
    @State private var started = false

    init() {
        let api = YouTubeAPI()
        self.api = api
        _youtube = StateObject(wrappedValue: YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(), api: api,
            aiModeProvider: { (ProcessInfo.processInfo.arguments.contains("--ai-mode-switch-test") || ProcessInfo.processInfo.arguments.contains("--live-broadcast-edit-test")) ? .server : .onDevice },
            persistAIProcessingMode: { _ in }
        ))
    }

    var body: some View {
        VStack {
            RemoteStreamView(uplink: youtube.videoUplink, previewTransition: .none,
                             isPreparingSession: youtube.isPreparingSession, isConnectingVideo: youtube.isConnectingVideo,
                             recoveryStatus: youtube.videoRecoveryStatus)
            ScrollView { Text(report).font(.caption.monospaced()).padding() }.frame(maxHeight: 220)
        }.task {
            guard !started else { return }
            started = true
            if ProcessInfo.processInfo.arguments.contains("--ai-mode-switch-test") { await runSwitchTest() }
            else if ProcessInfo.processInfo.arguments.contains("--live-broadcast-edit-test") { await runLiveEditTest() }
            else { await run() }
        }
    }

    private func record(_ value: String) {
        report += "\n" + value
        print("PRIVATE_BROADCAST_TEST \(value)")
        if let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            try? report.write(to: documents.appendingPathComponent("private-broadcast-validation.txt"), atomically: true, encoding: .utf8)
        }
    }

    private func runLiveEditTest() async {
        youtube.configureAuthentication(authentication)
        authentication.restore()
        guard authentication.isAuthenticated else { record("BLOCKED: 앱 로그인이 필요합니다."); return }
        await youtube.refreshConnection(accessToken: authentication.currentAccessToken())
        guard youtube.isConnected else { record("BLOCKED: YouTube 연결이 필요합니다."); return }
        guard let audience = youtube.broadcastSettings.audience else { record("BLOCKED: 시청자층 설정이 필요합니다."); return }
        let originalSettings = youtube.settingsEditor.youtube
        defer {
            youtube.settingsEditor.editYouTube { $0 = originalSettings }
            youtube.settingsEditor.persist(provider: .youtube)
        }
        var prepared = false
        do {
            guard await youtube.prepareSession(accessToken: authentication.currentAccessToken()), let session = youtube.session,
                  await youtube.connectVideo(accessToken: authentication.currentAccessToken(), preferredCameraID: nil,
                                             preferredAudioID: nil, preferredVideoQuality: .hd30),
                  let token = authentication.currentAccessToken() else { throw PrivacyFaceError.message("세션·영상 연결 실패") }
            let settings = YouTubeBroadcastSettings(title: "InnoLive 비공개 방송 수정 검증", description: "라이브 편집 검증", privacy: .private, audience: audience, categoryID: "22")
            youtube.settingsEditor.editYouTube { $0 = settings }
            prepared = true
            guard await youtube.prepareYouTubeStream(accessToken: token, useEditor: true) else { throw PrivacyFaceError.message("방송 준비 실패") }
            await youtube.goLiveYouTubeStream(accessToken: token)
            guard youtube.canEditLiveBroadcast(.youtube) else { throw PrivacyFaceError.message("라이브 전환 실패") }
            let peer = youtube.videoUplink.peerConnection
            let before = try await api.sessionStatus(session: session, accessToken: token)
            guard before.broadcast?.privacy == "private" else { throw PrivacyFaceError.message("비공개 설정 확인 실패") }
            youtube.beginLiveEditing(.youtube, accessToken: token)
            youtube.liveSettingsEditor.editYouTube { $0.title = "InnoLive 비공개 방송 수정 완료"; $0.description = "송출 유지 중 PATCH 검증"; $0.categoryID = "20" }
            guard await youtube.saveLiveSettings(accessToken: token) else { throw PrivacyFaceError.message(youtube.liveSettingsError ?? "라이브 편집 실패") }
            let after = try await api.sessionStatus(session: session, accessToken: token)
            guard after.broadcast?.title == "InnoLive 비공개 방송 수정 완료", after.broadcast?.description == "송출 유지 중 PATCH 검증",
                  after.broadcast?.categoryID == "20", after.broadcast?.privacy == "private", after.stream.broadcastPhaseValue == .live,
                  after.stream.publisherActive, after.stream.startedAt == before.stream.startedAt,
                  youtube.session?.sessionID == session.sessionID, youtube.videoUplink.peerConnection === peer else {
                throw PrivacyFaceError.message("수정값·세션·송출 유지 확인 실패")
            }
            record("PASS: real_server_PATCH; title_description_category=true; private=true; same_session_peer_start=true; publisher_active=true")
            try await Task.sleep(for: .seconds(10))
            let final = try await api.sessionStatus(session: session, accessToken: token)
            guard final.stream.broadcastPhaseValue == .live, final.stream.publisherActive else { throw PrivacyFaceError.message("수정 후 송출 유지 실패") }
            record("PASS: live_after_edit_10s=true; external_viewer_audio_video=unverified")
        } catch { record("FAILED: 방송 수정 검증을 완료하지 못했습니다.") }
        if prepared {
            await youtube.stopYouTubeStream(accessToken: authentication.currentAccessToken())
            if let session = youtube.session, let token = authentication.currentAccessToken(),
               let stopped = try? await api.sessionStatus(session: session, accessToken: token),
               stopped.stream.broadcastPhaseValue != .live {
                record("PASS: test_broadcast_stopped=true")
            } else { record("FAILED: 테스트 방송 종료 상태를 확인하지 못했습니다.") }
        }
        await youtube.endBroadcast(accessToken: authentication.currentAccessToken())
        record(youtube.session == nil ? "PASS: session_closed=true" : "FAILED: 세션 종료를 확인하지 못했습니다.")
        record("live_edit_validation_finished")
    }

    private func runSwitchTest() async {
        UIApplication.shared.isIdleTimerDisabled = true
        defer { UIApplication.shared.isIdleTimerDisabled = false }
        youtube.configureAuthentication(authentication)
        authentication.restore()
        guard authentication.isAuthenticated else { record("BLOCKED: 앱 로그인이 필요합니다."); return }
        do {
            guard await youtube.prepareSession(accessToken: authentication.currentAccessToken()), let initial = youtube.session else {
                throw PrivacyFaceError.message(youtube.errorMessage ?? "세션 준비 실패")
            }
            guard await youtube.connectVideo(accessToken: authentication.currentAccessToken(), preferredCameraID: nil,
                                             preferredAudioID: nil, preferredVideoQuality: .hd30),
                  let peer = youtube.videoUplink.peerConnection else {
                throw PrivacyFaceError.message(youtube.errorMessage ?? "영상 연결 실패")
            }
            let capturer = youtube.videoUplink.cameraCapturer
            if !youtube.isAnonymizationEnabled { await youtube.toggleAnonymization(accessToken: authentication.currentAccessToken()) }
            guard youtube.isAnonymizationEnabled else { throw PrivacyFaceError.message("서버 AI 활성화 실패") }
            record("switch_test_start: server connected; no YouTube broadcast created")
            for cycle in 1...2 {
                let start = ProcessInfo.processInfo.systemUptime
                guard await youtube.changeAIProcessingMode(.onDevice, accessToken: authentication.currentAccessToken()) else {
                    throw PrivacyFaceError.message(youtube.errorMessage ?? "로컬 전환 실패")
                }
                let counts = PrivacyUplinkValidationMetrics.counts
                try await Task.sleep(for: .seconds(5))
                guard youtube.session?.sessionID == initial.sessionID, youtube.videoUplink.peerConnection === peer,
                      youtube.videoUplink.cameraCapturer === capturer, youtube.videoUplink.state == .connected,
                      youtube.session?.processingMode == .onDevice,
                      PrivacyUplinkValidationMetrics.counts.processed > counts.processed,
                      PrivacyUplinkValidationMetrics.counts.raw == 0 else {
                    throw PrivacyFaceError.message("로컬 전환 후 연결 또는 프레임 검증 실패")
                }
                record("cycle=\(cycle) local_same_session_peer_camera=true; processed_frames=\(PrivacyUplinkValidationMetrics.counts.processed - counts.processed); elapsed_ms=\(Int((ProcessInfo.processInfo.systemUptime - start - 5) * 1000))")
                guard await youtube.changeAIProcessingMode(.server, accessToken: authentication.currentAccessToken()) else {
                    throw PrivacyFaceError.message(youtube.errorMessage ?? "서버 전환 실패")
                }
                try await Task.sleep(for: .seconds(2))
                guard youtube.session?.sessionID == initial.sessionID, youtube.videoUplink.peerConnection === peer,
                      youtube.videoUplink.cameraCapturer === capturer, youtube.videoUplink.state == .connected,
                      youtube.session?.processingMode == .server else {
                    throw PrivacyFaceError.message("서버 전환 후 연결 검증 실패")
                }
                record("cycle=\(cycle) server_same_session_peer_camera=true")
            }
            record("PASS: bidirectional switch, no reconnect, raw_local_frames=0")
        } catch { record("FAILED: \(error.localizedDescription)") }
        await youtube.endBroadcast(accessToken: authentication.currentAccessToken())
        record("switch_test_finished")
    }

    private func run() async {
        UIApplication.shared.isIdleTimerDisabled = true
        defer { UIApplication.shared.isIdleTimerDisabled = false }
        youtube.configureAuthentication(authentication)
        authentication.restore()
        guard authentication.isAuthenticated else { record("BLOCKED: 앱 로그인이 필요합니다."); return }
        await youtube.refreshConnection(accessToken: authentication.currentAccessToken())
        guard youtube.isConnected, youtube.errorMessage == nil else { record("BLOCKED: YouTube 연결 확인 실패"); return }
        guard let audience = youtube.broadcastSettings.audience else { record("BLOCKED: 저장된 시청자층 설정이 필요합니다."); return }
        var prepared = false
        do {
            guard await youtube.prepareSession(accessToken: authentication.currentAccessToken()),
                  let session = youtube.session, session.processingMode == .onDevice else {
                throw PrivacyFaceError.message(youtube.errorMessage ?? "온디바이스 세션 준비 실패")
            }
            record("session=on_device; local_anonymization=true")
            guard await youtube.connectVideo(accessToken: authentication.currentAccessToken(), preferredCameraID: nil,
                                             preferredAudioID: nil, preferredVideoQuality: .hd30) else {
                throw PrivacyFaceError.message(youtube.errorMessage ?? "영상 연결 실패")
            }
            guard let token = authentication.currentAccessToken() else { throw YouTubeAPIError.unauthorized }
            let settings = YouTubeBroadcastSettings(title: "InnoLive 온디바이스 비공개 검증 \(Date().ISO8601Format())",
                                                     description: "iPhone 온디바이스 비식별화 송출 검증", privacy: .private, audience: audience)
            let saved = try await api.saveBroadcastSettings(session: session, accessToken: token, settings: settings)
            guard saved.broadcast?.privacy == "private" else { throw PrivacyFaceError.message("비공개 설정 확인 실패") }
            record("server_broadcast_privacy=private")
            // Set before awaiting, so a lost prepare response still triggers cleanup.
            prepared = true
            let snapshot = try await api.prepareStream(session: session, accessToken: token)
            guard snapshot.broadcast?.privacy == "private" else { throw PrivacyFaceError.message("준비된 방송의 비공개 설정 확인 실패") }
            var live = false
            for _ in 0..<30 {
                do {
                    let stream = try await api.goLive(session: session, accessToken: token)
                    live = stream.broadcastPhaseValue == .live
                    if live { break }
                } catch let error as YouTubeAPIError {
                    if case let .api(code, _, _) = error, code == "broadcast_not_ready" {
                        try await Task.sleep(for: .seconds(2))
                        continue
                    }
                    throw error
                }
                try await Task.sleep(for: .seconds(2))
            }
            guard live else { throw PrivacyFaceError.message("라이브 전환 시간 초과") }
            record("broadcast_phase=live; observing=20s")
            let before = PrivacyUplinkValidationMetrics.counts
            try await Task.sleep(for: .seconds(20))
            let after = PrivacyUplinkValidationMetrics.counts
            let status = try await api.sessionStatus(session: session, accessToken: token)
            guard status.broadcast?.privacy == "private", status.stream.broadcastPhaseValue == .live,
                  status.stream.publisherActive, status.media.anonymizationEnabled == false,
                  after.processed > before.processed, after.raw == 0 else {
                throw PrivacyFaceError.message("비공개 송출 또는 처리 프레임 검증 실패")
            }
            record("PASS: processed_frames=\(after.processed - before.processed); raw_frames=\(after.raw); publisher_active=true; server_ai=false")
        } catch {
            record("FAILED: \(error.localizedDescription)")
        }
        if prepared, let session = youtube.session, let token = authentication.currentAccessToken() {
            do {
                _ = try await api.stopStream(session: session, accessToken: token)
                record("stop_request=success")
                let status = try await api.sessionStatus(session: session, accessToken: token)
                record("after_stop_phase=\(status.stream.broadcastPhase ?? "unknown"); publisher_active=\(status.stream.publisherActive)")
            } catch { record("CLEANUP_FAILED: 방송 종료를 확인해야 합니다.") }
        }
        await youtube.endBroadcast(accessToken: authentication.currentAccessToken())
        record("validation_finished")
    }
}
#endif
