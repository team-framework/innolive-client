import Foundation

enum InnoLiveLinks {
    static let privacyPolicyURL: URL? = {
        let preferredLanguage = Bundle.main.preferredLocalizations.first ?? "ko"
        let language = ["ko", "en", "ja"].contains(preferredLanguage) ? preferredLanguage : "ko"
        guard let url = URL(string: "https://innolive.studio/\(language)/privacy"),
              url.scheme == "https",
              url.host == "innolive.studio" else { return nil }
        return url
    }()
}
