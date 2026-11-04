import Foundation

/// 화면 이동 전용이다. 페이지를 골라도 방송 수명 주기는 바뀌지 않는다.
enum StudioPage: Int, CaseIterable, Identifiable {
    case preview, basic, details

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .preview: String(localized: "화면")
        case .basic: String(localized: "기본")
        case .details: String(localized: "방송 설정")
        }
    }

    static func destination(from current: StudioPage, requested: StudioPage, isLocked: Bool) -> StudioPage {
        isLocked ? current : requested
    }

    var previous: StudioPage { StudioPage(rawValue: rawValue - 1) ?? self }
    var next: StudioPage { StudioPage(rawValue: rawValue + 1) ?? self }
}
