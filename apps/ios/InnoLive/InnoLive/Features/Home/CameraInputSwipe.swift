import CoreGraphics

enum CameraInputSwipe {
    static let minimumHorizontalDistance: CGFloat = 60

    static func shouldSwitch(translation: CGSize) -> Bool {
        let horizontal = abs(translation.width)
        let vertical = abs(translation.height)
        return horizontal >= minimumHorizontalDistance && horizontal > vertical
    }
}
