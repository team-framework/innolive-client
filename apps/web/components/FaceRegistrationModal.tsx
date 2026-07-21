'use client'

import { useEffect, useRef, useState } from 'react'

type CameraPreviewState = 'loading' | 'ready' | 'failed'

type FaceRegistrationModalProps = {
  isOpen: boolean
  onClose: () => void
}

function cameraErrorMessage(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError') {
      return '카메라 권한을 허용해 주세요.'
    }

    if (error.name === 'NotFoundError') {
      return '사용할 수 있는 카메라를 찾지 못했습니다.'
    }
  }

  return '카메라를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export function FaceRegistrationModal({ isOpen, onClose }: FaceRegistrationModalProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const [cameraState, setCameraState] = useState<CameraPreviewState>('loading')
  const [cameraMessage, setCameraMessage] = useState('카메라를 준비하는 중입니다.')

  useEffect(() => {
    if (!isOpen) {
      return
    }

    let isActive = true
    let stream: MediaStream | null = null

    const startCamera = async () => {
      setCameraState('loading')
      setCameraMessage('카메라를 준비하는 중입니다.')

      try {
        if (!navigator.mediaDevices?.getUserMedia) {
          throw new Error('getUserMedia is unavailable')
        }

        stream = await navigator.mediaDevices.getUserMedia({
          video: {
            width: { ideal: 1280 },
            height: { ideal: 1280 },
            facingMode: 'user',
          },
          audio: false,
        })

        if (!isActive) {
          stream.getTracks().forEach((track) => track.stop())
          return
        }

        if (videoRef.current) {
          videoRef.current.srcObject = stream
          await videoRef.current.play()
        }

        if (isActive) {
          setCameraState('ready')
          setCameraMessage('카메라가 준비되었습니다.')
        }
      } catch (error) {
        if (isActive) {
          setCameraState('failed')
          setCameraMessage(cameraErrorMessage(error))
        }
      }
    }

    void startCamera()

    return () => {
      isActive = false
      stream?.getTracks().forEach((track) => track.stop())
      if (videoRef.current) {
        videoRef.current.srcObject = null
      }
    }
  }, [isOpen])

  useEffect(() => {
    if (!isOpen) {
      return
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, onClose])

  if (!isOpen) {
    return null
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 px-6 py-8 backdrop-blur-sm" role="presentation" onMouseDown={onClose}>
      <section className="max-h-[calc(100dvh-4rem)] w-full max-w-[496px] overflow-y-auto rounded-[18px] bg-black px-6 pb-[clamp(42px,11vh,96px)] pt-[clamp(52px,14vh,122px)] text-center shadow-[0_20px_42px_rgba(0,0,0,0.55)]" role="dialog" aria-modal="true" aria-labelledby="face-registration-title" aria-describedby="face-registration-guide" onMouseDown={(event) => event.stopPropagation()}>
        <button type="button" className="sr-only" onClick={onClose}>얼굴 등록 팝업 닫기</button>
        <h2 id="face-registration-title" className="font-neurimbo text-[3.75rem] font-normal leading-[0.9] tracking-[-0.1em]">얼굴 등록</h2>
        <p id="face-registration-guide" className="mt-4 text-[1.5rem] font-normal leading-none tracking-[-0.06em]">얼굴을 원 가운데에 위치시켜 주세요.</p>

        <div className="relative mx-auto mt-8 aspect-square w-[min(100%,400px,calc(100dvh-365px))] overflow-hidden rounded-full border border-[#656565] bg-[#171717]">
          <video ref={videoRef} autoPlay muted playsInline className="size-full scale-x-[-1] object-cover" aria-label="얼굴 등록 카메라 미리보기" />
          {cameraState !== 'ready' && <div className="absolute inset-0 grid place-items-center bg-black/55 px-8 text-sm text-white/80">{cameraState === 'loading' ? '카메라 준비 중' : '카메라를 표시할 수 없습니다.'}</div>}
        </div>

        <p className="sr-only" role="status" aria-live="polite">{cameraMessage}</p>
      </section>
    </div>
  )
}
