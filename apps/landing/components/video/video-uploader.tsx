"use client";

import { type ChangeEvent, type DragEvent, useRef, useState } from "react";
import { Button } from "@/components/button";
import { useLocale } from "@/components/locale-provider";
import { interpolate } from "@/lib/locales";
import {
  uploadVodJob,
  VodRequestError,
  type VodClientConfig,
  type VodJob,
} from "@/lib/vod-client";
import {
  VOD_ACCEPT_ATTRIBUTE,
  VOD_ACCEPTED_LABEL,
  VOD_MAX_DURATION_MIN,
  VOD_MAX_GIGABYTES,
  validateVodDuration,
  validateVodFile,
  type VodValidationError,
} from "@/lib/vod-limits";
import styles from "./video.module.css";
import { cn } from "@/lib/cn";

type Phase = "idle" | "checking" | "ready" | "uploading";

// Probe duration by loading the file's metadata into a detached <video>. Rejects on load failure
// so the caller can surface the "metadata" message.
function probeDurationSec(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement("video");
    video.preload = "metadata";
    const cleanup = () => URL.revokeObjectURL(url);
    video.onloadedmetadata = () => {
      cleanup();
      resolve(video.duration);
    };
    video.onerror = () => {
      cleanup();
      reject(new Error("metadata"));
    };
    video.src = url;
  });
}

export function VideoUploader({
  config,
  onUploaded,
}: {
  config: VodClientConfig;
  onUploaded: (job: VodJob, fileName: string) => void;
}) {
  const { messages } = useLocale();
  const t = messages.video.upload;
  const inputRef = useRef<HTMLInputElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const [phase, setPhase] = useState<Phase>("idle");
  const [file, setFile] = useState<File | null>(null);
  const [progress, setProgress] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [errorKey, setErrorKey] = useState<VodValidationError | "upload" | null>(null);

  const hintValues = {
    size: String(VOD_MAX_GIGABYTES),
    minutes: String(VOD_MAX_DURATION_MIN),
    formats: VOD_ACCEPTED_LABEL,
  };

  const errorText = (key: VodValidationError | "upload"): string =>
    interpolate(t.errors[key], hintValues);

  const reset = () => {
    setFile(null);
    setProgress(0);
    setPhase("idle");
    if (inputRef.current) inputRef.current.value = "";
  };

  const acceptFile = async (candidate: File) => {
    setErrorKey(null);
    const basicError = validateVodFile(candidate);
    if (basicError) {
      setErrorKey(basicError);
      reset();
      return;
    }
    setFile(candidate);
    setPhase("checking");
    try {
      const duration = await probeDurationSec(candidate);
      const durationError = validateVodDuration(duration);
      if (durationError) {
        setErrorKey(durationError);
        reset();
        return;
      }
      setPhase("ready");
    } catch {
      setErrorKey("metadata");
      reset();
    }
  };

  const onInputChange = (event: ChangeEvent<HTMLInputElement>) => {
    const picked = event.target.files?.[0];
    if (picked) void acceptFile(picked);
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    if (phase === "uploading") return;
    const dropped = event.dataTransfer.files?.[0];
    if (dropped) void acceptFile(dropped);
  };

  const openPicker = () => inputRef.current?.click();

  const startUpload = async () => {
    if (!file) return;
    setErrorKey(null);
    setPhase("uploading");
    setProgress(0);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const job = await uploadVodJob(config, file, {
        signal: controller.signal,
        onProgress: setProgress,
      });
      onUploaded(job, file.name);
      reset();
    } catch (err) {
      if (err instanceof VodRequestError && err.code === "aborted") {
        setPhase("ready");
        return;
      }
      setErrorKey("upload");
      setPhase("ready");
    } finally {
      abortRef.current = null;
    }
  };

  const cancelUpload = () => abortRef.current?.abort();

  const percent = Math.round(progress * 100);

  return (
    <section
      aria-labelledby="video-upload-heading"
      className={cn(styles.card, styles.rise, "flex flex-col gap-5 rounded-card p-6 sm:p-8")}
    >
      <h2 id="video-upload-heading" className="text-xl font-semibold text-text-primary">
        {t.heading}
      </h2>

      <input
        ref={inputRef}
        type="file"
        accept={VOD_ACCEPT_ATTRIBUTE}
        className="sr-only"
        onChange={onInputChange}
        tabIndex={-1}
      />

      {phase === "idle" || phase === "checking" ? (
        <div
          role="button"
          tabIndex={0}
          aria-label={t.dropzone}
          aria-disabled={phase === "checking"}
          className={cn(
            styles.dropzone,
            dragging && styles.dropzoneActive,
            "flex min-h-44 flex-col items-center justify-center gap-3 rounded-card px-6 py-10 text-center",
          )}
          onClick={openPicker}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              openPicker();
            }
          }}
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
        >
          <p className="text-base font-medium text-text-primary">
            {dragging ? t.dropzoneActive : phase === "checking" ? t.checking : t.dropzone}
          </p>
          <p className="text-sm text-text-secondary">{interpolate(t.hint, hintValues)}</p>
          {phase !== "checking" ? (
            <span className="mt-1 inline-flex min-h-11 items-center justify-center rounded-pill bg-background-secondary px-5 text-sm font-semibold text-text-primary shadow-button ring-1 ring-surface-primary">
              {t.pick}
            </span>
          ) : null}
        </div>
      ) : (
        <div className="flex flex-col gap-4 rounded-card bg-background-primary p-5">
          <p className="truncate text-base font-medium text-text-primary" title={file?.name}>
            {interpolate(t.selected, { name: file?.name ?? "" })}
          </p>

          {phase === "uploading" ? (
            <div className="flex flex-col gap-2">
              <div
                role="progressbar"
                aria-valuenow={percent}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label={t.progressLabel}
                className={cn(styles.track, "h-2 w-full rounded-pill")}
              >
                <div className={cn(styles.fill, "h-full rounded-pill")} style={{ width: `${percent}%` }} />
              </div>
              <p aria-live="polite" className="text-sm text-text-secondary">
                {interpolate(t.uploading, { percent: String(percent) })}
              </p>
            </div>
          ) : null}

          <div className="flex flex-wrap gap-3">
            {phase === "uploading" ? (
              <button
                type="button"
                onClick={cancelUpload}
                className="inline-flex min-h-11 items-center justify-center rounded-pill px-5 text-sm font-semibold text-text-primary underline underline-offset-4"
              >
                {t.cancel}
              </button>
            ) : (
              <>
                <Button variant="secondary" showChevron={false} onClick={startUpload} className="text-base">
                  {t.start}
                </Button>
                <button
                  type="button"
                  onClick={reset}
                  className="inline-flex min-h-11 items-center justify-center rounded-pill px-5 text-sm font-semibold text-text-primary underline underline-offset-4"
                >
                  {t.change}
                </button>
              </>
            )}
          </div>
        </div>
      )}

      {errorKey ? (
        <p role="alert" className={cn(styles.toneDanger, "rounded-card px-4 py-3 text-sm")}>
          {errorText(errorKey)}
        </p>
      ) : null}
    </section>
  );
}
