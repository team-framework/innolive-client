"use client";

import { useState } from "react";
import { useLocale } from "@/components/locale-provider";
import { cn } from "@/lib/cn";
import { interpolate } from "@/lib/locales";
import {
  canDeleteVodJob,
  canDownloadVodResult,
  deleteVodJob,
  downloadVodResult,
  isVodJobGone,
  hasVodProgressBar,
  isActiveVodStatus,
  vodStatusTone,
  type VodClientConfig,
  type VodJob,
  type VodTone,
} from "@/lib/vod-client";
import { formatDateTimeLabel, formatDurationLabel, formatProgressPercent } from "@/lib/vod-format";
import styles from "./video.module.css";

const toneClass: Record<VodTone, string> = {
  progress: styles.toneProgress,
  paused: styles.tonePaused,
  success: styles.toneSuccess,
  danger: styles.toneDanger,
  neutral: styles.toneNeutral,
};

export function VideoJobCard({
  job,
  displayName,
  config,
  onDeleted,
}: {
  job: VodJob;
  displayName: string;
  config: VodClientConfig;
  onDeleted: (id: string) => void;
}) {
  const { messages, locale } = useLocale();
  const t = messages.video.jobs;
  const notice = messages.video.notice;
  const [downloading, setDownloading] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const tone = vodStatusTone(job.status);
  const percent = formatProgressPercent(job.progress);
  const showProgress = hasVodProgressBar(job.status);
  const active = isActiveVodStatus(job.status);

  const created = formatDateTimeLabel(job.createdAt, locale);
  const originalDeleteAt = formatDateTimeLabel(job.originalDeleteAt, locale);
  const resultExpiresAt = formatDateTimeLabel(job.resultExpiresAt, locale);
  const etaLabel =
    active && job.etaSec !== null ? formatDurationLabel(job.etaSec, messages.video.time) : null;

  const handleDownload = async () => {
    setActionError(null);
    setDownloading(true);
    try {
      await downloadVodResult(config, job);
    } catch (err) {
      // 404 means the job is gone (deleted or not ours): drop it from the list. Any other
      // failure just re-enables the button for a retry.
      if (isVodJobGone(err)) onDeleted(job.id);
    } finally {
      setDownloading(false);
    }
  };

  const handleDelete = async () => {
    setActionError(null);
    setDeleting(true);
    try {
      await deleteVodJob(config, job.id);
      onDeleted(job.id);
    } catch {
      setActionError(t.deleteError);
      setDeleting(false);
    }
  };

  return (
    <li className={cn(styles.card, "flex flex-col gap-4 rounded-card p-5 sm:p-6")}>
      <div className="flex items-start justify-between gap-3">
        <p className="min-w-0 flex-1 truncate text-base font-medium text-text-primary" title={displayName}>
          {displayName}
        </p>
        <span
          aria-live="polite"
          className={cn(
            toneClass[tone],
            styles.tone,
            "inline-flex items-center gap-1.5 rounded-pill px-3 py-1 text-sm font-semibold",
          )}
        >
          <span className={styles.dot} aria-hidden="true" />
          {messages.video.status[job.status]}
        </span>
      </div>

      {showProgress ? (
        <div className="flex flex-col gap-1.5">
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
          <span className="text-sm text-text-secondary">{percent}%</span>
        </div>
      ) : null}

      <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-text-secondary">
        {job.status === "queued" && job.queuePosition !== null ? (
          <span>{interpolate(t.queuePosition, { position: String(job.queuePosition) })}</span>
        ) : null}
        {etaLabel ? <span>{interpolate(t.eta, { time: etaLabel })}</span> : null}
        {created ? <span>{interpolate(t.created, { time: created })}</span> : null}
      </div>

      {job.status === "failed" && job.failure?.message ? (
        <p className={cn(styles.toneDanger, "rounded-card px-4 py-2 text-sm")}>{job.failure.message}</p>
      ) : null}

      {job.status === "done" && (originalDeleteAt || resultExpiresAt) ? (
        <div className="flex flex-col gap-0.5 text-sm text-text-secondary">
          {originalDeleteAt ? <span>{interpolate(notice.originalDeleteAt, { time: originalDeleteAt })}</span> : null}
          {resultExpiresAt ? <span>{interpolate(notice.resultExpiresAt, { time: resultExpiresAt })}</span> : null}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        {canDownloadVodResult(job.status) ? (
          <button
            type="button"
            onClick={handleDownload}
            disabled={downloading}
            className="inline-flex min-h-11 items-center justify-center rounded-pill bg-button-secondary px-5 text-sm font-semibold text-text-reversed shadow-button transition-colors hover:bg-button-secondary-hover disabled:opacity-60"
          >
            {downloading ? t.downloading : t.download}
          </button>
        ) : null}
        {canDeleteVodJob(job.status) ? (
          <button
            type="button"
            onClick={handleDelete}
            disabled={deleting}
            className="inline-flex min-h-11 items-center justify-center rounded-pill px-4 text-sm font-semibold text-text-primary underline underline-offset-4 disabled:opacity-60"
          >
            {deleting ? t.deleting : t.delete}
          </button>
        ) : null}
      </div>

      {actionError ? (
        <p role="alert" className="text-sm text-text-primary">
          {actionError}
        </p>
      ) : null}
    </li>
  );
}
