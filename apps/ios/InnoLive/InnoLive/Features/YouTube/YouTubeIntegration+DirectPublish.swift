import Foundation

extension YouTubeIntegration {
    // MARK: 앱 직접 송출 (#481)

    /// 서버가 송출하지 않고 앱이 플랫폼으로 직접 보내는 세션이다(server #438).
    var isDirectPublishSession: Bool { session.map(Self.isDirectPublish) ?? false }

    static func isDirectPublish(_ session: YouTubeBroadcastSession) -> Bool {
        session.aiProcessing == AIProcessingMode.onDevice.rawValue
    }

    /// 동시 송출 라이브 전환에서 실패한 대상의 안내다. 다른 플랫폼은 라이브로 남는다.
    static func liveStartFailureMessage(_ failure: BroadcastTargetFailure) -> String {
        let title = BroadcastSettingsProvider(rawValue: failure.provider)?.title ?? failure.provider
        let message = failure.isRateLimited
            ? String(localized: "채널 요청 한도로 라이브를 시작하지 못했습니다. 다른 플랫폼의 방송은 유지됩니다. 잠시 후 다시 시도하거나 다른 채널을 선택해 주세요.", table: "Simulcast")
            : String(localized: "라이브 시작에 실패했습니다. 다른 플랫폼의 방송은 유지됩니다.", table: "Simulcast")
        return title + ": " + message
    }

    /// 치지직은 RTMP 연결이 곧 공개 방송이라 서버가 라이브 전환 응답에서 주소를 준다. 나머지는 준비 응답에서 준다.
    static func receivesDirectIngestAtGoLive(_ provider: String) -> Bool {
        provider == BroadcastSettingsProvider.chzzk.rawValue
    }

    /// 주소가 없으면 송출을 시작하지 않고 실패로 처리한다. 영상 없이 남은 플랫폼 방송은 서버에서 정리한다.
    func startDirectPublish(provider: String, endpoint: DirectIngestEndpoint?, session: YouTubeBroadcastSession,
                                    accessToken: String, generation: UInt) async -> Bool {
        let quality = videoConnectionConfiguration?.preferredVideoQuality ?? .defaultValue
        do {
            guard let endpoint else { throw DirectPublishError.missingIngest(provider: provider) }
            try await directPublisher.start(provider: provider, endpoint: endpoint, quality: quality)
            guard generation == sessionOperationGeneration, self.session?.sessionID == session.sessionID else {
                await directPublisher.stop(provider: provider)
                return false
            }
            videoUplink.setDirectPublishBitrateLimited(true)
            refreshDirectPublishOverlay()
            return true
        } catch {
            guard generation == sessionOperationGeneration, self.session?.sessionID == session.sessionID else { return false }
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? DirectPublishError.connectionFailed(provider: provider).localizedDescription
            // 영상 없이 남은 플랫폼 방송을 정리한다. 실패해도 위 오류를 유지한다.
            if let response = try? await api.streamAction(.stop, session: session, accessToken: accessToken, provider: provider) {
                let message = errorMessage
                applySessionResponse(response, sessionID: session.sessionID, generation: generation, provider: provider)
                errorMessage = message
            }
            return false
        }
    }

    func stopAllDirectPublish() async {
        stopDirectHeartbeat()
        await directPublisher.stopAll()
        videoUplink.setDirectPublishBitrateLimited(false)
    }

    func refreshDirectPublishOverlay() {
        guard session != nil else { return }
        responseState.applyDirectPublishStates(directPublisher.states)
        stream = responseState.stream
        if directPublisher.states.isEmpty { videoUplink.setDirectPublishBitrateLimited(false) }
    }

    /// 서버에서 방송이 끝난 대상(스튜디오 종료, 한도 종료 등)은 앱 송출도 닫는다.
    func stopDirectPublishForEndedTargets() {
        guard let targets = responseState.details.targets else { return }
        for provider in directPublisher.states.keys {
            guard let target = targets.first(where: { $0.provider == provider }) else { continue }
            if target.stream.broadcastPhaseValue == .idle {
                Task { @MainActor [directPublisher] in await directPublisher.stop(provider: provider) }
            }
        }
    }

    func handleDirectPublishFailure(provider: String) async {
        let accessToken = api.currentAccessToken(fallback: videoUplink.accessToken ?? "")
        guard let session, !accessToken.isEmpty else {
            await directPublisher.stop(provider: provider)
            return
        }
        let generation = sessionOperationGeneration
        let title = BroadcastSettingsProvider(rawValue: provider)?.title ?? provider
        let message = String(format: String(localized: "%@ 송출 연결이 끊겨 방송을 종료했습니다.", table: "DirectPublish"), title)
        if let response = try? await api.streamAction(.stop, session: session, accessToken: accessToken, provider: provider) {
            applySessionResponse(response, sessionID: session.sessionID, generation: generation, provider: provider)
        }
        await directPublisher.stop(provider: provider)
        guard generation == sessionOperationGeneration else { return }
        errorMessage = message
        beginPolling(accessToken: accessToken)
    }

    // MARK: 직접 송출 하트비트 (server #434, #436)

    var isSendingDirectHeartbeat: Bool { heartbeatTask != nil }

    func beginDirectHeartbeat(accessToken: String) {
        guard heartbeatTask == nil, isDirectPublishSession else { return }
        heartbeatGeneration &+= 1
        let generation = heartbeatGeneration
        let sessionGeneration = sessionOperationGeneration
        let interval = heartbeatInterval
        heartbeatBuilder.reset()
        heartbeatTask = Task { [weak self] in
            var isFirst = true
            while !Task.isCancelled {
                if !isFirst {
                    do { try await Task.sleep(for: interval) } catch { return }
                }
                isFirst = false
                guard let self, self.heartbeatGeneration == generation,
                      self.sessionOperationGeneration == sessionGeneration else { return }
                // 실패해도 방송은 유지하고 다음 주기에 다시 보낸다. 누락분은 재전송하지 않는다.
                guard await self.sendDirectHeartbeat(generation: generation, sessionGeneration: sessionGeneration,
                                                     fallbackAccessToken: accessToken) else {
                    if self.heartbeatGeneration == generation { self.heartbeatTask = nil }
                    return
                }
            }
        }
    }

    func stopDirectHeartbeat() {
        heartbeatGeneration &+= 1
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    /// 계속 보낼지 돌려준다. 라이브 대상이 없다는 409를 받으면 멈춘다.
    private func sendDirectHeartbeat(generation: UInt, sessionGeneration: UInt, fallbackAccessToken: String) async -> Bool {
        guard let session, isDirectPublishSession else { return false }
        let accessToken = api.currentAccessToken(fallback: fallbackAccessToken)
        guard !accessToken.isEmpty else { return true }
        var status = await directPublisher.heartbeatStatus()
        guard heartbeatGeneration == generation, sessionOperationGeneration == sessionGeneration else { return false }
        // 서버에서 라이브인데 앱 연결이 없는 대상은 끊긴 상태로 보고한다.
        let reported = Set(status.targets.map(\.provider))
        for provider in liveEditingTargets.map(\.rawValue) where !reported.contains(provider) {
            status.targets.append(.init(provider: provider, connected: false, sentBytes: nil, reconnects: 0))
        }
        guard !status.targets.isEmpty else { return false }
        let request = heartbeatBuilder.makeRequest(
            status: status,
            anonymizationEnabled: isAnonymizationEnabled,
            device: DirectHeartbeatDevice.current(),
            model: DirectHeartbeatDevice.model,
            now: Date()
        )
        do {
            let response = try await api.sendHeartbeat(session: session, accessToken: accessToken, request: request)
            guard heartbeatGeneration == generation, sessionOperationGeneration == sessionGeneration,
                  self.session?.sessionID == session.sessionID else { return false }
            await applyHeartbeatResponse(response, session: session, accessToken: accessToken)
            return true
        } catch let error as YouTubeAPIError {
            if case let .api(code, _, _) = error, let code, Self.heartbeatTerminalCodes.contains(code) {
                // 보고 작업은 곧 취소되므로 정리는 별도 작업에서 한다. 취소된 작업에서는 세션 조회가 실패한다.
                Task { @MainActor [weak self] in
                    await self?.endDirectPublishAfterHeartbeat(code: code, session: session, accessToken: accessToken,
                                                               sessionGeneration: sessionGeneration)
                }
                return false
            }
            return true
        } catch {
            return true
        }
    }

    /// 공유 계약(direct-publish-v1)에서 모든 연결을 끊어야 하는 하트비트 오류다.
    /// 409 라이브 대상 없음, 403 소유권 상실, 404 세션 삭제. 401은 요청 계층이 인증을 갱신해 다시 보낸다.
    nonisolated static let heartbeatTerminalCodes: Set<String> = ["heartbeat_not_applicable", "forbidden", "not_found"]

    private func endDirectPublishAfterHeartbeat(code: String, session: YouTubeBroadcastSession, accessToken: String,
                                                sessionGeneration: UInt) async {
        guard sessionOperationGeneration == sessionGeneration, self.session?.sessionID == session.sessionID else { return }
        let titles = directPublisher.states.keys.sorted().map { BroadcastSettingsProvider(rawValue: $0)?.title ?? $0 }
        await stopAllDirectPublish()
        guard sessionOperationGeneration == sessionGeneration else { return }
        // 409는 세션이 남아 있어 서버 상태로 화면을 맞춘다. 403·404는 세션 조회도 실패하므로 기존 폴링이 정리한다.
        if code == "heartbeat_not_applicable",
           let status = try? await api.sessionStatus(session: session, accessToken: accessToken) {
            applySessionResponse(status, sessionID: session.sessionID, generation: sessionGeneration)
        }
        guard sessionOperationGeneration == sessionGeneration, !titles.isEmpty else { return }
        errorMessage = String(format: String(localized: "서버가 %@ 방송을 종료해 송출을 멈췄습니다.", table: "DirectPublish"),
                              titles.joined(separator: ", "))
    }

    private func applyHeartbeatResponse(_ response: DirectHeartbeatResponse, session: YouTubeBroadcastSession,
                                        accessToken: String) async {
        let generation = sessionOperationGeneration
        responseState.details.notices = response.notices
        if response.remainingTime != .missing {
            responseState.details.remainingTime = response.remainingTime
            isRemainingTimeStale = false
        }
        guard !response.stopTargets.isEmpty else { return }
        // 서버가 끝낸 대상(한도 도달, 입력 없음, 플랫폼에서 종료)은 즉시 끊는다.
        // 치지직은 이 연결 종료가 방송 종료다.
        for provider in response.stopTargets {
            await directPublisher.stop(provider: provider)
        }
        let titles = response.stopTargets.map { BroadcastSettingsProvider(rawValue: $0)?.title ?? $0 }
        if let status = try? await api.sessionStatus(session: session, accessToken: accessToken) {
            applySessionResponse(status, sessionID: session.sessionID, generation: generation)
        }
        guard generation == sessionOperationGeneration else { return }
        errorMessage = String(format: String(localized: "서버가 %@ 방송을 종료해 송출을 멈췄습니다.", table: "DirectPublish"),
                              titles.joined(separator: ", "))
    }
}
