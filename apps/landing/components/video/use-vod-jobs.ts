"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchVodJobs,
  getVodConfig,
  isActiveVodStatus,
  type VodClientConfig,
  type VodJob,
  VodRequestError,
} from "@/lib/vod-client";

// Poll the contract's recommended 5s cadence while any job is active; pause when the tab is
// hidden (saves requests) and stop entirely once nothing is active.
const POLL_INTERVAL_MS = 5000;

export type VodJobsState = {
  config: VodClientConfig;
  jobs: VodJob[];
  isLoading: boolean;
  error: "unauthenticated" | "load" | null;
  refresh: () => Promise<void>;
  removeJobLocally: (id: string) => void;
  upsertJob: (job: VodJob) => void;
};

export function useVodJobs(): VodJobsState {
  // getVodConfig reads build-time env only; resolve once via a lazy state initializer.
  const [config] = useState<VodClientConfig>(() => getVodConfig());

  const [jobs, setJobs] = useState<VodJob[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<"unauthenticated" | "load" | null>(null);
  const activeRef = useRef(true);

  const load = useCallback(async () => {
    try {
      const next = await fetchVodJobs(config);
      if (!activeRef.current) return;
      setJobs(next);
      setError(null);
    } catch (err) {
      if (!activeRef.current) return;
      if (err instanceof VodRequestError && err.code === "unauthenticated") {
        setError("unauthenticated");
      } else {
        setError("load");
      }
    } finally {
      if (activeRef.current) setIsLoading(false);
    }
  }, [config]);

  const refresh = useCallback(async () => {
    await load();
  }, [load]);

  const removeJobLocally = useCallback((id: string) => {
    setJobs((current) => current.filter((job) => job.id !== id));
  }, []);

  const upsertJob = useCallback((job: VodJob) => {
    setJobs((current) => {
      const exists = current.some((existing) => existing.id === job.id);
      return exists ? current.map((existing) => (existing.id === job.id ? job : existing)) : [job, ...current];
    });
  }, []);

  useEffect(() => {
    activeRef.current = true;
    // Kick the initial load off as a microtask so the effect body performs no synchronous setState.
    void Promise.resolve().then(load);
    return () => {
      activeRef.current = false;
    };
  }, [load]);

  // Polling loop driven by whether any job is active and whether the tab is visible.
  const hasActive = jobs.some((job) => isActiveVodStatus(job.status));
  useEffect(() => {
    if (!hasActive || error === "unauthenticated") return;
    let timer: ReturnType<typeof setInterval> | null = null;

    const start = () => {
      if (timer !== null) return;
      timer = setInterval(() => {
        if (typeof document !== "undefined" && document.hidden) return;
        void load();
      }, POLL_INTERVAL_MS);
    };
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };

    const onVisibility = () => {
      if (document.hidden) {
        stop();
      } else {
        void load();
        start();
      }
    };

    if (typeof document === "undefined" || !document.hidden) start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [hasActive, error, load]);

  return { config, jobs, isLoading, error, refresh, removeJobLocally, upsertJob };
}
