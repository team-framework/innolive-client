import Foundation

/// 서버 영상 m-line의 코덱 순서. 서버는 offer 순서대로 처음 받아들인 코덱 하나로 업링크와 반환 영상을 함께 묶는다
/// (innolive-server `internal/session/video_codec.go`).
nonisolated enum VideoUplinkCodecPreference {
    /// FHD 30fps를 담는 가장 낮은 H.264 level 4.0의 level_idc. level 3.1은 1080p를 약 13fps로 묶어
    /// VideoToolbox가 1080p30을 거부할 수 있다.
    static let minimumH264LevelIDC = 40

    /// 서버가 RTP까지 받는 H.264는 pion 기본 코덱과 profile 두 바이트가 같은 Constrained Baseline(42e0)뿐이다.
    /// Constrained High(640c)는 answer까지 가지만 서버가 RTP를 버려 OnTrack이 열리지 않는다.
    /// level은 기기마다 VideoToolbox 조회값이라 Android처럼 42e01f로 고정하지 않는다.
    static func isServerH264Baseline(name: String, parameters: [String: String]) -> Bool {
        guard name.caseInsensitiveCompare("H264") == .orderedSame,
              parameters["packetization-mode"] == "1",
              let profileLevelID = parameters["profile-level-id"]?.lowercased(),
              profileLevelID.count == 6,
              profileLevelID.hasPrefix("42e0"),
              let levelIDC = Int(profileLevelID.suffix(2), radix: 16) else { return false }
        return levelIDC >= minimumH264LevelIDC
    }

    /// 서버 AI 세션은 하드웨어 H.264 Constrained Baseline을 맨 앞에 두어 FHD 인코딩과 반환 영상 디코딩을
    /// VideoToolbox로 보낸다(Android `VideoCodecPreference.kt`와 같은 방식). 다음은 VP8이라 쓸 수 있는 Baseline이
    /// 없는 기기는 종전처럼 VP8로 협상한다. 온디바이스 세션은 VP8 우선을 유지한다. 둘 다 없으면 nil이다.
    static func ordered<Codec>(
        _ codecs: [Codec],
        for mode: VideoUplinkSessionMode,
        name: (Codec) -> String,
        parameters: (Codec) -> [String: String]
    ) -> [Codec]? {
        let baseline = mode == .serverAI
            ? codecs.indices.filter { isServerH264Baseline(name: name(codecs[$0]), parameters: parameters(codecs[$0])) }
            : []
        let vp8 = codecs.indices.filter { name(codecs[$0]).caseInsensitiveCompare("VP8") == .orderedSame }
        let leading = baseline + vp8
        guard !leading.isEmpty else { return nil }
        let rest = codecs.indices.filter { !leading.contains($0) }
        return (leading + rest).map { codecs[$0] }
    }
}
