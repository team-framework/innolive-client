import Foundation

struct YouTubeTransmissionNotice {
    private(set) var acknowledgement = SignupConsent()

    var canPrepare: Bool { acknowledgement.isAccepted }

    mutating func record(_ consent: SignupConsent) {
        guard consent.isAccepted else { return }
        acknowledgement = consent
    }

    mutating func reset() {
        self = YouTubeTransmissionNotice()
    }
}
