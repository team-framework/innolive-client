import Foundation

enum InnoLiveLinks {
    static let privacyPolicyURL: URL? = {
        guard let url = URL(string: "https://innolive.studio/ko/privacy"),
              url.scheme == "https",
              url.host == "innolive.studio" else { return nil }
        return url
    }()
}
