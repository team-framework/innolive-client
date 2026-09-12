import CoreGraphics

enum CameraInputSwipe {
    static let minimumVerticalDistance: CGFloat = 60

    static func shouldSwitch(translation: CGSize) -> Bool {
        let horizontal = abs(translation.width)
        let vertical = abs(translation.height)
        return vertical >= minimumVerticalDistance && vertical > horizontal
    }
}
