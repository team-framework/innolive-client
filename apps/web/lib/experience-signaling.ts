import type { TFunction } from 'i18next'
import type { MutableRefObject } from 'react'

type SignalingMessage = {
  type?: string
  sdp?: string
  candidate?: string
  sdpMid?: string | null
  sdpMLineIndex?: number | null
}

type ExperienceSignalingOptions = {
  signalingURL: URL
  sessionID: string
  peerConnection: RTCPeerConnection
  pendingCandidatesRef: MutableRefObject<RTCIceCandidateInit[]>
  isCurrentAttempt: () => boolean
  onStatusMessage: (message: string) => void
  onFailure: (message: string) => void
  t: TFunction
}

export function createExperienceSignalingSocket({
  signalingURL,
  sessionID,
  peerConnection,
  pendingCandidatesRef,
  isCurrentAttempt,
  onStatusMessage,
  onFailure,
  t,
}: ExperienceSignalingOptions) {
  const socket = new WebSocket(signalingURL)

  socket.onopen = async () => {
    try {
      if (!isCurrentAttempt()) {
        return
      }

      onStatusMessage(t('experience.status.creatingOffer'))
      const offer = await peerConnection.createOffer()
      await peerConnection.setLocalDescription(offer)
      socket.send(JSON.stringify({ session_id: sessionID, type: 'offer', sdp: offer.sdp }))
      onStatusMessage(t('experience.status.waitingForServer'))
    } catch (error) {
      onFailure(error instanceof Error ? error.message : t('experience.errors.offerCreationFailed'))
    }
  }

  socket.onmessage = async (event) => {
    try {
      if (!isCurrentAttempt()) {
        return
      }

      const message: SignalingMessage = JSON.parse(String(event.data))
      if (message.type === 'error') {
        throw new Error(t('experience.errors.serverRejected'))
      }

      if (message.type === 'answer') {
        if (!message.sdp) {
          throw new Error(t('experience.errors.missingAnswerSdp'))
        }

        await peerConnection.setRemoteDescription({ type: 'answer', sdp: message.sdp })
        for (const candidate of pendingCandidatesRef.current) {
          await peerConnection.addIceCandidate(candidate)
        }
        pendingCandidatesRef.current = []
        onStatusMessage(t('experience.status.completingConnection'))
        return
      }

      if (message.type === 'ice_candidate' && message.candidate) {
        const candidate = {
          candidate: message.candidate,
          sdpMid: message.sdpMid ?? null,
          sdpMLineIndex: message.sdpMLineIndex ?? null,
        }
        if (peerConnection.remoteDescription) {
          await peerConnection.addIceCandidate(candidate)
        } else {
          pendingCandidatesRef.current.push(candidate)
        }
      }
    } catch (error) {
      onFailure(error instanceof Error ? error.message : t('experience.errors.signalingFailed'))
    }
  }

  socket.onerror = () => {
    if (isCurrentAttempt()) {
      onStatusMessage(t('experience.status.checkingWebSocketError'))
    }
  }

  socket.onclose = (event) => {
    if (isCurrentAttempt() && peerConnection.connectionState !== 'connected') {
      const reason = event.reason ? `: ${event.reason}` : ''
      onFailure(t('experience.errors.webSocketClosed', { code: event.code, reason }))
    }
  }

  return socket
}
