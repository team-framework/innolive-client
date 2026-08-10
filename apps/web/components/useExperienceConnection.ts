'use client'

import type { TFunction } from 'i18next'
import { useCallback, useEffect, useRef, useState } from 'react'

import { getBestAvailableCameraStream } from '../lib/experience-camera'
import { createExperienceSession, deleteExperienceSession } from '../lib/experience-server'
import { createExperienceSignalingSocket } from '../lib/experience-signaling'

export type ExperienceState = 'idle' | 'connecting' | 'connected' | 'failed'

export function useExperienceConnection(t: TFunction) {
  const [state, setState] = useState<ExperienceState>('idle')
  const [statusText, setStatusText] = useState(() => t('experience.status.idle'))
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
      void deleteExperienceSession(sessionID, t)
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
      const { signalingURL, sessionID } = await createExperienceSession(t)

      if (connectionAttemptRef.current !== attempt) {
        return
      }

      sessionIDRef.current = sessionID
      setStatusText(t('experience.status.checkingCamera'))

      const localStream = await getBestAvailableCameraStream()

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

      const socket = createExperienceSignalingSocket({
        signalingURL,
        sessionID,
        peerConnection,
        pendingCandidatesRef,
        isCurrentAttempt: () => connectionAttemptRef.current === attempt,
        onStatusMessage: setStatusText,
        onFailure: failExperience,
        t,
      })
      socketRef.current = socket
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

  return { state, statusText, videoRef, startExperience, endExperience, showStatusMessage: setStatusText }
}
