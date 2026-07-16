//
//  StudioModels.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import Foundation

enum RecordingResolution: String, CaseIterable, Identifiable, Hashable {
    case hd720
    case fullHD1080
    case qhd1440

    var id: Self { self }

    var title: String {
        switch self {
        case .hd720:
            "1280 x 720"
        case .fullHD1080:
            "1920 x 1080"
        case .qhd1440:
            "2560 x 1440"
        }
    }

    var dimensions: (width: Int, height: Int) {
        switch self {
        case .hd720:
            (1280, 720)
        case .fullHD1080:
            (1920, 1080)
        case .qhd1440:
            (2560, 1440)
        }
    }
}

struct RecordingSettings: Hashable {
    var resolution: RecordingResolution
    var videoBitrateKbps: Int
    var frameRate: Int

    init(
        resolution: RecordingResolution = .fullHD1080,
        videoBitrateKbps: Int = 6_000,
        frameRate: Int = 30
    ) {
        self.resolution = resolution
        self.videoBitrateKbps = videoBitrateKbps
        self.frameRate = frameRate
    }
}

struct AudioMeterSample: Hashable {
    var level: Double
    var peakLevel: Double

    static let zero = AudioMeterSample(level: 0, peakLevel: 0)
}

enum ScreenCaptureResolution: String, CaseIterable, Identifiable, Hashable {
    case source
    case max960
    case max1280
    case max1920
    case max2560

    var id: Self { self }

    var title: String {
        switch self {
        case .source:
            "원본"
        case .max960:
            "긴 변 960px"
        case .max1280:
            "긴 변 1280px"
        case .max1920:
            "긴 변 1920px"
        case .max2560:
            "긴 변 2560px"
        }
    }

    var maximumLongEdge: Int? {
        switch self {
        case .source:
            nil
        case .max960:
            960
        case .max1280:
            1280
        case .max1920:
            1920
        case .max2560:
            2560
        }
    }
}

struct ScreenCaptureSettings: Hashable {
    var displayID: UInt32?
    var resolution: ScreenCaptureResolution

    init(
        displayID: UInt32? = nil,
        resolution: ScreenCaptureResolution = .max1920
    ) {
        self.displayID = displayID
        self.resolution = resolution
    }
}

enum StudioTool: String, CaseIterable, Identifiable, Hashable {
    case broadcast
    case scenes
    case sources
    case audio
    case response
    case settings

    var id: Self { self }

    var title: String {
        switch self {
        case .broadcast:
            "방송"
        case .scenes:
            "장면"
        case .sources:
            "소스"
        case .audio:
            "오디오"
        case .response:
            "응답 영상"
        case .settings:
            "설정"
        }
    }

    var systemImage: String {
        switch self {
        case .broadcast:
            "dot.radiowaves.left.and.right"
        case .scenes:
            "rectangle.3.group"
        case .sources:
            "rectangle.on.rectangle"
        case .audio:
            "slider.horizontal.3"
        case .response:
            "server.rack"
        case .settings:
            "gearshape"
        }
    }
}

struct StudioScene: Identifiable, Hashable {
    let id: UUID
    var name: String

    init(id: UUID = UUID(), name: String) {
        self.id = id
        self.name = name
    }
}

enum SourceKind: String, CaseIterable, Identifiable, Hashable {
    case camera
    case delayedResponse
    case music
    case media
    case text
    case color
    case image
    case screen

    var id: Self { self }

    var title: String {
        switch self {
        case .camera:
            "카메라"
        case .delayedResponse:
            "응답 영상"
        case .music:
            "뮤직 플레이리스트"
        case .media:
            "미디어 소스"
        case .text:
            "텍스트"
        case .color:
            "색상"
        case .image:
            "이미지"
        case .screen:
            "화면 캡처"
        }
    }

    var systemImage: String {
        switch self {
        case .camera:
            "video"
        case .delayedResponse:
            "server.rack"
        case .music:
            "music.note"
        case .media:
            "play.rectangle"
        case .text:
            "textformat"
        case .color:
            "paintpalette"
        case .image:
            "photo"
        case .screen:
            "display"
        }
    }
}

struct SourceLayout: Hashable {
    var x: Double
    var y: Double
    var width: Double
    var height: Double
    var zIndex: Int
    var opacity: Double

    init(
        x: Double = 0,
        y: Double = 0,
        width: Double = 1,
        height: Double = 1,
        zIndex: Int = 0,
        opacity: Double = 1
    ) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.zIndex = zIndex
        self.opacity = opacity
    }
}

struct StudioSource: Identifiable, Hashable {
    let id: UUID
    var name: String
    var kind: SourceKind
    var isVisible: Bool
    var isLocked: Bool
    var layout: SourceLayout
    var text: String
    var colorHex: String
    var assetURL: URL?
    var screenCapture: ScreenCaptureSettings

    init(
        id: UUID = UUID(),
        name: String,
        kind: SourceKind,
        isVisible: Bool = true,
        isLocked: Bool = false,
        layout: SourceLayout = SourceLayout(),
        text: String = "",
        colorHex: String = "#3478F6",
        assetURL: URL? = nil,
        screenCapture: ScreenCaptureSettings = ScreenCaptureSettings()
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.isVisible = isVisible
        self.isLocked = isLocked
        self.layout = layout
        self.text = text
        self.colorHex = colorHex
        self.assetURL = assetURL
        self.screenCapture = screenCapture
    }
}

struct SceneRecordingConfiguration: Hashable {
    var settings: RecordingSettings
    var sources: [StudioSource]
    var includeMicrophoneAudio: Bool

    init(
        settings: RecordingSettings,
        sources: [StudioSource],
        includeMicrophoneAudio: Bool
    ) {
        self.settings = settings
        self.sources = sources
        self.includeMicrophoneAudio = includeMicrophoneAudio
    }
}

struct AudioChannel: Identifiable, Hashable {
    let id: UUID
    var kind: AudioChannelKind
    var name: String
    var isEnabled: Bool
    var volume: Double
    var level: Double
    var peakLevel: Double

    init(
        id: UUID = UUID(),
        kind: AudioChannelKind,
        name: String,
        isEnabled: Bool,
        volume: Double,
        level: Double = 0,
        peakLevel: Double = 0
    ) {
        self.id = id
        self.kind = kind
        self.name = name
        self.isEnabled = isEnabled
        self.volume = volume
        self.level = level
        self.peakLevel = peakLevel
    }
}

enum AudioChannelKind: String, CaseIterable, Identifiable, Hashable {
    case microphone

    var id: Self { self }
}

enum StudioSheet: Identifiable {
    case destinations
    case serverDiagnostics
    case cameraProperties
    case filters
    case responseSettings
    case appSettings

    var id: String {
        switch self {
        case .destinations:
            "destinations"
        case .serverDiagnostics:
            "serverDiagnostics"
        case .cameraProperties:
            "cameraProperties"
        case .filters:
            "filters"
        case .responseSettings:
            "responseSettings"
        case .appSettings:
            "appSettings"
        }
    }

    var title: String {
        switch self {
        case .destinations:
            "송출 목적지"
        case .serverDiagnostics:
            "서버 진단"
        case .cameraProperties:
            "입력 속성"
        case .filters:
            "영상 필터"
        case .responseSettings:
            "응답 영상 설정"
        case .appSettings:
            "앱 설정"
        }
    }
}

enum ExpandedPreviewKind: String {
    case beforeFilter
    case afterFilter

    var windowID: String {
        "expandedPreview-\(rawValue)"
    }
}
