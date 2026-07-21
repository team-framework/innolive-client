'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

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

const buttonClassName = 'cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition duration-200 hover:-translate-y-0.5 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white disabled:cursor-not-allowed disabled:transform-none disabled:bg-[#8f8f8f]'

function getServerEndpoints(): ServerEndpoints {
  const configuredURL = process.env.NEXT_PUBLIC_INNOLIVE_SIGNALING_URL?.trim()
  if (!configuredURL) {
    throw new Error('체험 서버 주소가 설정되지 않았습니다.')
  }

  const signalingURL = new URL(configuredURL)
  if (signalingURL.protocol === 'http:') {
    signalingURL.protocol = 'ws:'
  } else if (signalingURL.protocol === 'https:') {
    signalingURL.protocol = 'wss:'
  }

  if (signalingURL.protocol !== 'ws:' && signalingURL.protocol !== 'wss:') {
    throw new Error('체험 서버 주소는 ws 또는 wss URL이어야 합니다.')
  }

  const sessionsURL = new URL(signalingURL)
  sessionsURL.protocol = signalingURL.protocol === 'wss:' ? 'https:' : 'http:'
  sessionsURL.pathname = '/sessions'
  sessionsURL.search = ''
  sessionsURL.hash = ''

  return { signalingURL, sessionsURL }
}

export function WebRTCExperience() {
  const [state, setState] = useState<ExperienceState>('idle')
  const [statusText, setStatusText] = useState('시작하기를 누르면 체험을 시작합니다.')
  const videoRef = useRef<HTMLVideoElement>(null)
  const peerConnectionRef = useRef<RTCPeerConnection | null>(null)
  const socketRef = useRef<WebSocket | null>(null)
  const localStreamRef = useRef<MediaStream | null>(null)
  const remoteStreamRef = useRef<MediaStream | null>(null)
  const sessionIDRef = useRef<string | null>(null)
  const pendingCandidatesRef = useRef<RTCIceCandidateInit[]>([])
  const connectionAttemptRef = useRef(0)
  const connectionTimeoutRef = useRef<number | null>(null)

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
        const { sessionsURL } = getServerEndpoints()
        const sessionURL = new URL(`/sessions/${encodeURIComponent(sessionID)}`, sessionsURL)
        void fetch(sessionURL, { method: 'DELETE' })
      } catch {
        // 환경 변수 설정 오류는 이미 화면 상태로 안내하므로 종료 과정에서 다시 표시하지 않는다.
      }
    }
  }, [])

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
    setStatusText('체험이 종료되었습니다. 다시 시작할 수 있습니다.')
  }, [releaseResources])

  const startExperience = useCallback(async () => {
    const attempt = connectionAttemptRef.current + 1
    connectionAttemptRef.current = attempt
    setState('connecting')
    setStatusText('카메라와 체험 서버를 준비하는 중입니다.')

    try {
      const { signalingURL, sessionsURL } = getServerEndpoints()
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
        throw new Error(`체험 세션 생성에 실패했습니다. (${sessionResponse.status})`)
      }

      const sessionPayload: unknown = await sessionResponse.json()
      if (!sessionPayload || typeof sessionPayload !== 'object' || typeof (sessionPayload as { session_id?: unknown }).session_id !== 'string') {
        throw new Error('체험 세션 응답에 session_id가 없습니다.')
      }

      if (connectionAttemptRef.current !== attempt) {
        return
      }

      const sessionID = (sessionPayload as { session_id: string }).session_id
      sessionIDRef.current = sessionID
      setStatusText('카메라 권한을 확인하는 중입니다.')

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
          failExperience('WebRTC 연결 시간이 초과되었습니다.')
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
          setStatusText('체험 서버에 연결되었습니다.')
          return
        }

        if (peerConnection.connectionState === 'failed' || peerConnection.connectionState === 'disconnected') {
          failExperience('WebRTC 연결이 끊겼습니다.')
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

          setStatusText('WebRTC offer를 생성하는 중입니다.')
          const offer = await peerConnection.createOffer()
          await peerConnection.setLocalDescription(offer)
          socket.send(JSON.stringify({
            session_id: sessionID,
            type: 'offer',
            sdp: offer.sdp,
          }))
          setStatusText('서버 응답을 기다리는 중입니다.')
        } catch (error) {
          failExperience(error instanceof Error ? error.message : 'WebRTC offer 생성에 실패했습니다.')
        }
      }

      socket.onmessage = async (event) => {
        try {
          if (connectionAttemptRef.current !== attempt) {
            return
          }

          const message: SignalingMessage = JSON.parse(String(event.data))
          if (message.type === 'error') {
            throw new Error(message.error?.message ?? message.message ?? '체험 서버가 연결을 거절했습니다.')
          }

          if (message.type === 'answer') {
            if (!message.sdp) {
              throw new Error('체험 서버 answer에 SDP가 없습니다.')
            }

            await peerConnection.setRemoteDescription({ type: 'answer', sdp: message.sdp })
            for (const candidate of pendingCandidatesRef.current) {
              await peerConnection.addIceCandidate(candidate)
            }
            pendingCandidatesRef.current = []
            setStatusText('WebRTC 연결을 완료하는 중입니다.')
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
          failExperience(error instanceof Error ? error.message : '체험 서버 신호 처리에 실패했습니다.')
        }
      }

      socket.onerror = () => {
        if (connectionAttemptRef.current === attempt) {
          setStatusText('WebSocket 오류를 확인하는 중입니다.')
        }
      }

      socket.onclose = (event) => {
        if (connectionAttemptRef.current === attempt && peerConnection.connectionState !== 'connected') {
          const reason = event.reason ? `: ${event.reason}` : ''
          failExperience(`체험 서버 WebSocket 연결이 종료되었습니다. (code ${event.code}${reason})`)
        }
      }
    } catch (error) {
      if (connectionAttemptRef.current === attempt) {
        const message = error instanceof Error ? error.message : '체험을 시작하지 못했습니다.'
        failExperience(message)
      }
    }
  }, [failExperience])

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
  const primaryButtonText = isConnecting ? '연결 중...' : isConnected ? '체험 종료' : '시작하기'

  return (
    <div className="flex w-full flex-col items-center gap-5">
      <div className="w-full max-w-[850px] border border-[#5c5c5c] p-px">
        {isConnected ? (
          <video ref={videoRef} autoPlay muted playsInline className="block aspect-video w-full border border-[#5c5c5c] bg-black object-contain" aria-label="InnoLive 서버 처리 영상" />
        ) : (
          <img src="/figma/live-pending.svg" alt="InnoLive 라이브 대기 화면" className="block w-full border border-[#5c5c5c]" />
        )}
      </div>
      <p className="min-h-6 text-center text-sm text-[#c7c7c7]" role="status" aria-live="polite">{statusText}</p>
      <div className="flex gap-[clamp(6px,0.42vw,8px)]">
        <button type="button" className={buttonClassName} disabled={isConnecting} onClick={isConnected ? endExperience : startExperience}>{primaryButtonText}</button>
        <button type="button" className={buttonClassName} disabled aria-label="얼굴 등록하기 기능은 준비 중입니다.">얼굴 등록하기</button>
      </div>
    </div>
  )
}
