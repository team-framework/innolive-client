import Foundation
import ImageIO

/// 로컬 얼굴 등록 작업을 순서대로 처리한다. 네트워크 요청을 보내지 않고 JPEG도 저장하지 않는다.
actor LocalFaceRegistrationStore {
    static let shared = LocalFaceRegistrationStore()
    private var recognizer: PrivacyFaceEmbeddingModel?

    func status() throws -> ReferenceFaceStatus {
        let entries = try PrivacyFaceLibrary().entries
        return ReferenceFaceStatus(registered: !entries.isEmpty, source: "on_device", registeredAt: nil,
                                   clientID: "local-device", count: entries.count, faces: entries.map {
            ReferenceFace(id: $0.id.uuidString, name: $0.name, registeredAt: $0.registeredAt ?? "")
        })
    }

    func register(jpegData: Data, name: String) throws -> ReferenceFaceStatus {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ReferenceFaceName.isValid(name) else { throw PrivacyFaceError.message(String(localized: "이름을 1~40자로 입력해 주세요.")) }
        try Task.checkCancellation()
        guard let source = CGImageSourceCreateWithData(jpegData as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw PrivacyModelError.imageBuffer }
        if recognizer == nil { recognizer = try PrivacyFaceEmbeddingModel() }
        let embedding = try recognizer!.embedding(image: image, enrollment: true)
        try Task.checkCancellation()
        let library = try PrivacyFaceLibrary()
        if library.entries.contains(where: { PrivacyFaceMath.cosine(embedding, $0.embedding) >= 0.75 }) {
            throw PrivacyFaceError.message(String(localized: "이미 등록된 얼굴입니다."))
        }
        try library.add(name: name, embedding: embedding)
        return try status()
    }

    func delete(faceID: String) throws {
        guard let id = UUID(uuidString: faceID) else { throw PrivacyFaceError.message(String(localized: "등록된 얼굴을 찾지 못했습니다.")) }
        try PrivacyFaceLibrary().delete(id: id)
    }
    func deleteAll() throws { try PrivacyFaceLibrary().deleteAll() }
}
