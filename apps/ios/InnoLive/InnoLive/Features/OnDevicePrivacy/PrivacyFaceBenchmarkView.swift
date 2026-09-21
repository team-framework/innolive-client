#if DEBUG
import SwiftUI
import CoreML

struct PrivacyFaceBenchmarkView: View {
    @State private var status = "AdaFace 모델을 측정합니다. 카메라는 사용하지 않습니다."

    var body: some View {
        Text(status).padding().task {
            status = await Task.detached(priority: .userInitiated) {
                var rows: [[String: Double]] = []
                var references: [Int: [Float]] = [:]
                do {
                    let modes: [(Int, MLComputeUnits)] = ProcessInfo.processInfo.arguments.contains("--face-model-compare-gpu")
                        ? [(0, .all), (2, .cpuAndGPU)] : [(0, .all), (1, .cpuAndNeuralEngine)]
                    for (mode, units) in modes {
                        try autoreleasepool {
                            let baseline = PrivacyFaceEmbeddingModel.memoryMegabytes()
                            let model = try PrivacyFaceEmbeddingModel(computeUnits: units)
                            for run in 0..<12 {
                                let sample = run % 3
                                let start = ProcessInfo.processInfo.systemUptime
                                let embedding = try model.benchmarkPrediction(sample: sample)
                                let elapsed = (ProcessInfo.processInfo.systemUptime - start) * 1000
                                if references[sample] == nil { references[sample] = embedding }
                                let cosine = PrivacyFaceMath.cosine(embedding, references[sample]!)
                                rows.append(["mode": Double(mode), "run": Double(run), "sample": Double(sample), "warmup": run < 3 ? 1 : 0,
                                             "load_ms": model.loadMilliseconds,
                                             "recognizer_load_ms": model.recognizerLoadMilliseconds,
                                             "detector_load_ms": model.detectorLoadMilliseconds,
                                             "reference_cosine": Double(cosine),
                                             "inference_ms": elapsed,
                                             "baseline_mb": baseline, "memory_mb": PrivacyFaceEmbeddingModel.memoryMegabytes()])
                                PrivacyFaceWorker.saveMetrics(rows, filename: "privacy-face-benchmark.json")
                                guard cosine >= 0.999 else { throw PrivacyFaceError.message("실행 장치 변경의 수치 비교를 통과하지 못했습니다.") }
                            }
                        }
                    }
                    let selectedMode = modes.last!.0
                    let values = rows.filter { $0["mode"] == Double(selectedMode) && $0["warmup"] == 0 }.compactMap { $0["inference_ms"] }.sorted()
                    return String(format: "완료 · AdaFace %.1f ms\n수치: privacy-face-benchmark.json", values[values.count / 2])
                } catch { return "측정 실패: \(error.localizedDescription)" }
            }.value
        }
    }
}
#endif
