//
//  BroadcastModels.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import Foundation

enum BroadcastState: String, CaseIterable, Identifiable, Hashable {
    case idle
    case connecting
    case live
    case stopping
    case failed

    var id: Self { self }

    var title: String {
        switch self {
        case .idle:
            "대기"
        case .connecting:
            "연결 중"
        case .live:
            "방송 중"
        case .stopping:
            "중지 중"
        case .failed:
            "실패"
        }
    }

    var systemImage: String {
        switch self {
        case .idle:
            "power"
        case .connecting:
            "arrow.triangle.2.circlepath"
        case .live:
            "dot.radiowaves.left.and.right"
        case .stopping:
            "stop.circle"
        case .failed:
            "exclamationmark.triangle"
        }
    }

    var isBusy: Bool {
        self == .connecting || self == .stopping
    }

    var isStreaming: Bool {
        self == .live
    }
}

enum BroadcastMode: String, CaseIterable, Identifiable, Hashable, Codable {
    case mockLocal
    case serverBridge

    var id: Self { self }

    var title: String {
        switch self {
        case .mockLocal:
            "로컬 테스트"
        case .serverBridge:
            "서버 브리지"
        }
    }

    var detail: String {
        switch self {
        case .mockLocal:
            "실제 서버 없이 3초 지연 카메라로 방송 상태와 UI를 검증합니다."
        case .serverBridge:
            "서버 WebSocket 제어 채널에 연결해 AI 처리/송출 서버와 방송 세션을 제어합니다."
        }
    }
}

enum StreamPlatform: String, CaseIterable, Identifiable, Hashable, Codable {
    case youtube
    case twitch
    case customRTMP

    var id: Self { self }

    var title: String {
        switch self {
        case .youtube:
            "YouTube"
        case .twitch:
            "Twitch"
        case .customRTMP:
            "사용자 RTMP"
        }
    }

    var systemImage: String {
        switch self {
        case .youtube:
            "play.rectangle.fill"
        case .twitch:
            "bubble.left.and.bubble.right.fill"
        case .customRTMP:
            "network"
        }
    }
}

struct StreamDestination: Identifiable, Hashable, Codable {
    let id: UUID
    var platform: StreamPlatform
    var isEnabled: Bool
    var ingestURL: String
    var streamKey: String

    init(
        id: UUID = UUID(),
        platform: StreamPlatform,
        isEnabled: Bool,
        ingestURL: String = "",
        streamKey: String = ""
    ) {
        self.id = id
        self.platform = platform
        self.isEnabled = isEnabled
        self.ingestURL = ingestURL
        self.streamKey = streamKey
    }
}

struct BroadcastMetrics: Hashable {
    var bitrateKbps = 0
    var latencyMilliseconds = 0
    var framesPerSecond = 30
    var droppedFrames = 0
    var viewerCount = 0
}

enum WebRTCMediaUplinkState: String, CaseIterable, Identifiable, Hashable {
    case idle
    case preparing
    case offering
    case connecting
    case connected
    case failed

    var id: Self { self }

    var title: String {
        switch self {
        case .idle:
            "대기"
        case .preparing:
            "미디어 준비 중"
        case .offering:
            "Offer 전송 대기"
        case .connecting:
            "WebRTC 연결 중"
        case .connected:
            "미디어 송신 중"
        case .failed:
            "업링크 실패"
        }
    }
}

struct MediaUplinkSignal: Codable, Hashable {
    var type: String
    var sdp: String?
    var candidate: String?
    var sdpMid: String?
    var sdpMLineIndex: Int?
    var connectionState: String?
    var message: String?

    static func offer(_ sdp: String) -> MediaUplinkSignal {
        MediaUplinkSignal(type: "offer", sdp: sdp)
    }

    static func iceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) -> MediaUplinkSignal {
        MediaUplinkSignal(
            type: "iceCandidate",
            candidate: candidate,
            sdpMid: sdpMid,
            sdpMLineIndex: sdpMLineIndex
        )
    }

    static func status(_ state: String, message: String? = nil) -> MediaUplinkSignal {
        MediaUplinkSignal(type: "status", connectionState: state, message: message)
    }
}

struct ServerSignalingMessage: Codable, Hashable {
    var sessionID: String
    var type: String
    var sdp: String?
    var candidate: String?
    var sdpMid: String?
    var sdpMLineIndex: Int?

    enum CodingKeys: String, CodingKey {
        case sessionID = "session_id"
        case type
        case sdp
        case candidate
        case sdpMid
        case sdpMLineIndex
    }

    static func offer(sessionID: String, sdp: String) -> ServerSignalingMessage {
        ServerSignalingMessage(
            sessionID: sessionID,
            type: "offer",
            sdp: sdp
        )
    }

    static func iceCandidate(sessionID: String, signal: MediaUplinkSignal) -> ServerSignalingMessage {
        ServerSignalingMessage(
            sessionID: sessionID,
            type: "ice_candidate",
            candidate: signal.candidate,
            sdpMid: signal.sdpMid,
            sdpMLineIndex: signal.sdpMLineIndex
        )
    }
}

struct BroadcastControlMessage: Codable {
    let type: String
    let sessionID: String
    let title: String
    let broadcasterID: String
    let mosaicPolicy: String
    let destinations: [DestinationPayload]
    let timestamp: Date
    var mediaUplink: MediaUplinkSignal? = nil

    struct DestinationPayload: Codable {
        let platform: StreamPlatform
        let ingestURL: String
        let streamKey: String
    }
}

struct BroadcastServerMessage: Codable {
    let type: String
    let sessionID: String?
    let status: String?
    let processedVideoURL: String?
    let bitrateKbps: Int?
    let latencyMilliseconds: Int?
    let framesPerSecond: Int?
    let droppedFrames: Int?
    let viewerCount: Int?
    let message: String?
    let mediaUplink: MediaUplinkSignal?
    let sdp: String?
    let candidate: String?
    let sdpMid: String?
    let sdpMLineIndex: Int?
    let endOfCandidates: Bool?
    let connectionState: String?
    let iceConnectionState: String?
    let error: BroadcastServerError?

    enum CodingKeys: String, CodingKey {
        case type
        case sessionID = "session_id"
        case status
        case processedVideoURL
        case bitrateKbps
        case latencyMilliseconds
        case framesPerSecond
        case droppedFrames
        case viewerCount
        case message
        case mediaUplink
        case sdp
        case candidate
        case sdpMid
        case sdpMLineIndex
        case endOfCandidates = "end_of_candidates"
        case connectionState = "connection_state"
        case iceConnectionState = "ice_connection_state"
        case error
    }
}

struct BroadcastServerError: Codable, Hashable {
    let code: String
    let message: String
}
