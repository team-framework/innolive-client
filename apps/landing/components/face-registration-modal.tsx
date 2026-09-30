"use client";

import Image from "next/image";
import type { FaceDetector } from "@mediapipe/tasks-vision";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/button";
import { Dialog } from "@/components/dialog";
import { useLocale } from "@/components/locale-provider";
import type { Messages } from "@/lib/messages";

type FaceRegistrationModalProps = {
  isOpen: boolean;
  stream: MediaStream | null;
  onClose: () => void;
  onRegistered: () => void;
};

type FaceRegistrationState = "loading" | "ready" | "registering" | "failed";

class FaceRegistrationError extends Error {
  constructor(message: string) {
    super(message);
  }
}

type FaceRegistrationCopy = Messages["faceRegistration"];

const cropSideLength = 500;
const stableFaceFrames = 3;
const detectionInterval = 350;
const maxImageBytes = 10 * 1024 * 1024;
const visionWasmURL = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm";

function drawVisibleCrop(
  video: HTMLVideoElement,
  canvas: HTMLCanvasElement,
  lowResolutionMessage: string,
  imageCreateMessage: string,
) {
  const sourceWidth = video.videoWidth;
  const sourceHeight = video.videoHeight;
  const sourceSide = Math.min(sourceWidth, sourceHeight);
  if (sourceSide < cropSideLength) {
    throw new FaceRegistrationError(lowResolutionMessage);
  }

  const context = canvas.getContext("2d");
  if (!context) {
    throw new FaceRegistrationError(imageCreateMessage);
  }

  canvas.width = cropSideLength;
  canvas.height = cropSideLength;
  // Match object-cover and enclose the Figma guide (60, 59, 285, 316)
  // in a square without stretching the face in the uploaded JPEG.
  const scale = Math.max(405 / sourceWidth, 720 / sourceHeight);
  const croppedX = (sourceWidth * scale - 405) / 2;
  const croppedY = (sourceHeight * scale - 720) / 2;
  const guideSide = 316 / scale;
  context.drawImage(
    video,
    (croppedX + (405 - 316) / 2) / scale,
    (croppedY + 59) / scale,
    guideSide,
    guideSide,
    0,
    0,
    cropSideLength,
    cropSideLength,
  );
}

function toJPEG(canvas: HTMLCanvasElement, conversionMessage: string) {
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) {
        reject(new FaceRegistrationError(conversionMessage));
        return;
      }
      if (blob.size > maxImageBytes) {
        reject(new FaceRegistrationError(conversionMessage));
        return;
      }
      resolve(blob);
    }, "image/jpeg", 0.9);
  });
}

async function createFaceDetector(detectorMessage: string) {
  try {
    const { FaceDetector, FilesetResolver } = await import("@mediapipe/tasks-vision");
    const vision = await FilesetResolver.forVisionTasks(visionWasmURL);
    return await FaceDetector.createFromOptions(vision, {
      baseOptions: { modelAssetPath: "/models/blaze-face-short-range.tflite" },
      runningMode: "VIDEO",
      minDetectionConfidence: 0.6,
    });
  } catch {
    throw new FaceRegistrationError(detectorMessage);
  }
}

async function registerReferenceFace(image: Blob, copy: FaceRegistrationCopy) {
  const formData = new FormData();
  formData.append("image", image, "reference-face.jpg");

  let response: Response;
  try {
    response = await fetch("/api/reference-face", {
      method: "POST",
      body: formData,
      cache: "no-store",
      credentials: "include",
    });
  } catch {
    throw new FaceRegistrationError(copy.errors.serverConnection);
  }

  if (!response.ok) {
    if (response.status === 400) {
      throw new FaceRegistrationError(copy.errors.faceNotDetected);
    }
    if (response.status === 401) {
      throw new FaceRegistrationError(copy.errors.sessionExpired);
    }
    throw new FaceRegistrationError(copy.errors.serverRequest);
  }

  const payload = (await response.json().catch(() => null)) as { registered?: unknown } | null;
  if (payload?.registered !== true) {
    throw new FaceRegistrationError(copy.errors.serverIncomplete);
  }
}

function errorMessage(
  error: unknown,
  copy: FaceRegistrationCopy,
) {
  if (error instanceof FaceRegistrationError) return error.message;
  if (error instanceof DOMException && error.name === "NotAllowedError") {
    return copy.errors.cameraPermission;
  }
  if (error instanceof DOMException && error.name === "NotFoundError") {
    return copy.errors.cameraNotFound;
  }
  return copy.errors.unknown;
}

export function FaceRegistrationModal({
  isOpen,
  stream,
  onClose,
  onRegistered,
}: FaceRegistrationModalProps) {
  const { messages } = useLocale();
  const copy = messages.faceRegistration;
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const detectorRef = useRef<FaceDetector | null>(null);
  const detectionTimerRef = useRef<number | null>(null);
  const stableFaceCountRef = useRef(0);
  const isRegisteringRef = useRef(false);
  const [retryCount, setRetryCount] = useState(0);
  const [state, setState] = useState<FaceRegistrationState>("loading");
  const [status, setStatus] = useState(copy.status.preparing);

  useEffect(() => {
    if (!isOpen) return;

    let active = true;
    const video = videoRef.current;
    const canvas = canvasRef.current;
    stableFaceCountRef.current = 0;
    isRegisteringRef.current = false;

    const stopPreview = () => {
      if (!video) return;
      video.pause();
      video.srcObject = null;
    };

    const scheduleDetection = (detectFace: () => Promise<void>) => {
      detectionTimerRef.current = window.setTimeout(() => void detectFace(), detectionInterval);
    };

    const detectFace = async () => {
      if (!active || isRegisteringRef.current) return;
      const detector = detectorRef.current;
      if (!video || !canvas || !detector || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
        scheduleDetection(detectFace);
        return;
      }

      try {
        drawVisibleCrop(video, canvas, copy.errors.lowResolution, copy.errors.imageCreate);
        const detections = detector.detectForVideo(canvas, performance.now()).detections;
        if (detections.length !== 1) {
          stableFaceCountRef.current = 0;
          setState("ready");
          setStatus(detections.length === 0 ? copy.status.finding : copy.status.multiple);
          scheduleDetection(detectFace);
          return;
        }

        stableFaceCountRef.current += 1;
        if (stableFaceCountRef.current < stableFaceFrames) {
          setState("ready");
          setStatus(copy.status.hold);
          scheduleDetection(detectFace);
          return;
        }

        isRegisteringRef.current = true;
        setState("registering");
        setStatus(copy.status.registering);
        const image = await toJPEG(canvas, copy.errors.jpegConversion);
        await registerReferenceFace(image, copy);
        if (!active) return;
        onRegistered();
        onClose();
      } catch (error) {
        if (!active) return;
        isRegisteringRef.current = false;
        detectorRef.current?.close();
        detectorRef.current = null;
        stopPreview();
        setState("failed");
        setStatus(errorMessage(error, copy));
      }
    };

    const start = async () => {
      try {
        if (!stream || !video || !canvas) {
          throw new FaceRegistrationError(copy.errors.previewStart);
        }
        video.srcObject = stream;
        await video.play();
        detectorRef.current = await createFaceDetector(copy.errors.detectorStart);
        if (!active) return;
        setState("ready");
        setStatus(copy.status.finding);
        await detectFace();
      } catch (error) {
        if (!active) return;
        detectorRef.current?.close();
        detectorRef.current = null;
        stopPreview();
        setState("failed");
        setStatus(errorMessage(error, copy));
      }
    };

    void start();
    return () => {
      active = false;
      if (detectionTimerRef.current !== null) {
        window.clearTimeout(detectionTimerRef.current);
        detectionTimerRef.current = null;
      }
      detectorRef.current?.close();
      detectorRef.current = null;
      isRegisteringRef.current = false;
      stopPreview();
    };
  }, [copy, isOpen, onClose, onRegistered, retryCount, stream]);

  return (
    <Dialog open={isOpen} onClose={onClose} label={copy.dialogLabel} className="zoom-[0.7]">
      <div className="flex h-[calc(90dvh-4rem)] w-[min(calc(100vw-3rem),40.4375rem)] max-w-[40.4375rem] flex-col overflow-clip rounded-[12px] bg-background-secondary text-text-primary min-[48rem]:h-[45rem] min-[48rem]:flex-row">
        <div className="flex w-full shrink-0 flex-col items-start justify-between gap-4 p-4 min-[48rem]:gap-8 min-[48rem]:p-8 min-[48rem]:w-[15.125rem] min-[48rem]:shrink-0">
          <div className="flex flex-col gap-2.5">
            <h2 className="text-[2rem] font-medium leading-none min-[48rem]:text-[2.25rem]">{messages.face.title}</h2>
            <p className="text-lg font-normal leading-[1.2] text-text-secondary min-[48rem]:text-2xl">{messages.face.subtitle}</p>
          </div>
          <p className="w-full text-center text-xs leading-[1.3] text-text-secondary">{messages.face.termsNote}</p>
        </div>

        <div className="relative hidden w-0 self-stretch min-[48rem]:block" aria-hidden="true">
          <Image src="/try-out/face-divider.svg" alt="" width={720} height={1} unoptimized className="absolute left-0 top-0 max-w-none origin-top-left rotate-90" />
        </div>
        <div className="flex min-h-0 w-full flex-1 items-center justify-center [container-type:size] min-[48rem]:w-[25.3125rem] min-[48rem]:flex-none">
          <div className="relative aspect-[405/720] w-[min(100cqw,56.25cqh)] overflow-hidden bg-black">
            <video
              ref={videoRef}
              autoPlay
              muted
              playsInline
              className="absolute inset-0 size-full scale-x-[-1] object-cover"
              aria-label={copy.previewLabel}
            />
            <Image src="/try-out/face-guide.svg" alt="" width={405} height={720} unoptimized className="pointer-events-none absolute inset-0 h-auto w-full" />
            <div className="absolute inset-x-[13.086%] bottom-[6.528%] flex flex-col items-center gap-2 text-center text-white">
              <p className="w-full text-base font-semibold leading-none min-[48rem]:text-xl" role="status" aria-live="polite">{status}</p>
              {state === "failed" ? (
                <Button showChevron={false} onClick={() => setRetryCount((count) => count + 1)}>
                  {copy.retry}
                </Button>
              ) : (
                <p className="w-full text-sm font-medium leading-[1.3] text-[#fbfbfb] min-[48rem]:text-base">{messages.face.guide}</p>
              )}
            </div>
          </div>
        </div>
        <canvas ref={canvasRef} className="sr-only" aria-hidden="true" />
      </div>
    </Dialog>
  );
}
