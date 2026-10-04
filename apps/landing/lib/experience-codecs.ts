/** The processed-video path is verified with VP8 in Safari; retain all fallback codecs. */
export function preferExperienceVideoCodec(
  peerConnection: RTCPeerConnection,
  codecs: RTCRtpCodec[] | undefined,
) {
  if (!codecs?.some((codec) => codec.mimeType.toLowerCase() === "video/vp8")) return;
  const preferred = [
    ...codecs.filter((codec) => codec.mimeType.toLowerCase() === "video/vp8"),
    ...codecs.filter((codec) => codec.mimeType.toLowerCase() !== "video/vp8"),
  ];
  for (const transceiver of peerConnection.getTransceivers()) {
    if (transceiver.sender.track?.kind === "video") {
      transceiver.setCodecPreferences?.(preferred);
    }
  }
}
