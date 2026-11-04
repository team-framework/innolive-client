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
        // 위쪽 inset 때문에 멈춰 있을 때 contentOffset.y는 음수다. 아래쪽 inset은 읽지 않은
        // 내용이 아니라 고정된 버튼 영역이다. 이를 더하면 끝에 도달할 수 없다.
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
