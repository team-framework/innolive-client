'use client'

import type { TFunction } from 'i18next'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { FaceRegistrationModal } from './FaceRegistrationModal'
import PrivacyPolicyModal from "./PrivacyPolicyModal";

type ExperienceState = 'idle' | 'connecting' | 'connected' | 'failed'

type SignalingMessage = {
  type?: string
  sdp?: string
  candidate?: string
  sdpMid?: string | null
  sdpMLineIndex?: number | null
  message?: string
  error?: { message?: string }
}

type ServerEndpoints = {
  signalingURL: URL
  sessionsURL: URL
}

const buttonClassName = 'cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition-colors duration-200 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white disabled:cursor-not-allowed disabled:bg-[#8f8f8f]'

function getServerEndpoints(t: TFunction): ServerEndpoints {
  const configuredURL = process.env.NEXT_PUBLIC_INNOLIVE_SIGNALING_URL?.trim()
  if (!configuredURL) {
    throw new Error(t('experience.errors.serverUrlMissing'))
  }

  const signalingURL = new URL(configuredURL)
  if (signalingURL.protocol === 'http:') {
    signalingURL.protocol = 'ws:'
  } else if (signalingURL.protocol === 'https:') {
    signalingURL.protocol = 'wss:'
  }

  if (signalingURL.protocol !== 'ws:' && signalingURL.protocol !== 'wss:') {
    throw new Error(t('experience.errors.serverUrlProtocol'))
  }

  const sessionsURL = new URL(signalingURL)
  sessionsURL.protocol = signalingURL.protocol === 'wss:' ? 'https:' : 'http:'
  sessionsURL.pathname = '/sessions'
  sessionsURL.search = ''
  sessionsURL.hash = ''

  return { signalingURL, sessionsURL }
}

export function WebRTCExperience() {
  const { t } = useTranslation()
  const [state, setState] = useState<ExperienceState>('idle')
  const [statusText, setStatusText] = useState(() => t('experience.status.idle'))
  const [isFaceRegistrationOpen, setIsFaceRegistrationOpen] = useState(false)
  const [faceRegistrationURL, setFaceRegistrationURL] = useState<string | null>(null)
  const videoRef = useRef<HTMLVideoElement>(null)
  const peerConnectionRef = useRef<RTCPeerConnection | null>(null)
  const socketRef = useRef<WebSocket | null>(null)
  const localStreamRef = useRef<MediaStream | null>(null)
  const remoteStreamRef = useRef<MediaStream | null>(null)
  const sessionIDRef = useRef<string | null>(null)
  const pendingCandidatesRef = useRef<RTCIceCandidateInit[]>([])
  const connectionAttemptRef = useRef(0)
  const connectionTimeoutRef = useRef<number | null>(null)
  const [isPrivacyPolicyOpen, setIsPrivacyPolicyOpen] = useState(false)

  const releaseResources = useCallback((deleteSession: boolean) => {
    const sessionID = sessionIDRef.current
    const socket = socketRef.current
    const peerConnection = peerConnectionRef.current
    const localStream = localStreamRef.current
    const remoteStream = remoteStreamRef.current

    socketRef.current = null
    peerConnectionRef.current = null
    localStreamRef.current = null
    remoteStreamRef.current = null
    sessionIDRef.current = null
    pendingCandidatesRef.current = []

    if (connectionTimeoutRef.current !== null) {
      window.clearTimeout(connectionTimeoutRef.current)
      connectionTimeoutRef.current = null
    }

    socket?.close()
    peerConnection?.close()
    localStream?.getTracks().forEach((track) => track.stop())
    remoteStream?.getTracks().forEach((track) => track.stop())

    if (videoRef.current) {
      videoRef.current.srcObject = null
    }

    if (deleteSession && sessionID) {
      try {
        const { sessionsURL } = getServerEndpoints(t)
        const sessionURL = new URL(`/sessions/${encodeURIComponent(sessionID)}`, sessionsURL)
        void fetch(sessionURL, { method: 'DELETE' })
      } catch {
        // 환경 변수 설정 오류는 이미 화면 상태로 안내하므로 종료 과정에서 다시 표시하지 않는다.
      }
    }
  }, [t])

  const failExperience = useCallback((message: string) => {
    connectionAttemptRef.current += 1
    releaseResources(true)
    setState('failed')
    setStatusText(message)
  }, [releaseResources])

  const endExperience = useCallback(() => {
    connectionAttemptRef.current += 1
    releaseResources(true)
    setState('idle')
    setStatusText(t('experience.status.ended'))
  }, [releaseResources, t])

  const startExperience = useCallback(async () => {
    const attempt = connectionAttemptRef.current + 1
    connectionAttemptRef.current = attempt
    setState('connecting')
    setStatusText(t('experience.status.preparing'))

    try {
      const { signalingURL, sessionsURL } = getServerEndpoints(t)
      const sessionResponse = await fetch(sessionsURL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          metadata: {
            title: 'InnoLive Web Experience',
            broadcaster_id: 'web-experience',
            client: 'innolive-web',
          },
        }),
      })

      if (!sessionResponse.ok) {
        throw new Error(t('experience.errors.sessionCreationFailed', { status: sessionResponse.status }))
      }

      const sessionPayload: unknown = await sessionResponse.json()
      if (!sessionPayload || typeof sessionPayload !== 'object' || typeof (sessionPayload as { session_id?: unknown }).session_id !== 'string') {
        throw new Error(t('experience.errors.missingSessionId'))
      }

      if (connectionAttemptRef.current !== attempt) {
        return
      }

      const sessionID = (sessionPayload as { session_id: string }).session_id
      sessionIDRef.current = sessionID
      setStatusText(t('experience.status.checkingCamera'))

      const localStream = await navigator.mediaDevices.getUserMedia({
        video: {
          width: { ideal: 1280 },
          height: { ideal: 720 },
          frameRate: { ideal: 30, max: 30 },
        },
        audio: false,
      })

      if (connectionAttemptRef.current !== attempt) {
        localStream.getTracks().forEach((track) => track.stop())
        return
      }

      localStreamRef.current = localStream
      const peerConnection = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
      })
      peerConnectionRef.current = peerConnection
      localStream.getTracks().forEach((track) => peerConnection.addTrack(track, localStream))
      connectionTimeoutRef.current = window.setTimeout(() => {
        if (connectionAttemptRef.current === attempt) {
          failExperience(t('experience.errors.connectionTimeout'))
        }
      }, 30_000)

      peerConnection.onicecandidate = (event) => {
        if (!event.candidate || connectionAttemptRef.current !== attempt || socketRef.current?.readyState !== WebSocket.OPEN) {
          return
        }

        socketRef.current.send(JSON.stringify({
          session_id: sessionID,
          type: 'ice_candidate',
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
        }))
      }

      peerConnection.onconnectionstatechange = () => {
        if (connectionAttemptRef.current !== attempt) {
          return
        }

        if (peerConnection.connectionState === 'connected') {
          if (connectionTimeoutRef.current !== null) {
            window.clearTimeout(connectionTimeoutRef.current)
            connectionTimeoutRef.current = null
          }
          setState('connected')
          setStatusText(t('experience.status.connected'))
          return
        }

        if (peerConnection.connectionState === 'failed' || peerConnection.connectionState === 'disconnected') {
          failExperience(t('experience.errors.connectionLost'))
        }
      }

      peerConnection.ontrack = (event) => {
        if (connectionAttemptRef.current !== attempt) {
          return
        }

        const remoteStream = event.streams[0] ?? new MediaStream([event.track])
        remoteStreamRef.current = remoteStream
        if (videoRef.current) {
          videoRef.current.srcObject = remoteStream
          void videoRef.current.play().catch(() => undefined)
        }
      }

      const socket = new WebSocket(signalingURL)
      socketRef.current = socket
      socket.onopen = async () => {
        try {
          if (connectionAttemptRef.current !== attempt) {
            return
          }

          setStatusText(t('experience.status.creatingOffer'))
          const offer = await peerConnection.createOffer()
          await peerConnection.setLocalDescription(offer)
          socket.send(JSON.stringify({
            session_id: sessionID,
            type: 'offer',
            sdp: offer.sdp,
          }))
          setStatusText(t('experience.status.waitingForServer'))
        } catch (error) {
          failExperience(error instanceof Error ? error.message : t('experience.errors.offerCreationFailed'))
        }
      }

      socket.onmessage = async (event) => {
        try {
          if (connectionAttemptRef.current !== attempt) {
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
            setStatusText(t('experience.status.completingConnection'))
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
          failExperience(error instanceof Error ? error.message : t('experience.errors.signalingFailed'))
        }
      }

      socket.onerror = () => {
        if (connectionAttemptRef.current === attempt) {
          setStatusText(t('experience.status.checkingWebSocketError'))
        }
      }

      socket.onclose = (event) => {
        if (connectionAttemptRef.current === attempt && peerConnection.connectionState !== 'connected') {
          const reason = event.reason ? `: ${event.reason}` : ''
          failExperience(t('experience.errors.webSocketClosed', { code: event.code, reason }))
        }
      }
    } catch (error) {
      if (connectionAttemptRef.current === attempt) {
        const message = error instanceof Error ? error.message : t('experience.errors.startFailed')
        failExperience(message)
      }
    }
  }, [failExperience, t])

  useEffect(() => () => {
    connectionAttemptRef.current += 1
    releaseResources(true)
  }, [releaseResources])

  useEffect(() => {
    const video = videoRef.current
    const remoteStream = remoteStreamRef.current
    if (state !== 'connected' || !video || !remoteStream) {
      return
    }

    video.srcObject = remoteStream
    void video.play().catch(() => undefined)
  }, [state])

  const isConnecting = state === 'connecting'
  const isConnected = state === 'connected'
  const primaryButtonText = isConnecting ? t('experience.buttons.connecting') : isConnected ? t('experience.buttons.end') : t('experience.buttons.start')

  const openPrivacyPolicy = () => {
    const isAgreed = localStorage.getItem('facePrivacyPolicy')

    if (isAgreed === "true") {
      openFaceRegistration()
    } else {
      setIsPrivacyPolicyOpen(true)
    }
  }

  const openFaceRegistration = useCallback(() => {
    const isAgreed = localStorage.getItem('facePrivacyPolicy')

    if (isAgreed === "true") {
      try {
        const { sessionsURL } = getServerEndpoints(t)
        setFaceRegistrationURL(new URL('/reference-face', sessionsURL).toString())
        setIsFaceRegistrationOpen(true)
      } catch (error) {
        setStatusText(error instanceof Error ? error.message : t('experience.status.faceServerMissing'))
      }
    }
  }, [t])

  return (
    <div className="flex w-full flex-col items-center gap-5">
      <div className="w-full max-w-[850px] border border-[#5c5c5c] p-px">
        {isConnected ? (
          <video ref={videoRef} autoPlay muted playsInline className="block aspect-video w-full border border-[#5c5c5c] bg-black object-contain" aria-label={t('experience.video.processedLabel')} />
        ) : (
          <img src="/images/live-pending.svg" alt={t('experience.video.pendingAlt')} className="block w-full border border-[#5c5c5c]" />
        )}
      </div>
      <div className="flex flex-col items-center">
        <p className="min-h-6 text-center text-sm text-[#c7c7c7]" role="status" aria-live="polite">{statusText}</p>
        <p className="min-h-6 text-center text-sm text-[#c7c7c7]">
          {t('experience.video.notStored')}
        </p>
      </div>
      <div className="flex gap-[clamp(6px,0.42vw,8px)]">
        <button type="button" className={buttonClassName} disabled={isConnecting} onClick={isConnected ? endExperience : startExperience}>{primaryButtonText}</button>
        <button type="button" className={buttonClassName} onClick={openPrivacyPolicy}>{t('experience.buttons.registerFace')}</button>
      </div>
      <PrivacyPolicyModal isOpen={isPrivacyPolicyOpen} onClose={() => {setIsPrivacyPolicyOpen(false); openFaceRegistration()}} />
      {faceRegistrationURL && <FaceRegistrationModal isOpen={isFaceRegistrationOpen} registrationURL={faceRegistrationURL} onClose={() => setIsFaceRegistrationOpen(false)} />}
    </div>
  )
}
