import Combine
import Foundation
import Network

/// Feeds path changes and server probes into `OperatingState`. Runs only for administrators, because local mode is admin-only.
@MainActor
final class OperatingModeMonitor: ObservableObject {
    nonisolated deinit {}

    static let alwaysLocalKey = "com.framework.innolive.always-local-mode"
    static let probeTimeout: TimeInterval = 3
    private static let serverProbeInterval: Duration = .seconds(60)
    private static let suspectProbeInterval: Duration = .seconds(2)
    private static let localProbeIntervals: [Duration] = [.seconds(2), .seconds(4), .seconds(8), .seconds(16), .seconds(30)]

    @Published private(set) var state: OperatingState
    @Published private(set) var isMonitoring = false
    private let probeSession: URLSession
    private var pathMonitor: NWPathMonitor?
    private var loop: Task<Void, Never>?
    private var accessToken: () -> String? = { nil }

    init(defaults: UserDefaults = .standard) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = Self.probeTimeout
        configuration.timeoutIntervalForResource = Self.probeTimeout
        configuration.waitsForConnectivity = false
        probeSession = URLSession(configuration: configuration)
        // The always-local switch no longer has a settings row, so a choice saved by an earlier build must not pin local mode.
        defaults.removeObject(forKey: Self.alwaysLocalKey)
        state = OperatingState()
    }

    var alwaysLocal: Bool { state.alwaysLocal }

    /// Set only while the admin monitor runs and has decided on local mode.
    var broadcastUnavailableReason: String? {
        guard isMonitoring, case .local(let reason) = state.mode else { return nil }
        return reason.broadcastUnavailableReason
    }

    func start(accessToken: @escaping () -> String?) {
        self.accessToken = accessToken
        guard !isMonitoring else { return }
        isMonitoring = true
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor [weak self] in self?.send(.path(satisfied: satisfied)) }
        }
        monitor.start(queue: DispatchQueue(label: "com.innolive.operating-mode.path"))
        pathMonitor = monitor
        loop = Task { [weak self] in await self?.run() }
    }

    func stop() {
        pathMonitor?.cancel()
        pathMonitor = nil
        loop?.cancel()
        loop = nil
        isMonitoring = false
        state = OperatingState()
    }

    func send(_ event: OperatingEvent) {
        var next = state
        next.apply(event, at: .now)
        if next != state { state = next }
    }

    func setAlwaysLocal(_ enabled: Bool) {
        send(.alwaysLocal(enabled))
    }

    /// The user's "서버 모드로 전환" confirms the server with one fresh probe first.
    func switchToServer() async {
        guard state.canSwitchToServer else { return }
        send(.probe(succeeded: await probe()))
        send(.switchToServer)
    }

    private func run() async {
        var nextProbe = ContinuousClock.now
        var localStep = 0
        while !Task.isCancelled {
            send(.tick)
            if state.pathSatisfied, !state.alwaysLocal, ContinuousClock.now >= nextProbe {
                send(.probe(succeeded: await probe()))
                let interval: Duration
                switch state.mode {
                case .server:
                    localStep = 0
                    interval = Self.serverProbeInterval
                case .suspect:
                    localStep = 0
                    interval = Self.suspectProbeInterval
                case .local:
                    interval = Self.localProbeIntervals[min(localStep, Self.localProbeIntervals.count - 1)]
                    localStep += 1
                }
                nextProbe = ContinuousClock.now + interval
            }
            try? await Task.sleep(for: .seconds(1))
        }
    }

    /// Any HTTP answer below 500 means the server is reachable; authorization is not this probe's question.
    private func probe() async -> Bool {
        guard let url = AuthenticationConfiguration.serverURL(path: "/users/me/plan") else { return false }
        var request = URLRequest(url: url, timeoutInterval: Self.probeTimeout)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = accessToken(), !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        guard let (_, response) = try? await probeSession.data(for: request),
              let http = response as? HTTPURLResponse else { return false }
        return http.statusCode < 500
    }
}
