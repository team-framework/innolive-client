import Foundation

/// 온디바이스 세션에서 앱이 직접 송출할 플랫폼 ingest 주소다(server #438).
/// 스트림 키가 들어 있으므로 문자열 표현·디버그 출력·Mirror에 값을 싣지 않는다.
/// 저장소에 기록하지 않고 세션 메모리에만 둔다.
nonisolated struct DirectIngestEndpoint: Equatable, Sendable, CustomStringConvertible,
    CustomDebugStringConvertible, CustomReflectable {
    /// `rtmp(s)://host[:port]/app` 형태의 연결 주소
    let connectURL: String
    /// 마지막 경로 구성요소. 플랫폼 스트림 키다.
    let streamName: String

    init?(ingestURL: String) {
        let trimmed = ingestURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(), scheme == "rtmp" || scheme == "rtmps",
              let host = components.host, !host.isEmpty,
              let schemeEnd = trimmed.range(of: "://"),
              let lastSlash = trimmed.lastIndex(of: "/"), lastSlash > schemeEnd.upperBound else { return nil }
        let connectURL = String(trimmed[..<lastSlash])
        let streamName = String(trimmed[trimmed.index(after: lastSlash)...])
        // RTMP connect에는 앱 이름이 필요하다. `rtmp://host/key`처럼 앱 경로가 없으면 거부한다.
        guard let appStart = connectURL[schemeEnd.upperBound...].firstIndex(of: "/"),
              connectURL.index(after: appStart) < connectURL.endIndex,
              !streamName.isEmpty else { return nil }
        self.connectURL = connectURL
        self.streamName = streamName
    }

    var description: String { "DirectIngestEndpoint(<redacted>)" }
    var debugDescription: String { description }
    var customMirror: Mirror { Mirror(self, children: [], displayStyle: .struct) }
}

/// 라이브 전환 응답의 `direct_ingest[]` 항목이다. 치지직은 연결이 곧 공개 방송이므로
/// 이 응답에서만 주소를 받는다.
nonisolated struct DirectIngestTarget: Decodable, Equatable, Sendable {
    let provider: String
    let endpoint: DirectIngestEndpoint?

    enum CodingKeys: String, CodingKey {
        case provider
        case ingestURL = "ingest_url"
    }

    init(provider: String, endpoint: DirectIngestEndpoint?) {
        self.provider = provider
        self.endpoint = endpoint
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        provider = try container.decode(String.self, forKey: .provider)
        endpoint = try container.decodeIfPresent(String.self, forKey: .ingestURL).flatMap(DirectIngestEndpoint.init(ingestURL:))
    }
}

/// 앱 직접 송출 대상 하나의 연결 상태다.
nonisolated enum DirectPublishState: Equatable, Sendable {
    case connecting
    case publishing
    case paused
    case reconnecting
    case failed

    /// 서버는 앱 송출 대상의 stream.status를 idle로 둔다. 화면 상태 정책이 같은
    /// 규칙으로 판단하도록 앱 송출 상태를 서버 상태 값으로 옮긴다.
    var streamStatus: String {
        switch self {
        case .connecting, .reconnecting: return "reconnecting"
        case .publishing: return "streaming"
        case .paused: return "paused"
        case .failed: return "stopped"
        }
    }
}

extension YouTubeStreamState {
    nonisolated func overriding(status: String, publisherActive: Bool) -> Self {
        Self(
            status: status,
            startedAt: startedAt,
            stoppedAt: stoppedAt,
            publisherActive: publisherActive,
            lastError: lastError,
            reconnectAttempts: reconnectAttempts,
            stopReason: stopReason,
            pausedAt: pausedAt,
            broadcastPhase: broadcastPhase
        )
    }
}

extension BroadcastSessionSnapshot {
    /// 앱이 직접 송출하는 대상의 상태를 서버 스냅샷 위에 덮어쓴다.
    /// 방송 단계(phase)는 서버 값을 그대로 둔다.
    mutating func applyDirectPublishStates(_ states: [String: DirectPublishState]) {
        guard !states.isEmpty else { return }
        if var targets = details.targets {
            for index in targets.indices {
                guard let state = states[targets[index].provider],
                      targets[index].stream.broadcastPhaseValue != .idle else { continue }
                targets[index].stream = targets[index].stream.overriding(
                    status: state.streamStatus, publisherActive: state == .publishing
                )
            }
            details.targets = targets
        }
        if let current = stream, current.broadcastPhaseValue != .idle,
           let state = states[details.provider ?? "youtube"] {
            stream = current.overriding(status: state.streamStatus, publisherActive: state == .publishing)
        }
    }
}
