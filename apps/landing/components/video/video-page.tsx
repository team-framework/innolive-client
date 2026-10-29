"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useLocale } from "@/components/locale-provider";
import { cn } from "@/lib/cn";
import { interpolate } from "@/lib/locales";
import { vodResultFileName, type VodJob } from "@/lib/vod-client";
import { VOD_ORIGINAL_DELETE_HOURS, VOD_RESULT_RETENTION_DAYS } from "@/lib/vod-limits";
import styles from "./video.module.css";
import { useVodJobs } from "./use-vod-jobs";
import { VideoUploader } from "./video-uploader";
import { VideoJobCard } from "./video-job-card";
import { forgetVodName, readVodNames, rememberVodName } from "./vod-names";

export function VideoPage() {
  const { messages, href } = useLocale();
  const router = useRouter();
  const t = messages.video;
  const { config, jobs, isLoading, error, refresh, removeJobLocally, upsertJob } = useVodJobs();
  // Lazy initializer reads sessionStorage only on the client (returns {} during SSR). The map is
  // not used until job cards render, so there is no hydration mismatch.
  const [names, setNames] = useState<Record<string, string>>(() => readVodNames());
  const redirectedRef = useRef(false);

  // A session that expires mid-use falls back to the login redirect (server guard covers entry).
  useEffect(() => {
    if (error === "unauthenticated" && !redirectedRef.current) {
      redirectedRef.current = true;
      router.replace(href("/login"));
    }
  }, [error, href, router]);

  const handleUploaded = (job: VodJob, fileName: string) => {
    setNames(rememberVodName(job.id, fileName));
    upsertJob(job);
  };

  const handleDeleted = (id: string) => {
    setNames(forgetVodName(id));
    removeJobLocally(id);
  };

  const showEmpty = !isLoading && jobs.length === 0 && error !== "unauthenticated";

  // VOD API not configured for this build (e.g. a production image without a server URL).
  if (config === null) {
    return (
      <main id="main" data-page="video" className={cn(styles.page, "relative overflow-hidden")}>
        <div aria-hidden="true" className={cn(styles.backdrop, "pointer-events-none absolute inset-x-0 top-0 h-[40rem]")} />
        <div className="relative mx-auto flex w-full max-w-[52rem] flex-col gap-8 px-[var(--page-gutter)] pb-20">
          <header className={cn(styles.rise, "flex flex-col gap-3")}>
            <h1 className="text-heading font-semibold text-text-primary">{t.title}</h1>
            <p className="text-body-lg text-text-secondary">{t.subtitle}</p>
          </header>
          <section
            role="status"
            data-video-state="unavailable"
            className={cn(styles.card, styles.rise, "flex flex-col items-center gap-2 rounded-card px-6 py-12 text-center")}
          >
            <p className="text-base font-medium text-text-primary">{t.unavailable.heading}</p>
            <p className="text-sm text-text-secondary">{t.unavailable.body}</p>
          </section>
        </div>
      </main>
    );
  }

  return (
    <main id="main" data-page="video" className={cn(styles.page, "relative overflow-hidden")}>
      <div aria-hidden="true" className={cn(styles.backdrop, "pointer-events-none absolute inset-x-0 top-0 h-[40rem]")} />
      <div className="relative mx-auto flex w-full max-w-[52rem] flex-col gap-8 px-[var(--page-gutter)] pb-20">
        <header className={cn(styles.rise, "flex flex-col gap-3")}>
          <h1 className="text-heading font-semibold text-text-primary">{t.title}</h1>
          <p className="text-body-lg text-text-secondary">{t.subtitle}</p>
        </header>

        <VideoUploader config={config} onUploaded={handleUploaded} />

        <section
          aria-labelledby="video-notice-heading"
          className={cn(styles.card, styles.rise, "flex flex-col gap-2 rounded-card p-6")}
        >
          <h2 id="video-notice-heading" className="text-base font-semibold text-text-primary">
            {t.notice.heading}
          </h2>
          <p className="text-sm text-text-secondary">
            {interpolate(t.notice.retention, { hours: String(VOD_ORIGINAL_DELETE_HOURS) })}
          </p>
          <p className="text-sm text-text-secondary">
            {interpolate(t.notice.result, { days: String(VOD_RESULT_RETENTION_DAYS) })}
          </p>
        </section>

        <section aria-labelledby="video-jobs-heading" className={cn(styles.rise, "flex flex-col gap-4")}>
          <div className="flex items-center justify-between gap-3">
            <h2 id="video-jobs-heading" className="text-xl font-semibold text-text-primary">
              {t.jobs.heading}
            </h2>
            <button
              type="button"
              onClick={() => void refresh()}
              className="inline-flex min-h-11 items-center justify-center rounded-pill px-4 text-sm font-semibold text-text-primary underline underline-offset-4"
            >
              {t.jobs.refresh}
            </button>
          </div>

          {showEmpty ? (
            <div className={cn(styles.card, "flex flex-col items-center gap-2 rounded-card px-6 py-12 text-center")}>
              <p className="text-base font-medium text-text-primary">{t.jobs.empty}</p>
              <p className="text-sm text-text-secondary">{t.jobs.emptyHint}</p>
            </div>
          ) : (
            <ul className="flex flex-col gap-4">
              {jobs.map((job) => (
                <VideoJobCard
                  key={job.id}
                  job={job}
                  displayName={names[job.id] ?? vodResultFileName(job)}
                  config={config}
                  onDeleted={handleDeleted}
                />
              ))}
            </ul>
          )}
        </section>
      </div>
    </main>
  );
}
