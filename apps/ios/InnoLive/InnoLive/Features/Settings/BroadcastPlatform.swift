//
//  BroadcastPlatform.swift
//  InnoLive
//

import Foundation

enum BroadcastPlatform: String, CaseIterable, Identifiable {
    case youTube = "YouTube"
    case chzzk = "CHZZK"
    case soop = "SOOP"

    var id: String { rawValue }
    var assetName: String { "Platform\(rawValue)" }
}
