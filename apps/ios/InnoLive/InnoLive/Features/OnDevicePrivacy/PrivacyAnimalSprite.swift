import CoreImage
import Foundation

nonisolated enum PrivacyAnimal: String, CaseIterable, Identifiable {
    case blur, rabbit, owl
    static let storageKey = "com.framework.innolive.privacy-animal"
    var id: String { rawValue }
    var title: String { switch self { case .blur: "블러"; case .rabbit: "토끼"; case .owl: "부엉이" } }
    static var selected: Self {
        guard AIProcessingMode.selected == .onDevice else { return .blur }
        return Self(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .blur
    }
}

/// Queue-owned compositor. Missing assets always retain the already protected image.
nonisolated final class PrivacyAnimalSprite {
    private struct Metadata: Decodable { let faceRect: [Double]; let anchor: [Double] }
    private var unavailable: Set<PrivacyAnimal> = []
    private var cached: [PrivacyAnimal: (CIImage, CGRect)] = [:]
    func composite(_ protected: CIImage, boxes: [CGRect], animal: PrivacyAnimal = .selected) -> CIImage {
        guard animal != .blur, !unavailable.contains(animal), !boxes.isEmpty else { return protected }
        if cached[animal] == nil {
            unavailable.insert(animal)
            guard let png = Bundle.main.url(forResource: animal.rawValue, withExtension: "png"),
                  let json = Bundle.main.url(forResource: animal.rawValue, withExtension: "json"),
                  let data = try? Data(contentsOf: json),
                  let metadata = try? JSONDecoder().decode(Metadata.self, from: data),
                  metadata.faceRect.count == 4, metadata.anchor.count == 2,
                  metadata.faceRect.allSatisfy({ $0.isFinite && $0 >= 0 && $0 <= 1 }),
                  let image = CIImage(contentsOf: png) else { return protected }
            let f = metadata.faceRect
            guard f[2] > 0, f[3] > 0, f[0] + f[2] <= 1, f[1] + f[3] <= 1 else { return protected }
            unavailable.remove(animal)
            cached[animal] = (image, CGRect(x: f[0] * image.extent.width,
                y: (1 - f[1] - f[3]) * image.extent.height,
                width: f[2] * image.extent.width, height: f[3] * image.extent.height))
        }
        guard let (sprite, face) = cached[animal] else { return protected }
        return boxes.reduce(protected) { output, box in
            guard !box.isEmpty, !box.isNull else { return output }
            let sx = box.width / face.width, sy = box.height / face.height
            let placed = sprite.transformed(by: CGAffineTransform(scaleX: sx, y: sy))
                .transformed(by: CGAffineTransform(translationX: box.minX - face.minX * sx,
                                                   y: box.minY - face.minY * sy))
            return placed.composited(over: output).cropped(to: protected.extent)
        }
    }
}
