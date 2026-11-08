#if DEBUG
import CoreML
import XCTest
@testable import InnoLive

/// Fails, never skips, when a model is missing: this suite is the load proof for model-bundled builds.
final class BundledPrivacyModelLoadTests: XCTestCase {
    private func loadBundled(_ name: String, _ contract: PrivacyModelContract) throws -> MLModelDescription {
        let url = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: "mlmodelc"),
                                "\(name).mlmodelc is not in the app bundle")
        let config = MLModelConfiguration()
        config.computeUnits = .cpuOnly
        let description = try MLModel(contentsOf: url, configuration: config).modelDescription
        XCTAssertTrue(contract.accepts(description), "\(name) metadata matches \(contract.name)")
        return description
    }

    private func shapes(_ outputs: [String: MLFeatureDescription]) -> Set<[Int]> {
        Set(outputs.values.compactMap { $0.multiArrayConstraint?.shape.map(\.intValue) })
    }

    func testDetectorLoadsWithContractInputsAndOutputs() throws {
        let description = try loadBundled("PrivacyDetector", .detector)
        let images = description.inputDescriptionsByName.values.compactMap(\.imageConstraint)
        XCTAssertEqual(images.map { [$0.pixelsWide, $0.pixelsHigh] }, [[640, 640]])
        XCTAssertTrue(shapes(description.outputDescriptionsByName).isSuperset(of: [[1, 38, 8400], [1, 32, 160, 160]]))
    }

    func testFaceRecognizerLoadsWithContractInputsAndOutputs() throws {
        let description = try loadBundled("PrivacyFaceRecognizer", .faceRecognizer)
        XCTAssertEqual(description.inputDescriptionsByName["image"]?.imageConstraint?.pixelsWide, 112)
        XCTAssertEqual(description.inputDescriptionsByName["landmarks"]?.multiArrayConstraint?.shape.map(\.intValue), [1, 5, 2])
        XCTAssertEqual(description.outputDescriptionsByName["embedding"]?.multiArrayConstraint?.shape.map(\.intValue), [1, 512])
    }

    func testYuNetLoadsWithContractInputsAndOutputs() throws {
        let description = try loadBundled("PrivacyYuNet", .yuNet)
        XCTAssertEqual(description.inputDescriptionsByName["image"]?.type, .image)
        let expected = Set(["cls", "obj", "bbox", "kps"].flatMap { name in [8, 16, 32].map { "\(name)_\($0)" } })
        let outputs = Set(description.outputDescriptionsByName.filter { $0.value.type == .multiArray }.keys)
        XCTAssertTrue(expected.isSubset(of: outputs), "missing YuNet outputs: \(expected.subtracting(outputs).sorted())")
    }
}
#endif
