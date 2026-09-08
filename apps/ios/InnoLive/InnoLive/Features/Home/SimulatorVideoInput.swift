import Foundation

enum SimulatorVideoInput {
    #if DEBUG
    #if targetEnvironment(simulator)
    static let isEnabled = true
    #else
    static let isEnabled = false
    #endif
    #else
    static let isEnabled = false
    #endif

    static let resourceName = "SimulatorFixture"
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
