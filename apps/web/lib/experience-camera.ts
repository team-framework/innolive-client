const preferredAspectRatio = 16 / 9

export async function getBestAvailableCameraStream() {
  const stream = await navigator.mediaDevices.getUserMedia({
    video: {
      width: { ideal: 3840 },
      height: { ideal: 2160 },
      aspectRatio: { ideal: preferredAspectRatio },
      frameRate: { ideal: 60 },
    },
    audio: false,
  })
  const videoTrack = stream.getVideoTracks()[0]

  if (!videoTrack) {
    return stream
  }

  const capabilities = videoTrack.getCapabilities()
  const maxWidth = capabilities.width?.max
  const maxHeight = capabilities.height?.max
  const maxFrameRate = capabilities.frameRate?.max
  const targetWidth = maxWidth && maxHeight ? Math.min(maxWidth, Math.floor(maxHeight * preferredAspectRatio)) : maxWidth
  const targetHeight = targetWidth ? Math.round(targetWidth / preferredAspectRatio) : maxHeight

  try {
    // 최대 해상도 안에서 16:9 비율로 요청해 가로·세로 최대값 조합으로 비율이 흐트러지지 않게 함
    await videoTrack.applyConstraints({
      width: targetWidth ? { ideal: targetWidth } : undefined,
      height: targetHeight ? { ideal: targetHeight } : undefined,
      aspectRatio: { ideal: preferredAspectRatio },
      frameRate: maxFrameRate ? { ideal: Math.min(maxFrameRate, 60) } : undefined,
    })
  } catch {
  }

  return stream
}
