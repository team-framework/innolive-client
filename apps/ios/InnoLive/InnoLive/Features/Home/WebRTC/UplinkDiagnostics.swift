import Foundation

nonisolated struct UplinkVideoOutboundStats: Equatable, Sendable {
    var reason: String
    var width: Int?
    var height: Int?
    var framesPerSecond: Double?
}

nonisolated enum UplinkQualityKind: String, Equatable, Sendable {
    case limitation
    case resolution
    case heartbeat
}

nonisolated struct UplinkQualityLog: Equatable, Sendable {
    var at: Date
    var kind: UplinkQualityKind
    var reason: String
    var previousReason: String?
    var width: Int?
    var height: Int?
    var previousWidth: Int?
    var previousHeight: Int?
    var framesPerSecond: Double?

    var sizeLine: String { Self.size(width, height) }
    var previousSizeLine: String { Self.size(previousWidth, previousHeight) }

    func line() -> String {
        let fps = framesPerSecond.map { value in
            String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
        } ?? "-"
        return """
        uplink quality at=\(Self.timestamp(at)) kind=\(kind.rawValue) \
        reason=\(reason) previousReason=\(previousReason ?? "-") \
        size=\(sizeLine) previousSize=\(previousSizeLine) fps=\(fps)
        """
    }

    private static func size(_ width: Int?, _ height: Int?) -> String {
        guard let width, let height else { return "-" }
        return "\(width)x\(height)"
    }

    fileprivate static func timestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }
}

nonisolated struct UplinkQualityTracker: Sendable {
    static let heartbeatInterval: TimeInterval = 30
    private(set) var previous: UplinkVideoOutboundStats?
    private(set) var lastLoggedAt: Date?

    mutating func record(_ current: UplinkVideoOutboundStats, at: Date) -> UplinkQualityLog? {
        let prior = previous
        previous = current

        let reasonChanged = prior.map { $0.reason != current.reason } ?? (current.reason != "none")
        let resolutionDropped = Self.resolutionDropped(from: prior, to: current)
        if reasonChanged || resolutionDropped {
            lastLoggedAt = at
            return UplinkQualityLog(
                at: at,
                kind: reasonChanged ? .limitation : .resolution,
                reason: current.reason,
                previousReason: prior?.reason,
                width: current.width,
                height: current.height,
                previousWidth: prior?.width,
                previousHeight: prior?.height,
                framesPerSecond: current.framesPerSecond
            )
        }

        if current.reason != "none",
           let lastLoggedAt,
           at.timeIntervalSince(lastLoggedAt) >= Self.heartbeatInterval {
            self.lastLoggedAt = at
            return UplinkQualityLog(
                at: at,
                kind: .heartbeat,
                reason: current.reason,
                previousReason: prior?.reason,
                width: current.width,
                height: current.height,
                previousWidth: prior?.width,
                previousHeight: prior?.height,
                framesPerSecond: current.framesPerSecond
            )
        }
        return nil
    }

    private static func resolutionDropped(from prior: UplinkVideoOutboundStats?, to current: UplinkVideoOutboundStats) -> Bool {
        guard let prior,
              let previousWidth = prior.width,
              let previousHeight = prior.height,
              let width = current.width,
              let height = current.height else { return false }
        return width * height < previousWidth * previousHeight
    }
}

nonisolated enum UplinkVideoStatsParser {
    static func videoOutbound(statistics: [(type: String, values: [String: Any])]) -> UplinkVideoOutboundStats? {
        let video = statistics.first { entry in
            guard entry.type == "outbound-rtp" else { return false }
            let kind = stringValue(entry.values["kind"]) ?? stringValue(entry.values["mediaType"])
            return kind == "video"
        }
        guard let video else { return nil }
        let reason = stringValue(video.values["qualityLimitationReason"]).flatMap { $0.isEmpty ? nil : $0 } ?? "none"
        return UplinkVideoOutboundStats(
            reason: reason,
            width: intValue(video.values["frameWidth"]),
            height: intValue(video.values["frameHeight"]),
            framesPerSecond: doubleValue(video.values["framesPerSecond"])
        )
    }

    private static func stringValue(_ value: Any?) -> String? {
        switch value {
        case let text as String:
            return text
        case let text as NSString:
            return text as String
        default:
            return nil
        }
    }

    private static func intValue(_ value: Any?) -> Int? {
        switch value {
        case let number as NSNumber:
            return number.intValue
        case let number as Int:
            return number
        default:
            return nil
        }
    }

    private static func doubleValue(_ value: Any?) -> Double? {
        switch value {
        case let number as NSNumber:
            return number.doubleValue
        case let number as Double:
            return number
        case let number as Int:
            return Double(number)
        default:
            return nil
        }
    }
}

nonisolated enum UplinkRuntimeEvent: Equatable, Sendable {
    case enteredBackground
    case leftBackground
    case deviceLocked
    case deviceUnlocked
    case networkChanged(from: String, to: String)
}

nonisolated struct UplinkRuntimeSample: Equatable, Sendable {
    var at: Date
    var event: UplinkRuntimeEvent
}

nonisolated struct UplinkDisconnectLog: Equatable, Sendable {
    var at: Date
    var trigger: String
    var lastAliveAt: Date?
    var callbackDelayed: Bool
    var background: Bool
    var screenOff: Bool
    var networkChanged: Bool
    var networkFrom: String?
    var networkTo: String?

    func line() -> String {
        let alive = lastAliveAt.map(UplinkQualityLog.timestamp) ?? "-"
        let network: String
        if networkChanged, let networkFrom, let networkTo {
            network = "\(networkFrom)->\(networkTo)"
        } else {
            network = "-"
        }
        return """
        uplink disconnect at=\(UplinkQualityLog.timestamp(at)) trigger=\(trigger) \
        lastAliveAt=\(alive) callbackDelayed=\(callbackDelayed) \
        background=\(background) screenOff=\(screenOff) \
        networkChanged=\(networkChanged) network=\(network)
        """
    }
}

nonisolated struct UplinkDisconnectContext: Sendable {
    static let correlationWindow: TimeInterval = 15
    static let delayedCallbackThreshold: TimeInterval = 5
    private static let retention: TimeInterval = 60

    private(set) var events: [UplinkRuntimeSample] = []
    private(set) var inBackground: Bool
    private(set) var deviceLocked: Bool
    private(set) var pathSignature: String?
    private(set) var lastAliveAt: Date?
    private(set) var loggedCurrentDrop = false

    init(inBackground: Bool, deviceLocked: Bool) {
        self.inBackground = inBackground
        self.deviceLocked = deviceLocked
    }

    mutating func record(_ event: UplinkRuntimeEvent, at: Date) {
        switch event {
        case .enteredBackground:
            inBackground = true
        case .leftBackground:
            inBackground = false
        case .deviceLocked:
            deviceLocked = true
        case .deviceUnlocked:
            deviceLocked = false
        case .networkChanged:
            break
        }
        events.append(UplinkRuntimeSample(at: at, event: event))
        prune(now: at)
    }

    mutating func notePath(_ signature: String, at: Date) {
        if let pathSignature, pathSignature != signature {
            record(.networkChanged(from: pathSignature, to: signature), at: at)
        }
        pathSignature = signature
        prune(now: at)
    }

    mutating func noteAlive(at: Date) {
        lastAliveAt = at
    }

    mutating func noteReconnected() {
        loggedCurrentDrop = false
    }

    mutating func disconnect(trigger: String, at: Date) -> UplinkDisconnectLog? {
        guard !loggedCurrentDrop else { return nil }
        loggedCurrentDrop = true
        let recent = events.filter { at.timeIntervalSince($0.at) <= Self.correlationWindow && $0.at <= at }
        let background = inBackground || recent.contains { sample in
            if case .enteredBackground = sample.event { return true }
            return false
        }
        let screenOff = deviceLocked || recent.contains { sample in
            if case .deviceLocked = sample.event { return true }
            return false
        }
        var networkFrom: String?
        var networkTo: String?
        for sample in recent {
            if case let .networkChanged(from, to) = sample.event {
                networkFrom = from
                networkTo = to
            }
        }
        let callbackDelayed = lastAliveAt.map { at.timeIntervalSince($0) > Self.delayedCallbackThreshold } ?? false
        return UplinkDisconnectLog(
            at: at,
            trigger: trigger,
            lastAliveAt: lastAliveAt,
            callbackDelayed: callbackDelayed,
            background: background,
            screenOff: screenOff,
            networkChanged: networkFrom != nil,
            networkFrom: networkFrom,
            networkTo: networkTo
        )
    }

    private mutating func prune(now: Date) {
        events.removeAll { now.timeIntervalSince($0.at) > Self.retention }
    }
}
