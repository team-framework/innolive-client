#if DEBUG
import Foundation

nonisolated struct PrivacyRegisteredFace: Codable, Identifiable, Sendable {
    let id: UUID
    let name: String
    let embedding: [Float]
}

nonisolated enum PrivacyFaceMath {
    static func normalized(_ values: [Float]) -> [Float]? {
        guard values.count == 512, values.allSatisfy(\.isFinite) else { return nil }
        let norm = sqrt(values.reduce(Float(0)) { $0 + $1 * $1 })
        guard norm.isFinite, norm > 0.00001 else { return nil }
        return values.map { $0 / norm }
    }

    static func cosine(_ a: [Float], _ b: [Float]) -> Float {
        guard let a = normalized(a), let b = normalized(b) else { return -1 }
        return zip(a, b).reduce(Float(0)) { $0 + $1.0 * $1.1 }
    }

    // Experimental local threshold. Server embeddings and thresholds are not reused.
    static func match(_ embedding: [Float], entries: [PrivacyRegisteredFace]) -> UUID? {
        guard normalized(embedding) != nil else { return nil }
        let scores = entries.map { ($0.id, cosine(embedding, $0.embedding)) }.sorted { $0.1 > $1.1 }
        guard let first = scores.first, first.1 >= 0.60,
              scores.count == 1 || first.1 - scores[1].1 >= 0.08 else { return nil }
        return first.0
    }
}

/// Called only on the camera queue. Stores embeddings with complete file protection, without photos.
nonisolated final class PrivacyFaceLibrary {
    static let contract = "privacy-face-vit-kprpe-vision-v1"
    private struct Document: Codable {
        let contract: String
        let entries: [PrivacyRegisteredFace]
    }
    let url: URL
    private(set) var entries: [PrivacyRegisteredFace] = []

    init(url: URL? = nil) throws {
        self.url = try url ?? FileManager.default.url(for: .applicationSupportDirectory,
                                                      in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("privacy-lab-faces.json")
        if FileManager.default.fileExists(atPath: self.url.path) {
            let document = try JSONDecoder().decode(Document.self, from: Data(contentsOf: self.url))
            guard document.contract == Self.contract, document.entries.count <= 20,
                  Set(document.entries.map(\.id)).count == document.entries.count,
                  document.entries.allSatisfy({ !$0.name.isEmpty && $0.name.count <= 40 && PrivacyFaceMath.normalized($0.embedding) != nil }) else {
                throw PrivacyFaceError.message("등록 데이터 형식이 맞지 않습니다.")
            }
            entries = document.entries
        }
    }

    func add(name: String, embedding: [Float]) throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 40, entries.count < 20,
              let normalized = PrivacyFaceMath.normalized(embedding) else {
            throw PrivacyFaceError.message("이름은 1~40자, 등록은 최대 20명입니다.")
        }
        try save(entries + [.init(id: UUID(), name: name, embedding: normalized)])
    }

    func delete(id: UUID) throws { try save(entries.filter { $0.id != id }) }

    private func save(_ updated: [PrivacyRegisteredFace]) throws {
        let data = try JSONEncoder().encode(Document(contract: Self.contract, entries: updated))
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        var target = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try target.setResourceValues(values)
        entries = updated
    }
}

nonisolated enum PrivacyFaceError: LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case let .message(text): return text } }
}
#endif
