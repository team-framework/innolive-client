import Foundation

nonisolated struct OnDeviceMosaicSettings: Equatable, Codable, Sendable {
    static let defaultBlurStrength = 24
    // 72는 기본값을 낮추기 전의 기본 강도(1080p sigma 72)와 같다.
    static let maxBlurStrength = 72
    static let blurStrengthStep = 6
    static let blurStrengthRange = defaultBlurStrength...maxBlurStrength
    static let defaultMaskMargin = 8
    static let maskMarginRange = 8...64

    var blurStrength: Int
    var maskMargin: Int

    private enum Key {
        static let blurStrength = "com.framework.innolive.on-device-mosaic.blur-strength"
        static let maskMargin = "com.framework.innolive.on-device-mosaic.mask-margin"
    }

    init(blurStrength: Int = defaultBlurStrength, maskMargin: Int = defaultMaskMargin) {
        self.blurStrength = blurStrength
        self.maskMargin = maskMargin
    }

    func normalized() -> Self {
        Self(blurStrength: PrivacyModel.strengthLevel(blurStrength),
             maskMargin: Self.clamp(maskMargin, Self.maskMarginRange))
    }

    static func load(from defaults: UserDefaults = .standard) -> Self {
        Self(blurStrength: defaults.object(forKey: Key.blurStrength) as? Int ?? defaultBlurStrength,
             maskMargin: defaults.object(forKey: Key.maskMargin) as? Int ?? defaultMaskMargin).normalized()
    }

    func save(to defaults: UserDefaults = .standard) {
        let normalized = normalized()
        defaults.set(normalized.blurStrength, forKey: Key.blurStrength)
        defaults.set(normalized.maskMargin, forKey: Key.maskMargin)
    }

    private static func clamp(_ value: Int, _ range: ClosedRange<Int>) -> Int {
        min(max(value, range.lowerBound), range.upperBound)
    }
}
