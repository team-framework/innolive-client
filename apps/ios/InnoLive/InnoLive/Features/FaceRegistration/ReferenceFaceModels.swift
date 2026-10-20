import Foundation

nonisolated struct ReferenceFaceStatus: Decodable, Equatable, Sendable {
    let registered: Bool
    let source: String?
    let registeredAt: String?
    let clientID: String
    let count: Int
    let faces: [ReferenceFace]
    /// AI 워커가 재시작해 잃은 얼굴이 하나라도 있으면 서버가 true로 보낸다. 이 필드가 없는 옛 서버는 false로 읽는다.
    var reregistrationRequired = false

    enum CodingKeys: String, CodingKey {
        case registered
        case source
        case registeredAt = "registered_at"
        case clientID = "client_id"
        case count
        case faces
        case reregistrationRequired = "reregistration_required"
    }
}

extension ReferenceFaceStatus {
    // 디코더를 extension에 두어 온디바이스 저장소가 쓰는 멤버별 생성자를 유지한다.
    nonisolated init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        registered = try container.decode(Bool.self, forKey: .registered)
        source = try container.decodeIfPresent(String.self, forKey: .source)
        registeredAt = try container.decodeIfPresent(String.self, forKey: .registeredAt)
        clientID = try container.decode(String.self, forKey: .clientID)
        count = try container.decode(Int.self, forKey: .count)
        faces = try container.decode([ReferenceFace].self, forKey: .faces)
        reregistrationRequired = try container.decodeIfPresent(Bool.self, forKey: .reregistrationRequired) ?? false
    }
}

nonisolated struct ReferenceFace: Decodable, Equatable, Identifiable, Sendable {
    let id: String
    var name: String? = nil
    let registeredAt: String
    /// 이 얼굴을 AI 워커가 잃어 블러에서 빠지지 않는다. 같은 face_id로 사진을 다시 올리면 풀린다.
    var reregistrationRequired = false

    enum CodingKeys: String, CodingKey {
        case name
        case id = "face_id"
        case registeredAt = "registered_at"
        case reregistrationRequired = "reregistration_required"
    }
}

extension ReferenceFace {
    nonisolated init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        registeredAt = try container.decode(String.self, forKey: .registeredAt)
        reregistrationRequired = try container.decodeIfPresent(Bool.self, forKey: .reregistrationRequired) ?? false
    }
}

nonisolated enum ReferenceFaceReregistration {
    static var notice: String {
        String(localized: "AI 서버 재시작으로 등록이 초기화되어, 다시 등록해야 블러에서 제외됩니다.")
    }

    /// 서버가 같은 face_id의 얼굴을 다시 등록하는 multipart 본문이다. 이름을 보내지 않아 기존 이름을 유지한다.
    /// `image`가 아닌 `images`로 보내 face_id를 모르는 서버에서도 얼굴 집합 전체를 교체하지 않게 한다.
    static func multipartBody(faceID: String, jpegData: Data, boundary: String) -> Data {
        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"face_id\"\r\n\r\n".utf8))
        body.append(Data((faceID + "\r\n").utf8))
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"images\"; filename=\"reference-face.jpg\"\r\n".utf8))
        body.append(Data("Content-Type: image/jpeg\r\n\r\n".utf8))
        body.append(jpegData)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return body
    }
}

enum ReferenceFaceAPIError: Error, Equatable {
    case unauthorized
    case configuration
    case transport
    case response
    case api(code: String?, fallback: String)

    /// 서버에 그 얼굴이 없다(404 `not_found`). 다시 등록 중이면 목록을 새로 읽는다.
    var isNotFound: Bool {
        if case let .api(code, _) = self { return code == "not_found" }
        return false
    }

    var userMessage: String {
        switch self {
        case .unauthorized:
            return String(localized: "로그인이 만료되었습니다. 다시 로그인해 주세요.")
        case .configuration:
            return String(localized: "얼굴 등록 서버 설정이 필요합니다.")
        case .transport:
            return String(localized: "얼굴 등록 서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.")
        case .response:
            return String(localized: "얼굴 등록 서버 응답을 확인하지 못했습니다. 다시 시도해 주세요.")
        case let .api(code, fallback):
            switch code {
            case "face_not_detected":
                return String(localized: "얼굴을 찾지 못했습니다. 얼굴을 정면으로 맞추고 다시 시도해 주세요.")
            case "invalid_image":
                return String(localized: "얼굴 이미지를 처리하지 못했습니다. 다시 촬영해 주세요.")
            case "reference_rejected":
                return String(localized: "한 명의 얼굴이 선명하게 보이도록 맞춘 뒤 다시 시도해 주세요.")
            case "ai_unavailable":
                return String(localized: "얼굴 등록 기능을 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.")
            case "not_found":
                return String(localized: "등록된 얼굴을 찾지 못했습니다. 목록을 새로고침해 주세요.")
            case "bad_request":
                return String(localized: "현재 서버에서 얼굴 등록을 사용할 수 없습니다.")
            default:
                return fallback
            }
        }
    }
}

nonisolated enum ReferenceFaceName {
    static func isValid(_ name: String) -> Bool {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return !value.isEmpty && value.unicodeScalars.count <= 40
            && value.rangeOfCharacter(from: CharacterSet(charactersIn: "\r\n\0")) == nil
    }
}
