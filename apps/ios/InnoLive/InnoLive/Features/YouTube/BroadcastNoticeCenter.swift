import Foundation

struct BroadcastNoticeBanner: Identifiable, Equatable {
    let id: String
    let message: String
    let isError: Bool
}

struct BroadcastNoticeCenter {
    private(set) var sessionID: String?
    private(set) var banners: [BroadcastNoticeBanner] = []
    private var seen: Set<String> = []

    mutating func reset() { self = Self() }
    mutating func dismiss(_ id: String) { banners.removeAll { $0.id == id } }

    mutating func consume(sessionID: String, notices: [BroadcastNotice], warnings: [BroadcastPrepareWarning], targets: [BroadcastTargetState], failures: [BroadcastTargetFailure] = []) {
        if self.sessionID != sessionID { reset(); self.sessionID = sessionID }
        for notice in notices {
            guard let message = noticeMessage(notice.code) else { continue }
            let error = ["broadcast_limit_reached", "monthly_limit_reached", "no_input_stopped", "channel_live_elsewhere", "platform_broadcast_ended"].contains(notice.code)
            append(key: notice.code, message: message, isError: error)
        }
        for warning in warnings {
            guard let message = noticeMessage(warning.code) else { continue }
            append(key: warning.code, message: message, isError: false)
        }
        for target in targets where target.stream.statusValue == .stopped {
            let platform = BroadcastProblem(status: 0, code: nil, message: "", provider: target.provider, field: nil, reason: nil, helpURL: nil).providerTitle
            switch target.stream.stopReason {
            case "rtmp_reconnect_exhausted":
                append(key: "\(target.provider):rtmp_reconnect_exhausted", message: String(format: String(localized: "%@ 연결이 끊겨 송출이 종료됐습니다."), platform), isError: true)
            case "reconnect_input_timeout":
                append(key: "no_input_stopped", message: String(localized: "영상 입력이 복구되지 않아 송출이 종료됐습니다."), isError: true)
            case "platform_ended":
                append(key: "platform_broadcast_ended", message: String(format: String(localized: "%@ 스튜디오에서 방송이 종료되어 송출을 멈췄습니다."), platform), isError: true)
            default: break
            }
        }
        for failure in failures {
            let problem = BroadcastProblem(status: 0, code: failure.code, message: failure.message ?? "", provider: failure.provider, field: nil, reason: nil, helpURL: nil)
            let message: String
            switch failure.code {
            case "resolution_change_failed": message = String(format: String(localized: "해상도를 바꾸지 못했습니다. %@ 방송을 다시 시작하세요."), problem.providerTitle)
            case "resolution_switch_canceled": message = String(format: String(localized: "전환이 취소되어 %@ 방송을 열지 못했습니다."), problem.providerTitle)
            default: message = problem.userMessage
            }
            append(key: "\(failure.provider):\(failure.code)", message: message, isError: true)
        }
    }

    private mutating func append(key: String, message: String, isError: Bool) {
        guard seen.insert(key).inserted else { return }
        banners.append(BroadcastNoticeBanner(id: key, message: message, isError: isError))
    }

    private func noticeMessage(_ code: String) -> String? {
        switch code {
        case "broadcast_limit_30m": return String(localized: "이번 방송 가능 시간이 30분 남았습니다.")
        case "broadcast_limit_10m": return String(localized: "이번 방송 가능 시간이 10분 남았습니다.")
        case "broadcast_limit_reached": return String(localized: "방송 최대 시간에 도달해 송출을 종료했습니다.")
        case "monthly_usage_80": return String(localized: "이번 달 방송 시간을 80% 사용했습니다.")
        case "monthly_usage_100": return String(localized: "이번 달 방송 시간을 100% 사용했습니다.")
        case "monthly_limit_reached": return String(localized: "이번 달 방송 시간을 모두 사용해 송출을 종료했습니다.")
        case "no_input_stopped": return String(localized: "영상 입력이 없어 송출을 종료했습니다.")
        case "channel_live_elsewhere": return String(localized: "치지직 채널이 다른 도구로 방송 중이라 송출이 거절됐습니다. 다른 도구의 방송을 먼저 종료하세요.")
        case "platform_broadcast_ended": return String(localized: "플랫폼 스튜디오에서 방송이 종료되어 해당 플랫폼 송출을 멈췄습니다.")
        case "youtube_quota_low": return String(localized: "오늘 YouTube 연동 사용량이 많아 방식 변경이나 새 방송이 실패할 수 있습니다.")
        default: return nil
        }
    }
}
