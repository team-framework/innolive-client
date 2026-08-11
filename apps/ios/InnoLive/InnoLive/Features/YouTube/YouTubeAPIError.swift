import Foundation

enum YouTubeAPIError: Error, Equatable {
    case featureUnavailable
    case unauthorized
    case streamingNotConnected
    case sessionRequired
    case videoNotConnected
    case videoTrackUnavailable
    case authorizationCodeMissing
    case configuration
    case transport
    case response
    case api(code: String?, fallback: String, helpURL: URL?)

    static func == (lhs: YouTubeAPIError, rhs: YouTubeAPIError) -> Bool {
        switch (lhs, rhs) {
        case (.featureUnavailable, .featureUnavailable), (.unauthorized, .unauthorized),
             (.streamingNotConnected, .streamingNotConnected), (.sessionRequired, .sessionRequired),
             (.videoNotConnected, .videoNotConnected),
             (.videoTrackUnavailable, .videoTrackUnavailable),
             (.authorizationCodeMissing, .authorizationCodeMissing), (.configuration, .configuration),
             (.transport, .transport), (.response, .response):
            return true
        case let (.api(lhsCode, _, _), .api(rhsCode, _, _)):
            return lhsCode == rhsCode
        default:
            return false
        }
    }

    var helpURL: URL? {
        if case let .api(_, _, helpURL) = self { return helpURL }
        return nil
    }

    var userMessage: String {
        switch self {
        case .featureUnavailable: return "이 서버에는 YouTube 연결 기능이 아직 구성되지 않았습니다."
        case .unauthorized: return "로그인이 만료되었습니다. 다시 로그인해 주세요."
        case .streamingNotConnected: return "YouTube 계정을 먼저 연결해 주세요."
        case .sessionRequired: return "InnoLive 방송을 먼저 시작해 주세요."
        case .videoNotConnected: return "카메라 영상을 서버에 먼저 연결해 주세요."
        case .videoTrackUnavailable: return "서버가 카메라 영상을 받지 못했습니다. 네트워크를 확인한 뒤 방송을 다시 시작해 주세요."
        case .authorizationCodeMissing: return "YouTube 인가 코드를 받지 못했습니다. 다시 시도해 주세요."
        case .configuration: return "YouTube 연결 설정이 필요합니다."
        case .transport: return "YouTube 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."
        case .response: return "YouTube 서버 응답을 확인하지 못했습니다. 다시 시도해 주세요."
        case let .api(code, fallback, _):
            switch code {
            case "unauthorized": return "로그인이 만료되었습니다. 다시 로그인해 주세요."
            case "bad_request": return "YouTube 연결 요청을 확인하지 못했습니다. 다시 시도해 주세요."
            case "invalid_auth_code": return "YouTube 인가 코드가 만료됐습니다. 계정 연결부터 다시 시도해 주세요."
            case "youtube_channel_missing": return "이 Google 계정에 YouTube 채널이 없습니다. 채널을 먼저 만들어 주세요."
            case "youtube_token_exchange_failed": return "YouTube 인증 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."
            case "streaming_not_connected": return "YouTube 계정을 먼저 연결해 주세요."
            case "live_streaming_blocked": return "YouTube 라이브를 먼저 활성화해 주세요. 활성화에는 최대 24시간이 걸릴 수 있습니다."
            case "conflict": return "카메라 영상을 연결한 뒤 YouTube 송출을 다시 시작해 주세요."
            case "stream_already_active": return "YouTube 송출이 이미 진행 중입니다."
            case "stream_not_active": return "YouTube 송출 중이 아닙니다."
            case "not_supported": return "이 서버에는 YouTube 송출 기능이 아직 구성되지 않았습니다."
            case "streaming_prepare_failed": return "YouTube 송출을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요."
            default: return fallback
            }
        }
    }
}
