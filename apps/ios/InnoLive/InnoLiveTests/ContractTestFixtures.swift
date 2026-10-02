import Foundation

// contracts/fixtures 원본을 테스트 번들에 포함한다. 실기기에서도 호스트 저장소 경로를 참조하지 않는다.
enum ContractTestFixtures {
    static func data(named name: String) throws -> Data {
        let bundle = Bundle(for: ContractFixtureBundleAnchor.self)
        guard let url = bundle.url(forResource: name, withExtension: "json", subdirectory: "fixtures") else {
            throw CocoaError(.fileNoSuchFile, userInfo: [NSFilePathErrorKey: "fixtures/\(name).json"])
        }
        return try Data(contentsOf: url)
    }
}

private final class ContractFixtureBundleAnchor: NSObject {}
