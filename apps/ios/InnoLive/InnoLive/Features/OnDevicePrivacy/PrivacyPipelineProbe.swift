import Foundation

/// Opt-in device profiling. Records timings and counts, never frames or credentials.
nonisolated enum PrivacyPipelineProbe {
    #if DEBUG
    static let enabled = ProcessInfo.processInfo.arguments.contains("--privacy-pipeline-probe")
    private static let recorder = Recorder()

    private final class Recorder: @unchecked Sendable {
        let lock = NSLock()
        var samples: [String: [Double]] = [:]
        var counts: [String: Int] = [:]
        let queue = DispatchQueue(label: "com.innolive.privacy.probe", qos: .utility)
        var timer: DispatchSourceTimer?

        init() {
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now() + 15, repeating: 15)
            timer.setEventHandler { [weak self] in self?.flush() }
            self.timer = timer
            timer.resume()
        }

        func flush() {
            let (values, events) = lock.withLock {
                let result = (samples, counts)
                samples = [:]; counts = [:]
                return result
            }
            guard !events.isEmpty || !values.isEmpty else { return }
            var stages: [String: [String: Double]] = [:]
            for (key, values) in values where !values.isEmpty {
                let sorted = values.sorted()
                stages[key] = ["count": Double(sorted.count), "p50": sorted[sorted.count / 2],
                               "p95": sorted[min(sorted.count - 1, Int(Double(sorted.count - 1) * 0.95))],
                               "max": sorted.last!]
            }
            let row: [String: Any] = ["utc": Date().ISO8601Format(), "thermal": ProcessInfo.processInfo.thermalState.rawValue,
                                      "counts": events, "stages_ms": stages]
            guard var data = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]),
                  let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
            data.append(10)
            let url = directory.appendingPathComponent("privacy-pipeline-probe.jsonl")
            if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
            guard let file = try? FileHandle(forWritingTo: url) else { return }
            defer { try? file.close() }
            try? file.seekToEnd(); try? file.write(contentsOf: data)
        }
    }
    #endif

    @inline(__always) static func count(_ name: String) {
        #if DEBUG
        guard enabled else { return }
        recorder.lock.withLock { recorder.counts[name, default: 0] += 1 }
        #endif
    }

    @inline(__always) static func record(_ name: String, milliseconds: Double) {
        #if DEBUG
        guard enabled else { return }
        recorder.lock.withLock {
            if recorder.samples[name, default: []].count < 1_024 { recorder.samples[name, default: []].append(milliseconds) }
        }
        #endif
    }

    @inline(__always) static func measure<T>(_ name: String, _ operation: () throws -> T) rethrows -> T {
        #if DEBUG
        guard enabled else { return try operation() }
        let start = ProcessInfo.processInfo.systemUptime
        defer { record(name, milliseconds: (ProcessInfo.processInfo.systemUptime - start) * 1_000) }
        #endif
        return try operation()
    }
}
