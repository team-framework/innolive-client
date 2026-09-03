import Foundation

enum AuthenticationConfiguration {
    static func serverURL(path: String) -> URL? {
        guard let value = configuredServerURL,
              var components = URLComponents(string: value),
              components.scheme == "https" || components.scheme == "http" else { return nil }
        components.path = path
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private static var configuredServerURL: String? {
        nonEmptyValue(ProcessInfo.processInfo.environment["INNOLIVE_SERVER_URL"])
            ?? serverURLFromBundleEnvironmentFile()
            ?? nonEmptyValue(Bundle.main.object(forInfoDictionaryKey: "InnoLiveServerURL") as? String)
                .flatMap { $0.contains("$(") ? nil : $0 }
    }

    private static func serverURLFromBundleEnvironmentFile() -> String? {
        guard let url = Bundle.main.url(forResource: "Server", withExtension: "env")
            ?? Bundle.main.url(forResource: "Server", withExtension: "env", subdirectory: "Config"),
              let content = try? String(contentsOf: url, encoding: .utf8) else { return nil }

        return content
            .split(whereSeparator: \.isNewline)
            .compactMap { line -> String? in
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty,
                      !trimmed.hasPrefix("#"),
                      let separator = trimmed.firstIndex(of: "="),
                      trimmed[..<separator].trimmingCharacters(in: .whitespacesAndNewlines) == "INNOLIVE_SERVER_URL" else { return nil }
                return Self.nonEmptyValue(String(trimmed[trimmed.index(after: separator)...]))
            }
            .first
    }

    private static func nonEmptyValue(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.count >= 2,
           let first = trimmed.first,
           let last = trimmed.last,
           (first == "\"" && last == "\"") || (first == "'" && last == "'") {
            return String(trimmed.dropFirst().dropLast())
        }
        return trimmed
    }
}
