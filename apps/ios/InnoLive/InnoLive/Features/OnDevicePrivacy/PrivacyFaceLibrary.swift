import CryptoKit
import Foundation
import Security

nonisolated struct PrivacyRegisteredFace: Codable, Identifiable, Sendable {
    let id: UUID
    let name: String
    let embedding: [Float]
    var registeredAt: String? = nil
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

/// Key for the sealed face library. It never leaves the device and is not restored from backups.
nonisolated protocol PrivacyFaceLibraryKeyStoring: Sendable {
    func load() -> SymmetricKey?
    func create() throws -> SymmetricKey
    func remove()
}

nonisolated struct PrivacyFaceLibraryKeychainKey: PrivacyFaceLibraryKeyStoring {
    private var identity: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.framework.innolive.face-library-key",
         kSecAttrAccount as String: "aes-gcm-256"]
    }

    func load() -> SymmetricKey? {
        var query = identity
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data, data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    func create() throws -> SymmetricKey {
        let key = SymmetricKey(size: .bits256)
        var add = identity
        add[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem, let existing = load() { return existing }
        guard status == errSecSuccess else {
            throw PrivacyFaceError.message(String(localized: "얼굴 등록 데이터를 안전하게 저장하지 못했습니다."))
        }
        return key
    }

    func remove() { SecItemDelete(identity as CFDictionary) }
}

/// Called only on the camera queue. Stores embeddings, never photos, as an AES-GCM sealed box
/// on top of complete file protection, keyed by a device-only Keychain item.
nonisolated final class PrivacyFaceLibrary {
    private static let revisionState = RevisionState()
    private final class RevisionState: @unchecked Sendable {
        let lock = NSLock()
        var value = 0
    }
    static var revision: Int { revisionState.lock.withLock { revisionState.value } }
    static func changed() { revisionState.lock.withLock { revisionState.value += 1 } }
    static let contract = "privacy-face-vit-kprpe-yunet-v1"
    static let contractChangedMessage = String(localized: "얼굴 인식 모델이 바뀌어 다시 등록해야 합니다.")
    static let unreadableMessage = String(localized: "이 기기에서 등록 데이터를 읽을 수 없어 초기화했습니다. 다시 등록해 주세요.")
    private struct Document: Codable {
        let contract: String
        let entries: [PrivacyRegisteredFace]
    }
    let url: URL
    private let legacyURL: URL
    private let keys: PrivacyFaceLibraryKeyStoring
    private(set) var entries: [PrivacyRegisteredFace] = []
    /// Set when this load had to discard registrations; the user needs to register again.
    private(set) var notice: String?

    private static func defaultURLs() throws -> (sealed: URL, legacy: URL) {
        let support = try FileManager.default.url(for: .applicationSupportDirectory,
                                                  in: .userDomainMask, appropriateFor: nil, create: true)
        return (support.appendingPathComponent("privacy-faces.sealed"),
                support.appendingPathComponent("privacy-lab-faces-yunet.json"))
    }

    init(url: URL? = nil, legacyURL: URL? = nil,
         keys: PrivacyFaceLibraryKeyStoring = PrivacyFaceLibraryKeychainKey()) throws {
        let defaults = try Self.defaultURLs()
        self.url = url ?? defaults.sealed
        self.legacyURL = legacyURL ?? defaults.legacy
        self.keys = keys
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: self.url.path) {
            let plain: Data
            do {
                guard let key = keys.load() else { throw CryptoKitError.authenticationFailure }
                plain = try AES.GCM.open(AES.GCM.SealedBox(combined: Data(contentsOf: self.url)), using: key)
            } catch {
                // A restored backup or a new device has no key. Biometric data is not recoverable by design.
                try? fileManager.removeItem(at: self.url)
                notice = Self.unreadableMessage
                return
            }
            try adopt(plain)
            if notice != nil { try? fileManager.removeItem(at: self.url) }
        } else if fileManager.fileExists(atPath: self.legacyURL.path) {
            try adopt(Data(contentsOf: self.legacyURL))
            try write(entries)
            try fileManager.removeItem(at: self.legacyURL)
        }
    }

    /// Removes the library and its key, for sign-out, session expiry and account deletion.
    static func eraseAll(keys: PrivacyFaceLibraryKeyStoring = PrivacyFaceLibraryKeychainKey()) {
        if let urls = try? defaultURLs() {
            try? FileManager.default.removeItem(at: urls.sealed)
            try? FileManager.default.removeItem(at: urls.legacy)
        }
        keys.remove()
        changed()
    }

    private func adopt(_ data: Data) throws {
        let document = try JSONDecoder().decode(Document.self, from: data)
        guard document.contract == Self.contract else {
            // Embeddings from a different recognizer are not comparable.
            entries = []
            notice = Self.contractChangedMessage
            return
        }
        guard document.entries.count <= 20,
              Set(document.entries.map(\.id)).count == document.entries.count,
              document.entries.allSatisfy({ !$0.name.isEmpty && $0.name.count <= 40 && PrivacyFaceMath.normalized($0.embedding) != nil }) else {
            throw PrivacyFaceError.message(String(localized: "등록 데이터 형식이 맞지 않습니다."))
        }
        entries = document.entries
    }

    func add(name: String, embedding: [Float]) throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 40, entries.count < 20,
              let normalized = PrivacyFaceMath.normalized(embedding) else {
            throw PrivacyFaceError.message(String(localized: "이름은 1~40자, 등록은 최대 20명입니다."))
        }
        try save(entries + [.init(id: UUID(), name: name, embedding: normalized, registeredAt: Date().ISO8601Format())])
    }

    func deleteAll() throws { try save([]) }

    func delete(id: UUID) throws { try save(entries.filter { $0.id != id }) }

    private func save(_ updated: [PrivacyRegisteredFace]) throws {
        try write(updated)
        entries = updated
        Self.changed()
    }

    private func write(_ updated: [PrivacyRegisteredFace]) throws {
        let plain = try JSONEncoder().encode(Document(contract: Self.contract, entries: updated))
        let key = try keys.load() ?? keys.create()
        guard let sealed = try AES.GCM.seal(plain, using: key).combined else {
            throw PrivacyFaceError.message(String(localized: "얼굴 등록 데이터를 안전하게 저장하지 못했습니다."))
        }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try sealed.write(to: url, options: [.atomic, .completeFileProtection])
        var target = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try target.setResourceValues(values)
    }
}

nonisolated enum PrivacyFaceError: LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case let .message(text): return text } }
}
