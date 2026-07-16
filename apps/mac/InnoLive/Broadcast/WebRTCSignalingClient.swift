//
//  WebRTCSignalingClient.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import Foundation

final class WebRTCSignalingClient {
    var onMessage: ((BroadcastServerMessage) -> Void)?
    var onDisconnect: ((Error?) -> Void)?

    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var webSocketTask: URLSessionWebSocketTask?
    private var isManuallyDisconnected = false

    init(session: URLSession = .shared) {
        self.session = session
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
    }

    var isConnected: Bool {
        webSocketTask != nil
    }

    func connect(to url: URL) {
        disconnect()
        isManuallyDisconnected = false

        let task = session.webSocketTask(with: url)
        webSocketTask = task
        task.resume()
        receiveNextMessage(for: task)
    }

    func send<Message: Encodable>(_ message: Message, completion: @escaping (Error?) -> Void = { _ in }) {
        guard let webSocketTask else {
            DispatchQueue.main.async {
                completion(URLError(.notConnectedToInternet))
            }
            return
        }

        do {
            let data = try encoder.encode(message)
            guard let payload = String(data: data, encoding: .utf8) else {
                completion(URLError(.cannotDecodeContentData))
                return
            }

            webSocketTask.send(.string(payload)) { error in
                DispatchQueue.main.async {
                    completion(error)
                }
            }
        } catch {
            DispatchQueue.main.async {
                completion(error)
            }
        }
    }

    func sendStop(sessionID: String, completion: @escaping (Error?) -> Void = { _ in }) {
        let message = BroadcastControlMessage(
            type: "stopBroadcast",
            sessionID: sessionID,
            title: "",
            broadcasterID: "",
            mosaicPolicy: "",
            destinations: [],
            timestamp: Date()
        )
        send(message, completion: completion)
    }

    func disconnect() {
        isManuallyDisconnected = true
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
    }

    private func receiveNextMessage(for task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else {
                return
            }

            guard self.webSocketTask === task else {
                return
            }

            switch result {
            case .success(let message):
                self.handle(message)
                self.receiveNextMessage(for: task)
            case .failure(let error):
                let shouldNotify = !self.isManuallyDisconnected
                self.webSocketTask = nil
                if shouldNotify {
                    DispatchQueue.main.async {
                        self.onDisconnect?(error)
                    }
                }
            }
        }
    }

    private func handle(_ message: URLSessionWebSocketTask.Message) {
        switch message {
        case .string(let text):
            guard let data = text.data(using: .utf8),
                  let serverMessage = try? decoder.decode(BroadcastServerMessage.self, from: data) else {
                return
            }

            DispatchQueue.main.async {
                self.onMessage?(serverMessage)
            }
        case .data(let data):
            guard let serverMessage = try? decoder.decode(BroadcastServerMessage.self, from: data) else {
                return
            }

            DispatchQueue.main.async {
                self.onMessage?(serverMessage)
            }
        @unknown default:
            break
        }
    }
}
