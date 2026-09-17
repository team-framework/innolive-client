import Foundation

struct SignupConsent {
    private(set) var hasReadToEnd = false
    private(set) var didAccept = false

    var isAccepted: Bool { hasReadToEnd && didAccept }

    mutating func reachedEnd() { hasReadToEnd = true }

    static func scrollPosition(
        contentHeight: CGFloat,
        visibleHeight: CGFloat,
        offset: CGFloat,
        topInset: CGFloat,
        bottomInset _: CGFloat
    ) -> (contentHeight: CGFloat, visibleHeight: CGFloat, offset: CGFloat) {
        // Top inset makes contentOffset.y negative at rest. Bottom inset is the
        // fixed action bar, not unread content — adding it makes the end unreachable.
        (contentHeight, visibleHeight, offset + topInset)
    }

    static func hasReachedEnd(contentHeight: CGFloat, visibleHeight: CGFloat, offset: CGFloat) -> Bool {
        guard contentHeight > 0, visibleHeight > 0 else { return false }
        return offset + visibleHeight >= contentHeight - 1
    }

    mutating func observeScroll(contentHeight: CGFloat, visibleHeight: CGFloat, offset: CGFloat) {
        if Self.hasReachedEnd(contentHeight: contentHeight, visibleHeight: visibleHeight, offset: offset) {
            reachedEnd()
        }
    }

    mutating func accept() {
        guard hasReadToEnd else { return }
        didAccept = true
    }
}

struct EmailSignupConsent {
    private(set) var policy = SignupConsent()
    private(set) var isChecked = false

    var canSubmit: Bool { policy.isAccepted && isChecked }

    mutating func recordSheetAcceptance(_ consent: SignupConsent) {
        guard consent.isAccepted else { return }
        policy = consent
        isChecked = true
    }

    mutating func setChecked(_ checked: Bool) {
        isChecked = policy.isAccepted && checked
    }

    mutating func reset() {
        self = EmailSignupConsent()
    }
}
