import Foundation

struct BroadcastProblem: Equatable, Identifiable {
    enum Presentation { case alert, confirmation, inline, busy, silent }
    enum Action { case accounts, plan, help, retry, none }

    let status: Int
    let code: String?
    let message: String
    let provider: String
    let field: String?
    let reason: String?
    let helpURL: URL?
    var id: String { "\(provider):\(code ?? "unknown")" }

    var presentation: Presentation {
        switch code {
        case "channel_already_live": return .confirmation
        case "bad_request", "field_not_changeable_live": return .inline
        case "resolution_switch_in_progress", "broadcast_busy", "broadcast_going_live": return .busy
        case "stale_negotiation", "upgrade_offer_not_found": return .silent
        default: return .alert
        }
    }

    var action: Action {
        switch code {
        case "streaming_not_connected", "streaming_reconnect_required": return .accounts
        case "plan_resolution_not_allowed", "plan_simulcast_not_allowed", "plan_server_streaming_not_allowed", "monthly_limit_exhausted": return .plan
        case "live_streaming_blocked": return helpURL == nil ? .none : .help
        case "streaming_prepare_failed", "streaming_golive_failed", "streaming_update_failed": return .retry
        default: return .none
        }
    }

    var providerTitle: String {
        switch provider {
        case "youtube": return "YouTube"
        case "chzzk": return String(localized: "치지직")
        default: return String(localized: "플랫폼")
        }
    }

    var userMessage: String {
        let platform = providerTitle
        switch code {
        case "streaming_not_connected": return String(format: String(localized: "%@ 계정을 먼저 연결하세요."), platform)
        case "streaming_reconnect_required": return String(format: String(localized: "%@ 계정 연결이 만료됐습니다. 다시 연결하세요."), platform)
        case "channel_already_live": return String(localized: "YouTube 채널이 이미 다른 도구로 방송 중입니다. 계속하면 라이브가 하나 더 열려 시청자가 나뉠 수 있습니다.")
        case "live_streaming_blocked": return String(localized: "채널의 라이브 스트리밍이 아직 활성화되지 않았습니다. 활성화에는 최대 24시간이 걸릴 수 있습니다.")
        case "plan_resolution_not_allowed": return String(localized: "현재 플랜에서는 FHD를 사용할 수 없습니다.")
        case "plan_simulcast_not_allowed": return String(localized: "현재 플랜에서는 동시 송출을 사용할 수 없습니다.")
        case "plan_server_streaming_not_allowed": return String(localized: "현재 플랜에서는 서버 송출을 사용할 수 없습니다.")
        case "monthly_limit_exhausted": return String(localized: "이번 달 방송 시간을 모두 사용했습니다.")
        case "broadcast_limit_reached": return String(localized: "이번 방송의 최대 시간에 도달했습니다. 새 방송을 시작하세요.")
        case "egress_slots_exhausted", "capacity_exceeded", "server_busy": return String(localized: "지금은 송출 자리가 없습니다. 잠시 후 다시 시도하세요.")
        case "streaming_quota_exceeded": return String(localized: "오늘 YouTube 연동 한도를 모두 사용했습니다. 한국 시간 오후 4시, 겨울에는 오후 5시에 초기화됩니다.")
        case "streaming_rate_limited": return String(localized: "플랫폼 요청 한도에 도달했습니다. 잠시 후 다시 시도하세요.")
        case "streaming_account_in_use": return String(localized: "이 플랫폼으로 방송 중에는 연결을 해제할 수 없습니다. 방송을 먼저 종료하세요.")
        case "streaming_prepare_failed", "streaming_golive_failed", "streaming_update_failed": return String(format: String(localized: "%@에서 요청을 처리하지 못했습니다. 다시 시도하세요."), platform)
        case "withdrawal_in_progress": return String(localized: "계정 삭제가 진행 중입니다.")
        case "field_not_changeable_live": return String(localized: "방송 중에는 바꿀 수 없는 항목입니다.")
        case "bad_request":
            switch reason {
            case "required", "empty": return String(localized: "필수 항목을 입력하세요.")
            case "too_long": return String(localized: "입력 길이를 줄여 주세요.")
            default: return String(localized: "입력한 설정을 확인하세요.")
            }
        case "unauthorized": return String(localized: "로그인이 만료되었습니다. 다시 로그인해 주세요.")
        default: return YouTubeAPIError.api(code: code, fallback: message, helpURL: helpURL).userMessage
        }
    }
}
