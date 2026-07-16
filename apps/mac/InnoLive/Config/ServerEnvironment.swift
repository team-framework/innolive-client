//
//  ServerEnvironment.swift
//  InnoLive
//
//  Created by Codex on 7/6/26.
//

import Foundation

struct ServerEnvironment {
    static let current = ServerEnvironment()

    private enum EnvKey {
        static let envFile = "INNOLIVE_ENV_FILE"
        static let serverURL = "INNOLIVE_SERVER_URL"
        static let signalingURL = "INNOLIVE_SIGNALING_URL"
        static let processedVideoURL = "INNOLIVE_PROCESSED_VIDEO_URL"
    }

    private static let fallbackServerURLString = "http://127.0.0.1:8000"

    let httpBaseURL: URL
    let signalingURL: URL
    let processedVideoURL: URL
    let isConfiguredExternally: Bool

    var httpBaseURLString: String {
        normalizedString(httpBaseURL)
    }

    var signalingURLString: String {
        normalizedString(signalingURL)
    }

    var processedVideoURLString: String {
        normalizedString(processedVideoURL)
    }

    init(values: [String: String] = ServerEnvironment.loadValues()) {
        isConfiguredExternally = values.keys.contains { key in
            key.hasPrefix("INNOLIVE_")
        }

        httpBaseURL = ServerEnvironment.makeHTTPBaseURL(
            from: values[EnvKey.serverURL]
        )
        signalingURL = ServerEnvironment.makeSignalingURL(
            override: values[EnvKey.signalingURL],
            baseURL: httpBaseURL
        )
        processedVideoURL = ServerEnvironment.makeHTTPURL(
            from: values[EnvKey.processedVideoURL],
            fallback: httpBaseURL
        )
    }

    func serverURL(path: String) -> URL? {
        var components = URLComponents(url: httpBaseURL, resolvingAgainstBaseURL: false)
        components?.path = path.hasPrefix("/") ? path : "/\(path)"
        components?.query = nil
        components?.fragment = nil
        return components?.url
    }

    private static func makeHTTPBaseURL(from value: String?) -> URL {
        makeHTTPURL(from: value, fallback: URL(string: fallbackServerURLString)!)
    }

    private static func makeHTTPURL(from value: String?, fallback: URL) -> URL {
        guard let value,
              let url = url(from: value),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return fallback
        }

        switch components.scheme?.lowercased() {
        case "ws":
            components.scheme = "http"
        case "wss":
            components.scheme = "https"
        case "http", "https":
            break
        default:
            return fallback
        }

        components.path = components.path == "/" ? "" : components.path
        components.query = nil
        components.fragment = nil
        return components.url ?? fallback
    }

    private static func makeSignalingURL(override: String?, baseURL: URL) -> URL {
        if let override,
           let url = url(from: override),
           var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            switch components.scheme?.lowercased() {
            case "http":
                components.scheme = "ws"
            case "https":
                components.scheme = "wss"
            case "ws", "wss":
                break
            default:
                return makeSignalingURL(override: nil, baseURL: baseURL)
            }

            if components.path.isEmpty || components.path == "/" {
                components.path = "/signaling"
            }
            components.query = nil
            components.fragment = nil
            return components.url ?? makeSignalingURL(override: nil, baseURL: baseURL)
        }

        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        components?.scheme = baseURL.scheme == "https" ? "wss" : "ws"
        components?.path = "/signaling"
        components?.query = nil
        components?.fragment = nil
        return components?.url ?? makeSignalingURL(
            override: nil,
            baseURL: URL(string: fallbackServerURLString)!
        )
    }

    private static func url(from value: String) -> URL? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return nil
        }

        if trimmed.contains("://") {
            return URL(string: trimmed)
        }
        return URL(string: "http://\(trimmed)")
    }

    private static func loadValues() -> [String: String] {
        var values: [String: String] = [:]

        if let envURL = candidateEnvFileURLs().first(where: { FileManager.default.fileExists(atPath: $0.path) }) {
            values.merge(parseEnvFile(at: envURL)) { _, newValue in newValue }
        }

        for (key, value) in ProcessInfo.processInfo.environment where key.hasPrefix("INNOLIVE_") {
            values[key] = value
        }

        return values
    }

    private static func candidateEnvFileURLs() -> [URL] {
        var urls: [URL] = []

        if let explicitEnvFilePath = ProcessInfo.processInfo.environment[EnvKey.envFile],
           !explicitEnvFilePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            urls.append(URL(fileURLWithPath: explicitEnvFilePath))
        }

        if let bundleURL = Bundle.main.url(forResource: ".env", withExtension: nil) {
            urls.append(bundleURL)
        }
        if let bundleURL = Bundle.main.url(forResource: "Server", withExtension: "env") {
            urls.append(bundleURL)
        }
        if let bundleURL = Bundle.main.url(forResource: "Server", withExtension: "env", subdirectory: "Config") {
            urls.append(bundleURL)
        }

        let currentDirectoryURL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true)
        urls.append(contentsOf: envFileURLs(from: currentDirectoryURL))

        let sourceRootURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        urls.append(sourceRootURL.appendingPathComponent(".env"))

        return urls.reduce(into: []) { result, url in
            if !result.contains(url) {
                result.append(url)
            }
        }
    }

    private static func envFileURLs(from directoryURL: URL) -> [URL] {
        var urls: [URL] = []
        var currentURL = directoryURL.standardizedFileURL

        while true {
            urls.append(currentURL.appendingPathComponent(".env"))
            let parentURL = currentURL.deletingLastPathComponent()
            if parentURL.path == currentURL.path {
                break
            }
            currentURL = parentURL
        }

        return urls
    }

    private static func parseEnvFile(at url: URL) -> [String: String] {
        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            return [:]
        }

        return content
            .split(whereSeparator: \.isNewline)
            .reduce(into: [:]) { values, line in
                guard let pair = parseEnvLine(String(line)) else {
                    return
                }

                values[pair.key] = pair.value
            }
    }

    private static func parseEnvLine(_ line: String) -> (key: String, value: String)? {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("#"), let separatorIndex = trimmed.firstIndex(of: "=") else {
            return nil
        }

        let key = trimmed[..<separatorIndex].trimmingCharacters(in: .whitespacesAndNewlines)
        var value = trimmed[trimmed.index(after: separatorIndex)...].trimmingCharacters(in: .whitespacesAndNewlines)

        if value.count >= 2,
           let first = value.first,
           let last = value.last,
           (first == "\"" && last == "\"") || (first == "'" && last == "'") {
            value.removeFirst()
            value.removeLast()
        }

        guard !key.isEmpty else {
            return nil
        }

        return (key, value)
    }

    private func normalizedString(_ url: URL) -> String {
        let value = url.absoluteString
        guard value.count > 1, value.hasSuffix("/") else {
            return value
        }
        return String(value.dropLast())
    }
}
