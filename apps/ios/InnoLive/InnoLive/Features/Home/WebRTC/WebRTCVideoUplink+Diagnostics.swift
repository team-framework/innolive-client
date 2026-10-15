import Foundation
import Network
import os
import UIKit
@preconcurrency import LiveKitWebRTC

@MainActor
final class UplinkDiagnosticsMonitor {
    private let logger = Logger(subsystem: "com.framework.innolive", category: "uplink")
    private var tracker = UplinkQualityTracker()
    private var context = UplinkDisconnectContext(inBackground: false, deviceLocked: false)
    private var observers: [NSObjectProtocol] = []

    func start(inBackground: Bool, deviceLocked: Bool) {
        context = UplinkDisconnectContext(inBackground: inBackground, deviceLocked: deviceLocked)
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observe(center, UIApplication.didEnterBackgroundNotification, .enteredBackground)
        observe(center, UIApplication.willEnterForegroundNotification, .leftBackground)
        observe(center, UIApplication.protectedDataWillBecomeUnavailableNotification, .deviceLocked)
        observe(center, UIApplication.protectedDataDidBecomeAvailableNotification, .deviceUnlocked)
    }

    func notePath(_ path: NWPath, at: Date = Date()) {
        context.notePath(uplinkPathSignature(path), at: at)
    }

    func noteConnected() {
        context.noteReconnected()
        resetQualityTracker()
    }

    @discardableResult
    func noteStats(_ statistics: [(type: String, values: [String: Any])], at: Date = Date()) -> UplinkVideoOutboundStats? {
        guard let sample = UplinkVideoStatsParser.videoOutbound(statistics: statistics) else { return nil }
        context.noteAlive(at: at)
        if let log = tracker.record(sample, at: at) {
            let line = log.line()
            logger.notice("\(line, privacy: .public)")
        }
        return sample
    }

    func noteDisconnect(trigger: String, at: Date = Date()) {
        guard let log = context.disconnect(trigger: trigger, at: at) else { return }
        let line = log.line()
        logger.notice("\(line, privacy: .public)")
    }

    func resetQualityTracker() {
        tracker = UplinkQualityTracker()
    }

    func stopObserving() {
        let center = NotificationCenter.default
        for token in observers {
            center.removeObserver(token)
        }
        observers.removeAll()
    }

    deinit {
        let center = NotificationCenter.default
        for token in observers {
            center.removeObserver(token)
        }
    }

    private func observe(
        _ center: NotificationCenter,
        _ name: Notification.Name,
        _ event: UplinkRuntimeEvent
    ) {
        let token = center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.context.record(event, at: Date())
            }
        }
        observers.append(token)
    }
}

private func uplinkPathSignature(_ path: NWPath) -> String {
    let status: String
    switch path.status {
    case .satisfied:
        status = "satisfied"
    case .unsatisfied:
        status = "unsatisfied"
    case .requiresConnection:
        status = "requiresConnection"
    @unknown default:
        status = "unknown"
    }
    let names = path.availableInterfaces.map { interface -> String in
        switch interface.type {
        case .wifi:
            return "wifi"
        case .cellular:
            return "cellular"
        case .wiredEthernet:
            return "wired"
        case .loopback:
            return "loopback"
        case .other:
            return "other"
        @unknown default:
            return "other"
        }
    }.sorted()
    let interfaces = names.isEmpty ? "none" : names.joined(separator: "+")
    return "\(status):\(interfaces)"
}

extension WebRTCVideoUplink {
    func startQualityPollingIfNeeded() {
        guard qualityPollTask == nil else { return }
        qualityPollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, !self.isStopping,
                      let peerConnection = self.peerConnection,
                      let videoSender = self.videoSender else { return }
                let statistics = await self.senderStatistics(
                    peerConnection: peerConnection,
                    sender: videoSender
                )
                guard !Task.isCancelled, !self.isStopping else { return }
                if let sample = self.diagnostics.noteStats(statistics) {
                    self.recordUplinkQuality(sample)
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    func senderStatistics(
        peerConnection: LKRTCPeerConnection,
        sender: LKRTCRtpSender
    ) async -> [(type: String, values: [String: Any])] {
        await withCheckedContinuation { continuation in
            peerConnection.statistics(for: sender) { report in
                let statistics = report.statistics.values.map { statistic in
                    var values: [String: Any] = [:]
                    for (key, value) in statistic.values {
                        values[key] = value
                    }
                    return (type: statistic.type, values: values)
                }
                continuation.resume(returning: statistics)
            }
        }
    }
}
