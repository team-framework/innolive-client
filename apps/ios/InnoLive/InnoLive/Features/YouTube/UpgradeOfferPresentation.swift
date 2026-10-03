import Foundation

extension BroadcastUpgradeOffer {
    var expirationDate: Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: expiresAt) ?? ISO8601DateFormatter().date(from: expiresAt)
    }

    func isExpired(at date: Date = Date()) -> Bool {
        guard let expirationDate else { return true }
        return expirationDate <= date
    }

    var selectedOption: BroadcastUpgradeOption? { options?.first { $0.mode == selected } }
}

extension BroadcastUpgradeOption {
    var supportedTargets: Set<BroadcastSettingsProvider>? {
        let providers = targets.compactMap(BroadcastSettingsProvider.init(rawValue:))
        guard !providers.isEmpty, providers.count == targets.count,
              BroadcastPlanMode(rawValue: mode) != nil, ["720p", "fhd"].contains(resolution) else { return nil }
        return Set(providers)
    }

    var resolutionTitle: String { resolution == "fhd" ? "FHD (1080p)" : resolution }
    var platformsTitle: String {
        targets.map { BroadcastSettingsProvider(rawValue: $0)?.title ?? $0 }.joined(separator: " · ")
    }

    var remainingTimeTitle: String {
        switch remainingSecondsAfter {
        case .seconds(let seconds):
            return String(localized: "전환 후 남은 시간: \(max(0, seconds) / 60)분", table: "UpgradeOffer")
        case .unlimitedOrInactive:
            return String(localized: "전환 후 시간 제한 없음", table: "UpgradeOffer")
        case .missing:
            return String(localized: "전환 후 남은 시간 확인 필요", table: "UpgradeOffer")
        }
    }

    var restartMessage: String {
        var lines = [String(localized: "방송을 다시 시작하며 잠시 끊길 수 있습니다.", table: "UpgradeOffer")]
        for effect in restartEffects {
            let provider = BroadcastSettingsProvider(rawValue: effect.provider)?.title ?? effect.provider
            let link = effect.sameLink
                ? String(localized: "같은 시청 링크 유지", table: "UpgradeOffer")
                : String(localized: "새 시청 링크 생성", table: "UpgradeOffer")
            var line = provider + " · " + link
            if let seconds = effect.gapSeconds {
                line += " · " + String(localized: "약 \(max(0, seconds))초 송출 공백", table: "UpgradeOffer")
            }
            lines.append(line)
        }
        return lines.joined(separator: "\n\n")
    }
}
