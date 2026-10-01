import Foundation

enum BroadcastSettingsProvider: String, CaseIterable, Codable, Identifiable {
    case youtube, chzzk
    var id: String { rawValue }
    var title: String { self == .youtube ? "YouTube" : "치지직" }
}

struct BroadcastSettingsFieldError: Error, Equatable {
    let field: String
    let reason: String
    var fields: [String] = []
    var isNotChangeableLive = false
    var message: String {
        isNotChangeableLive ? String(localized: "방송 중에는 바꿀 수 없는 항목입니다.") : String(localized: "입력값을 확인해 주세요.")
    }
}

struct CHZZKBroadcastSettings: Codable, Equatable {
    var title = YouTubeBroadcastSettings.defaultTitle()
    var categoryType = ""
    var categoryID = ""
    var tags: [String] = []
    enum CodingKeys: String, CodingKey {
        case title, tags
        case categoryType = "category_type"
        case categoryID = "category_id"
    }
    var validation: [String: String] {
        var errors: [String: String] = [:]
        if title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || title.unicodeScalars.count > 100 {
            errors["title"] = String(localized: "방송 제목을 1~100자로 입력해 주세요.")
        }
        if categoryType.isEmpty != categoryID.isEmpty || (!categoryType.isEmpty && !["GAME", "SPORTS", "ETC"].contains(categoryType)) {
            errors["category_id"] = String(localized: "검색 결과에서 카테고리를 선택해 주세요.")
        }
        if tags.count > 5 || tags.contains(where: { tag in
            tag.isEmpty || tag.unicodeScalars.count > 15 || tag.unicodeScalars.contains {
                !CharacterSet.letters.contains($0) && !CharacterSet.decimalDigits.contains($0)
            }
        }) {
            errors["tags"] = String(localized: "태그는 최대 5개, 각각 15자까지 한글·영문·숫자로 입력해 주세요.")
        }
        return errors
    }
}

struct YouTubeBroadcastCategory: Decodable, Identifiable, Equatable {
    let id: String
    let title: String
}
struct YouTubeCategoryResponse: Decodable { let categories: [YouTubeBroadcastCategory] }
struct CHZZKBroadcastCategory: Decodable, Identifiable, Equatable {
    let categoryType: String
    let categoryID: String
    let categoryValue: String
    var id: String { categoryType + ":" + categoryID }
    enum CodingKeys: String, CodingKey {
        case categoryType = "category_type"
        case categoryID = "category_id"
        case categoryValue = "category_value"
    }
}
struct CHZZKCategoryResponse: Decodable { let categories: [CHZZKBroadcastCategory] }

struct BroadcastSettingsDefaults: Decodable {
    var title: String?
    var description: String?
    var privacy: String?
    var madeForKids: Bool?
    var categoryID: String?
    var categoryType: String?
    var tags: [String]?
    enum CodingKeys: String, CodingKey {
        case title, description, privacy, tags
        case madeForKids = "made_for_kids"
        case categoryID = "category_id"
        case categoryType = "category_type"
    }
}

extension YouTubeBroadcastSettings {
    var validation: [String: String] {
        var errors: [String: String] = [:]
        if title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || title.unicodeScalars.count > Self.maxTitleLength {
            errors["title"] = String(localized: "방송 제목을 1~100자로 입력해 주세요.")
        }
        if description.unicodeScalars.count > Self.maxDescriptionLength {
            errors["description"] = String(localized: "방송 설명은 5,000자까지 입력해 주세요.")
        }
        if audience == nil { errors["made_for_kids"] = String(localized: "시청자층을 선택해 주세요.") }
        if let categoryID, !categoryID.isEmpty,
           !categoryID.utf8.allSatisfy({ (48...57).contains($0) }) {
            errors["category_id"] = String(localized: "목록에서 카테고리를 선택해 주세요.")
        }
        return errors
    }
}
