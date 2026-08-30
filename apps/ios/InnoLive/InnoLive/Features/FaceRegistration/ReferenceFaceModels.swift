import Foundation

struct ReferenceFaceStatus: Decodable, Equatable {
    let registered: Bool
    let source: String?
    let registeredAt: String?
    let clientID: String
    let count: Int
    let faces: [ReferenceFace]

    enum CodingKeys: String, CodingKey {
        case registered
        case source
        case registeredAt = "registered_at"
        case clientID = "client_id"
        case count
        case faces
    }
}
struct ReferenceFace: Decodable, Equatable, Identifiable {
    let id: String
    let registeredAt: String

    enum CodingKeys: String, CodingKey {
        case id = "face_id"
        case registeredAt = "registered_at"
    }
}

enum ReferenceFaceAPIError: Error, Equatable {
    case unauthorized
    case configuration
    case transport
    case response
    case api(code: String?, fallback: String)

    var userMessage: String {
        switch self {
        case .unauthorized:
            return "로그인이 만료되었습니다. 다시 로그인해 주세요."
        case .configuration:
            return "얼굴 등록 서버 설정이 필요합니다."
        case .transport:
            return "얼굴 등록 서버에 연결하지 못했습니다. 네트워크를 확인해 주세요."
        case .response:
            return "얼굴 등록 서버 응답을 확인하지 못했습니다. 다시 시도해 주세요."
        case let .api(code, fallback):
            switch code {
            case "face_not_detected":
                return "얼굴을 찾지 못했습니다. 얼굴을 정면으로 맞추고 다시 시도해 주세요."
            case "invalid_image":
                return "얼굴 이미지를 처리하지 못했습니다. 다시 촬영해 주세요."
            case "reference_rejected":
                return "한 명의 얼굴이 선명하게 보이도록 맞춘 뒤 다시 시도해 주세요."
            case "ai_unavailable":
                return "얼굴 등록 기능을 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
            case "not_found":
                return "등록된 얼굴을 찾지 못했습니다. 목록을 새로고침해 주세요."
            case "bad_request":
                return "현재 서버에서 얼굴 등록을 사용할 수 없습니다."
            default:
                return fallback
            }
        }
    }
}
