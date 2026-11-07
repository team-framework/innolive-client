import CoreML

/// 변환 스크립트가 Core ML metadata에 기록하는 모델 식별 값이다.
nonisolated struct PrivacyModelContract: Equatable, Sendable {
    let name: String
    let checkpointSHA256: String

    /// `scripts/export-ios-privacy-model.py`
    static let detector = PrivacyModelContract(
        name: "privacy-segmentation-v1",
        checkpointSHA256: "307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115")
    /// `scripts/export-ios-face-model.py`
    static let faceRecognizer = PrivacyModelContract(
        name: "privacy-face-vit-kprpe-v1",
        checkpointSHA256: "04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9")
    /// `scripts/export-ios-yunet-model.py`
    static let yuNet = PrivacyModelContract(
        name: "privacy-yunet-2023mar-v1",
        checkpointSHA256: "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4")

    func accepts(_ metadata: [String: String]?) -> Bool {
        metadata?["innolive.contract"] == name && metadata?["innolive.checkpoint_sha256"] == checkpointSHA256
    }

    func accepts(_ description: MLModelDescription) -> Bool {
        accepts(description.metadata[.creatorDefinedKey] as? [String: String])
    }
}
