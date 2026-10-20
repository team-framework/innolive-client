import Foundation

enum SimulatorVideoInput {
    #if DEBUG
    static let isPerformanceFixture = ProcessInfo.processInfo.arguments.contains("--privacy-video-fixture")
    #if targetEnvironment(simulator)
    static let isEnabled = true
    #else
    static let isEnabled = isPerformanceFixture
    #endif
    #else
    static let isPerformanceFixture = false
    static let isEnabled = false
    #endif

    static let resourceName = isPerformanceFixture ? "PrivacyPerformanceFixture" : "SimulatorFixture"
    static let resourceExtension = "mp4"
    static let fileName = "\(resourceName).\(resourceExtension)"

    static var bundledURL: URL? {
        guard isEnabled else { return nil }
        return Bundle.main.url(
            forResource: resourceName,
            withExtension: resourceExtension
        )
    }
}
