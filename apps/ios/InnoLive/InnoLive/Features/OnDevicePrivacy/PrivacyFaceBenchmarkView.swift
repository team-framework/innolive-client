#if DEBUG
import SwiftUI
import CoreML

struct PrivacyFaceBenchmarkView: View {
    @State private var status = "AdaFace 모델을 측정합니다. 카메라는 사용하지 않습니다."

    var body: some View {
        Text(status).padding().task {
            status = await Task.detached(priority: .userInitiated) {
                var rows: [[String: Double]] = []
                do {
                    for (mode, units) in [(0, MLComputeUnits.all), (1, MLComputeUnits.cpuAndNeuralEngine)] {
                        try autoreleasepool {
                            let baseline = PrivacyFaceEmbeddingModel.memoryMegabytes()
                            let model = try PrivacyFaceEmbeddingModel(computeUnits: units)
                            for run in 0..<10 {
                                let start = ProcessInfo.processInfo.systemUptime
                                try model.benchmarkPrediction()
                                rows.append(["mode": Double(mode), "run": Double(run), "warmup": run < 2 ? 1 : 0,
                                             "load_ms": model.loadMilliseconds,
                                             "inference_ms": (ProcessInfo.processInfo.systemUptime - start) * 1000,
                                             "baseline_mb": baseline, "memory_mb": PrivacyFaceEmbeddingModel.memoryMegabytes()])
                                PrivacyFaceWorker.saveMetrics(rows, filename: "privacy-face-benchmark.json")
                            }
                        }
                    }
                    let values = rows.filter { $0["mode"] == 0 && $0["warmup"] == 0 }.compactMap { $0["inference_ms"] }.sorted()
                    return String(format: "완료 · AdaFace %.1f ms\n수치: privacy-face-benchmark.json", values[values.count / 2])
                } catch { return "측정 실패: \(error.localizedDescription)" }
            }.value
        }
    }
}
#endif
