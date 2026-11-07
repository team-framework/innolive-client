#if DEBUG
import XCTest
@testable import InnoLive

final class PrivacyModelContractTests: XCTestCase {
    private let exportedDetector = [
        "innolive.contract": "privacy-segmentation-v1",
        "innolive.checkpoint_sha256": "307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115",
        "innolive.preprocessing": "RGB, letterbox 640, pad 114; image input scales by 1/255",
    ]
    private let exportedFaceRecognizer = [
        "innolive.contract": "privacy-face-vit-kprpe-v1",
        "innolive.checkpoint_sha256": "04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9",
        "innolive.preprocessing": "RGB112; x/127.5-1; bbox square 1.5x; landmarks top-left normalized; flip fusion included",
    ]
    private let exportedYuNet = [
        "innolive.contract": "privacy-yunet-2023mar-v1",
        "innolive.checkpoint_sha256": "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    ]

    func testEachModelAcceptsOnlyItsOwnExportedMetadata() {
        let cases: [(PrivacyModelContract, [String: String])] = [
            (.detector, exportedDetector), (.faceRecognizer, exportedFaceRecognizer), (.yuNet, exportedYuNet),
        ]
        for (contract, _) in cases {
            for (owner, metadata) in cases {
                XCTAssertEqual(contract.accepts(metadata), contract == owner,
                               "\(contract.name) judging metadata exported for \(owner.name)")
            }
        }
    }

    func testDetectorFromAnotherCheckpointIsRejected() {
        var metadata = exportedDetector
        metadata["innolive.checkpoint_sha256"] = String(repeating: "0", count: 64)
        XCTAssertFalse(PrivacyModelContract.detector.accepts(metadata))
    }

    func testPackageWithoutInnoLiveMetadataIsRejected() {
        for contract in [PrivacyModelContract.detector, .faceRecognizer, .yuNet] {
            XCTAssertFalse(contract.accepts(nil), contract.name)
            XCTAssertFalse(contract.accepts([:]), contract.name)
            XCTAssertFalse(contract.accepts(["innolive.checkpoint_sha256": contract.checkpointSHA256]),
                           "\(contract.name) without a contract name")
            XCTAssertFalse(contract.accepts(["innolive.contract": contract.name]),
                           "\(contract.name) without a checkpoint")
        }
    }
}
#endif
