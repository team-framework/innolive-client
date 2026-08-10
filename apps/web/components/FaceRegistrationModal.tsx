'use client'

import type { FaceDetector } from '@mediapipe/tasks-vision'
import type { TFunction } from 'i18next'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

type CameraPreviewState = 'loading' | 'ready' | 'registering' | 'failed'

type FaceRegistrationModalProps = {
  isOpen: boolean
  registrationURL?: string
  onClose: () => void
}

const cropSideLength = 500
const detectionInterval = 350
const visionWasmURL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm'

type FaceRegistrationErrorKind = 'camera' | 'detector' | 'image' | 'network' | 'server'

class FaceRegistrationError extends Error {
  constructor(
    readonly kind: FaceRegistrationErrorKind,
    message: string,
  ) {
    super(message)
  }
}

function cameraErrorMessage(error: unknown, t: TFunction): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError') {
      return t('faceRegistration.errors.cameraPermission')
    }

    if (error.name === 'NotFoundError') {
      return t('faceRegistration.errors.cameraNotFound')
    }
  }

  return t('faceRegistration.errors.cameraStartFailed')
}

function registrationErrorMessage(error: unknown, t: TFunction): string {
  if (error instanceof FaceRegistrationError) {
    return error.message
  }

  if (error instanceof DOMException) {
    return cameraErrorMessage(error, t)
  }

  return t('faceRegistration.errors.unknown')
}

function drawVisibleCrop(video: HTMLVideoElement, canvas: HTMLCanvasElement, t: TFunction) {
  const sourceWidth = video.videoWidth
  const sourceHeight = video.videoHeight
  const sourceSide = Math.min(sourceWidth, sourceHeight)

  if (sourceSide < cropSideLength) {
    throw new FaceRegistrationError('image', t('faceRegistration.errors.lowResolution'))
  }

  const context = canvas.getContext('2d')
  if (!context) {
    throw new FaceRegistrationError('image', t('faceRegistration.errors.imageCreate'))
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

function jpegBlob(canvas: HTMLCanvasElement, t: TFunction): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) {
        resolve(blob)
        return
      }

      reject(new FaceRegistrationError('image', t('faceRegistration.errors.jpegConversion')))
    }, 'image/jpeg', 0.9)
  })
}

async function registerReferenceFace(registrationURL: string, image: Blob, t: TFunction) {
  const formData = new FormData()
  formData.append('image', image, 'reference-face.jpg')

  let response: Response
  try {
    response = await fetch(registrationURL, {
      method: 'POST',
      body: formData,
    })
  } catch {
    throw new FaceRegistrationError('network', t('faceRegistration.errors.serverConnection'))
  }

  if (!response.ok) {
    if (response.status === 400) {
      throw new FaceRegistrationError('server', t('faceRegistration.errors.faceNotDetected'))
    }

    throw new FaceRegistrationError('server', t('faceRegistration.errors.serverRequest', { status: response.status }))
  }

  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    throw new FaceRegistrationError('server', t('faceRegistration.errors.serverResponseParse'))
  }

  if (!payload || typeof payload !== 'object' || (payload as { registered?: unknown }).registered !== true) {
    throw new FaceRegistrationError('server', t('faceRegistration.errors.serverIncomplete'))
  }
}

async function createFaceDetector(t: TFunction): Promise<FaceDetector> {
  try {
    const { FaceDetector, FilesetResolver } = await import('@mediapipe/tasks-vision')
    const vision = await FilesetResolver.forVisionTasks(visionWasmURL)

    return FaceDetector.createFromOptions(vision, {
      baseOptions: { modelAssetPath: '/models/blaze-face-short-range.tflite' },
      runningMode: 'VIDEO',
      minDetectionConfidence: 0.6,
    })
  } catch {
    throw new FaceRegistrationError('detector', t('faceRegistration.errors.detectorStart'))
  }
}

export function FaceRegistrationModal({ isOpen, registrationURL, onClose }: FaceRegistrationModalProps) {
  const { t } = useTranslation()
  const videoRef = useRef<HTMLVideoElement>(null)
  const cropCanvasRef = useRef<HTMLCanvasElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const detectorRef = useRef<FaceDetector | null>(null)
  const detectionTimerRef = useRef<number | null>(null)
  const isRegisteringRef = useRef(false)
  const [cameraState, setCameraState] = useState<CameraPreviewState>('loading')
  const [cameraMessage, setCameraMessage] = useState(() => t('faceRegistration.status.preparing'))
  const [registrationDotCount, setRegistrationDotCount] = useState(1)

  useEffect(() => {
    if (!isOpen) {
      return
    }

    if (!registrationURL) {
      setCameraState('failed')
      setCameraMessage(t('experience.status.faceServerMissing'))
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

        drawVisibleCrop(video, canvas, t)
        let result: ReturnType<FaceDetector['detectForVideo']>
        try {
          result = detector.detectForVideo(canvas, performance.now())
        } catch {
          throw new FaceRegistrationError('detector', t('faceRegistration.errors.detectorRun'))
        }
        if (result.detections.length === 0) {
          scheduleDetection(detectFace)
          return
        }

        isRegisteringRef.current = true
        setCameraState('registering')
        setCameraMessage(t('faceRegistration.status.registeringDetected'))
        const image = await jpegBlob(canvas, t)
        await registerReferenceFace(registrationURL, image, t)

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
          setCameraMessage(registrationErrorMessage(error, t))
        }
      }
    }

    const startCamera = async () => {
      setCameraState('loading')
      setCameraMessage(t('faceRegistration.status.preparing'))
      isRegisteringRef.current = false

      try {
        if (!navigator.mediaDevices?.getUserMedia) {
          throw new FaceRegistrationError('camera', t('faceRegistration.errors.mediaUnavailable'))
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
          throw new FaceRegistrationError('camera', t('faceRegistration.errors.previewStart'))
        }

        videoRef.current.srcObject = stream
        await videoRef.current.play()
        detectorRef.current = await createFaceDetector(t)

        if (!isActive) {
          detectorRef.current.close()
          detectorRef.current = null
          return
        }

        setCameraState('ready')
        setCameraMessage(t('faceRegistration.status.finding'))
        await detectFace()
      } catch (error) {
        if (isActive) {
          stopCamera()
          setCameraState('failed')
          setCameraMessage(registrationErrorMessage(error, t))
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
  }, [isOpen, onClose, registrationURL, t])

  useEffect(() => {
    if (cameraState !== 'registering') {
      setRegistrationDotCount(1)
      return
    }

    const interval = window.setInterval(() => {
      setRegistrationDotCount((currentCount) => currentCount === 3 ? 1 : currentCount + 1)
    }, 500)

    return () => window.clearInterval(interval)
  }, [cameraState])

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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 px-6 backdrop-blur-sm" role="presentation" onMouseDown={onClose}>
      <section className="h-full w-full max-w-[26rem] overflow-y-auto bg-black px-6 pb-[clamp(42px,11vh,96px)] pt-[clamp(52px,14vh,122px)] text-center shadow-[0_20px_42px_rgba(0,0,0,0.55)]" role="dialog" aria-modal="true" aria-labelledby="face-registration-title" aria-describedby="face-registration-guide" onMouseDown={(event) => event.stopPropagation()}>
        <button type="button" className="sr-only" onClick={onClose}>{t('faceRegistration.close')}</button>
        <h2 id="face-registration-title" className="font-neurimbo text-[3rem] font-normal leading-[0.9] tracking-[-0.1em]">{t('faceRegistration.title')}</h2>
        <p id="face-registration-guide" className="mt-4 text-[1rem] font-normal leading-none tracking-[-0.06em]">{t('faceRegistration.guide')}</p>

        <div className="relative mx-auto mt-8 aspect-square w-[min(100%,400px,calc(100dvh-365px))] overflow-hidden rounded-full border border-[#656565] bg-[#171717]">
          <video ref={videoRef} autoPlay muted playsInline className="size-full scale-x-[-1] object-cover" aria-label={t('faceRegistration.previewLabel')} />
          {cameraState !== 'ready' && (
            <div className="absolute inset-0 grid place-items-center bg-black/55 px-8 text-center text-sm leading-5 text-white/80">
              {cameraState === 'registering' ? (
                <div className="flex flex-col items-center gap-3">
                  <div className="flex items-end gap-2" aria-hidden="true">
                    {[0, 160, 320].map((delay) => (
                      <span key={delay} className="size-2.5 animate-bounce rounded-full bg-white" style={{ animationDelay: `${delay}ms` }} />
                    ))}
                  </div>
                  <p className="font-semibold text-white">{t('faceRegistration.status.registering')}{'.'.repeat(registrationDotCount)}</p>
                </div>
              ) : cameraState === 'loading' ? t('faceRegistration.status.loading') : cameraMessage}
            </div>
          )}
        </div>
        <canvas ref={cropCanvasRef} className="sr-only" aria-hidden="true" />

        <p className="sr-only" role="status" aria-live="polite">{cameraMessage}</p>
      </section>
    </div>
  )
}
