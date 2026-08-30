import Foundation

@MainActor
final class ReferenceFaceAPI {
    private let authentication: AuthSession

    init(authentication: AuthSession) {
        self.authentication = authentication
    }

    func status() async throws -> ReferenceFaceStatus {
        let data = try await request(path: "/reference-face", method: "GET")
        return try decodeStatus(from: data)
    }

    func register(jpegData: Data) async throws -> ReferenceFaceStatus {
        let boundary = "InnoLive-Reference-Face-\(UUID().uuidString)"
        var body = Data()
        body.appendUTF8("--\(boundary)\r\n")
        body.appendUTF8("Content-Disposition: form-data; name=\"image\"; filename=\"reference-face.jpg\"\r\n")
        body.appendUTF8("Content-Type: image/jpeg\r\n\r\n")
        body.append(jpegData)
        body.appendUTF8("\r\n--\(boundary)--\r\n")

        let data = try await request(
            path: "/reference-face",
            method: "POST",
            contentType: "multipart/form-data; boundary=\(boundary)",
            body: body
        )
        let status = try decodeStatus(from: data)
        guard status.registered else { throw ReferenceFaceAPIError.response }
        return status
    }

    func delete(faceID: String) async throws {
        let encodedID = faceID.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? faceID
        _ = try await request(path: "/reference-face/\(encodedID)", method: "DELETE")
    }

    func deleteAll() async throws {
        _ = try await request(path: "/reference-face", method: "DELETE")
    }

    private func request(
        path: String,
        method: String,
        contentType: String? = nil,
        body: Data? = nil
    ) async throws -> Data {
        guard let url = AuthenticationConfiguration.serverURL(path: path) else {
            throw ReferenceFaceAPIError.configuration
        }
        guard let accessToken = authentication.currentAccessToken(), !accessToken.isEmpty else {
            throw ReferenceFaceAPIError.unauthorized
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        if let contentType {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        request.httpBody = body

        var result = try await perform(request)
        if result.response.statusCode == 401 {
            switch await authentication.refreshSession() {
            case .refreshed:
                guard let refreshedToken = authentication.currentAccessToken() else {
                    throw ReferenceFaceAPIError.unauthorized
                }
                request.setValue("Bearer \(refreshedToken)", forHTTPHeaderField: "Authorization")
                result = try await perform(request)
            case .invalid:
                authentication.expireSession()
                throw ReferenceFaceAPIError.unauthorized
            case .unavailable:
                break
            }
        }

        guard (200..<300).contains(result.response.statusCode) else {
            let envelope = try? JSONDecoder().decode(ReferenceFaceErrorEnvelope.self, from: result.data)
            throw ReferenceFaceAPIError.api(
                code: envelope?.error.code,
                fallback: envelope?.error.message ?? "얼굴 관리 요청을 처리하지 못했습니다."
            )
        }
        return result.data
    }

    private func perform(_ request: URLRequest) async throws -> (data: Data, response: HTTPURLResponse) {
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let response = response as? HTTPURLResponse else {
                throw ReferenceFaceAPIError.response
            }
            return (data, response)
        } catch let error as ReferenceFaceAPIError {
            throw error
        } catch {
            throw ReferenceFaceAPIError.transport
        }
    }

    private func decodeStatus(from data: Data) throws -> ReferenceFaceStatus {
        do {
            return try JSONDecoder().decode(ReferenceFaceStatus.self, from: data)
        } catch {
            throw ReferenceFaceAPIError.response
        }
    }
}

private struct ReferenceFaceErrorEnvelope: Decodable {
    let error: ReferenceFaceErrorBody
}

private struct ReferenceFaceErrorBody: Decodable {
    let code: String?
    let message: String?
}

private extension Data {
    mutating func appendUTF8(_ value: String) {
        append(value.data(using: .utf8) ?? Data())
    }
}
