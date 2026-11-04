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

    // 실험용 로컬 임계값이다. 서버 임베딩과 임계값은 재사용하지 않는다.
    static func match(_ embedding: [Float], entries: [PrivacyRegisteredFace]) -> UUID? {
        guard normalized(embedding) != nil else { return nil }
        let scores = entries.map { ($0.id, cosine(embedding, $0.embedding)) }.sorted { $0.1 > $1.1 }
        guard let first = scores.first, first.1 >= 0.60,
              scores.count == 1 || first.1 - scores[1].1 >= 0.08 else { return nil }
        return first.0
    }
}

/// 암호화한 얼굴 라이브러리의 키다. 기기 밖으로 나가지 않고 백업에서 복원되지도 않는다.
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

/// 카메라 큐에서만 호출한다. 사진은 저장하지 않고 임베딩만 저장한다. 임베딩은 complete 파일 보호에 더해
/// AES-GCM sealed box로 한 번 더 암호화하고, 키는 이 기기 전용 Keychain 항목에 둔다.
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
    /// 이번 로드에서 등록 데이터를 버려야 했을 때 설정한다. 사용자가 다시 등록해야 한다.
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
                // 백업에서 복원했거나 새 기기라면 키가 없다. 생체 데이터는 설계상 복구할 수 없다.
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

    /// 로그아웃, 세션 만료, 계정 삭제 때 라이브러리와 그 키를 함께 지운다.
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
            // 다른 인식기가 만든 임베딩과는 비교할 수 없다.
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
