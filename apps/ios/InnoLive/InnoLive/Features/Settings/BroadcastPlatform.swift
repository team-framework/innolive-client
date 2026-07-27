//
//  BroadcastPlatform.swift
//  InnoLive
//

import Foundation

enum BroadcastPlatform: String, CaseIterable, Identifiable {
    case youTube = "YouTube"
    case chzzk = "CHZZK"
    case soop = "SOOP"

    var id: String { rawValue } // 문자의 원본값 (YouTube, CHZZK, SOOP)을 id로 지정함
    var assetName: String { "Platform\(rawValue)" }
}
