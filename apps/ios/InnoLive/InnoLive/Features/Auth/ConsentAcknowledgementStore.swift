import Foundation

final class ConsentAcknowledgementStore {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private enum Key {
        static let mediaTransmission = "com.framework.innolive.consent.media-transmission.v1"
        static let youtubeTransmission = "com.framework.innolive.consent.youtube-transmission.v1"
    }

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    var hasAcceptedMediaTransmission: Bool {
        userDefaults.bool(forKey: Key.mediaTransmission)
    }

    func recordMediaTransmission() {
        userDefaults.set(true, forKey: Key.mediaTransmission)
    }

    func clearMediaTransmission() {
        userDefaults.removeObject(forKey: Key.mediaTransmission)
    }

    var hasAcknowledgedYouTubeTransmission: Bool {
        userDefaults.bool(forKey: Key.youtubeTransmission)
    }

    func recordYouTubeTransmission() {
        userDefaults.set(true, forKey: Key.youtubeTransmission)
    }

    func clearYouTubeTransmission() {
        userDefaults.removeObject(forKey: Key.youtubeTransmission)
    }
}
