import Foundation

enum MediaSourceSelection: Equatable {
    case camera
    case file(URL)
}

@MainActor
enum MediaSourceDebugConfiguration {
    #if DEBUG
    private static var importedFileURL: URL?
    private static var selectionOverride: MediaSourceSelection?
    #endif

    static func selection() -> MediaSourceSelection {
        #if DEBUG
        if let selectionOverride {
            return selectionOverride
        }
        if let importedFileURL {
            return .file(importedFileURL)
        }
        if let launchURL = launchFileURL() {
            return .file(launchURL)
        }
        #if targetEnvironment(simulator)
        if let bundledURL = Bundle.main.url(forResource: "SimulatorFixture", withExtension: "mp4") {
            return .file(bundledURL)
        }
        #endif
        #endif
        return .camera
    }

    #if DEBUG
    static var opensPreviewHarness: Bool {
        let arguments = ProcessInfo.processInfo.arguments
        return arguments.contains("-INNOLIVE_DEBUG_MEDIA_PREVIEW")
            || ProcessInfo.processInfo.environment["INNOLIVE_DEBUG_MEDIA_PREVIEW"] == "1"
    }

    static func setImportedFileURL(_ url: URL?) {
        importedFileURL = url
        selectionOverride = url.map(MediaSourceSelection.file)
    }

    static func selectCamera() {
        importedFileURL = nil
        selectionOverride = .camera
    }

    private static func launchFileURL() -> URL? {
        let arguments = ProcessInfo.processInfo.arguments
        if let index = arguments.firstIndex(of: "-INNOLIVE_MEDIA_URL"),
           arguments.indices.contains(arguments.index(after: index)) {
            let url = URL(fileURLWithPath: arguments[arguments.index(after: index)])
            return url
        }
        if let path = ProcessInfo.processInfo.environment["INNOLIVE_MEDIA_URL"] {
            let url = URL(fileURLWithPath: path)
            return url
        }
        return nil
    }
    #endif
}
