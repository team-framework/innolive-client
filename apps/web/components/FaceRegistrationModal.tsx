'use client'

import type { FaceDetector } from '@mediapipe/tasks-vision'
import { useEffect, useRef, useState } from 'react'

type CameraPreviewState = 'loading' | 'ready' | 'registering' | 'failed'

type FaceRegistrationModalProps = {
  isOpen: boolean
  registrationURL?: string
  onClose: () => void
}

const cropSideLength = 500
const detectionInterval = 350
const visionWasmURL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm'

function cameraErrorMessage(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError') {
      return '카메라 권한을 허용해 주세요.'
    }

    if (error.name === 'NotFoundError') {
      return '사용할 수 있는 카메라를 찾지 못했습니다.'
    }
  }

  return error instanceof Error ? error.message : '카메라를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

function drawVisibleCrop(video: HTMLVideoElement, canvas: HTMLCanvasElement) {
  const sourceWidth = video.videoWidth
  const sourceHeight = video.videoHeight
  const sourceSide = Math.min(sourceWidth, sourceHeight)

  if (sourceSide < cropSideLength) {
    throw new Error('얼굴 등록에는 최소 500 x 500 크기의 카메라 프레임이 필요합니다.')
  }

  const context = canvas.getContext('2d')
  if (!context) {
    throw new Error('얼굴 등록용 이미지를 생성하지 못했습니다.')
  }

  canvas.width = cropSideLength
  canvas.height = cropSideLength
  context.drawImage(
    video,
    (sourceWidth - sourceSide) / 2,
    (sourceHeight - sourceSide) / 2,
    sourceSide,
    sourceSide,
    0,
    0,
    cropSideLength,
    cropSideLength,
  )
}

function jpegBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) {
        resolve(blob)
        return
      }

      reject(new Error('캡처한 얼굴 이미지를 JPEG로 변환하지 못했습니다.'))
    }, 'image/jpeg', 0.9)
  })
}

async function registerReferenceFace(registrationURL: string, image: Blob) {
  const formData = new FormData()
  formData.append('image', image, 'reference-face.jpg')

  const response = await fetch(registrationURL, {
    method: 'POST',
    body: formData,
  })

  if (!response.ok) {
    throw new Error(`얼굴 등록 서버 요청에 실패했습니다. (${response.status})`)
  }

  const payload: unknown = await response.json()
  if (!payload || typeof payload !== 'object' || (payload as { registered?: unknown }).registered !== true) {
    throw new Error('얼굴 등록 서버가 완료 상태를 반환하지 않았습니다.')
  }
}

async function createFaceDetector(): Promise<FaceDetector> {
  const { FaceDetector, FilesetResolver } = await import('@mediapipe/tasks-vision')
  const vision = await FilesetResolver.forVisionTasks(visionWasmURL)

  return FaceDetector.createFromOptions(vision, {
    baseOptions: { modelAssetPath: '/models/blaze-face-short-range.tflite' },
    runningMode: 'VIDEO',
    minDetectionConfidence: 0.6,
  })
}

export function FaceRegistrationModal({ isOpen, registrationURL, onClose }: FaceRegistrationModalProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const cropCanvasRef = useRef<HTMLCanvasElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const detectorRef = useRef<FaceDetector | null>(null)
  const detectionTimerRef = useRef<number | null>(null)
  const isRegisteringRef = useRef(false)
  const [cameraState, setCameraState] = useState<CameraPreviewState>('loading')
  const [cameraMessage, setCameraMessage] = useState('카메라를 준비하는 중입니다.')

  useEffect(() => {
    if (!isOpen) {
      return
    }

    if (!registrationURL) {
      setCameraState('failed')
      setCameraMessage('얼굴 등록 서버 주소를 확인하지 못했습니다.')
      return
    }

    let isActive = true

    const stopCamera = () => {
      streamRef.current?.getTracks().forEach((track) => track.stop())
      streamRef.current = null
      if (videoRef.current) {
        videoRef.current.srcObject = null
      }
    }

    const scheduleDetection = (detectFace: () => Promise<void>) => {
      detectionTimerRef.current = window.setTimeout(() => {
        void detectFace()
      }, detectionInterval)
    }

    const detectFace = async () => {
      if (!isActive || isRegisteringRef.current) {
        return
      }

      try {
        const video = videoRef.current
        const canvas = cropCanvasRef.current
        const detector = detectorRef.current
        if (!video || !canvas || !detector || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
          scheduleDetection(detectFace)
          return
        }

        drawVisibleCrop(video, canvas)
        const result = detector.detectForVideo(canvas, performance.now())
        if (result.detections.length === 0) {
          scheduleDetection(detectFace)
          return
        }

        isRegisteringRef.current = true
        setCameraState('registering')
        setCameraMessage('감지된 얼굴 이미지를 서버에 등록하고 있습니다.')
        const image = await jpegBlob(canvas)
        await registerReferenceFace(registrationURL, image)

        if (!isActive) {
          return
        }

        stopCamera()
        onClose()
      } catch (error) {
        if (isActive) {
          isRegisteringRef.current = false
          stopCamera()
          setCameraState('failed')
          setCameraMessage(cameraErrorMessage(error))
        }
      }
    }

    const startCamera = async () => {
      setCameraState('loading')
      setCameraMessage('카메라를 준비하는 중입니다.')
      isRegisteringRef.current = false

      try {
        if (!navigator.mediaDevices?.getUserMedia) {
          throw new Error('getUserMedia is unavailable')
        }

        const stream = await navigator.mediaDevices.getUserMedia({
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

        streamRef.current = stream
        if (!videoRef.current) {
          throw new Error('카메라 미리보기를 시작하지 못했습니다.')
        }

        videoRef.current.srcObject = stream
        await videoRef.current.play()
        detectorRef.current = await createFaceDetector()

        if (!isActive) {
          detectorRef.current.close()
          detectorRef.current = null
          return
        }

        setCameraState('ready')
        setCameraMessage('얼굴을 찾는 중입니다.')
        await detectFace()
      } catch (error) {
        if (isActive) {
          stopCamera()
          setCameraState('failed')
          setCameraMessage(cameraErrorMessage(error))
        }
      }
    }

    void startCamera()

    return () => {
      isActive = false
      if (detectionTimerRef.current !== null) {
        window.clearTimeout(detectionTimerRef.current)
        detectionTimerRef.current = null
      }
      detectorRef.current?.close()
      detectorRef.current = null
      stopCamera()
    }
  }, [isOpen, onClose, registrationURL])

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
          {cameraState !== 'ready' && <div className="absolute inset-0 grid place-items-center bg-black/55 px-8 text-sm text-white/80">{cameraState === 'loading' ? '카메라 준비 중' : cameraState === 'registering' ? '얼굴 등록 중' : '카메라를 표시할 수 없습니다.'}</div>}
        </div>
        <canvas ref={cropCanvasRef} className="sr-only" aria-hidden="true" />

        <p className="sr-only" role="status" aria-live="polite">{cameraMessage}</p>
      </section>
    </div>
  )
}
