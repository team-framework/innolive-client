export const qualityStages = ["session", "queue", "camera", "signaling", "transport", "first_frame", "streaming"] as const;
export type QualityStage = (typeof qualityStages)[number];
export const qualityEvents = ["started", "transport_connected", "first_frame", "failed", "ended", "cancelled"] as const;
export const failureCodes = ["permission_denied", "camera_missing", "timeout", "request_failed", "connection_failed"] as const;

export type QualityEvent = {
  version: 1;
  attemptId: string;
  event: (typeof qualityEvents)[number];
  role: "member" | "guest";
  locale: "ko" | "en" | "ja";
  retry: boolean;
  stage: QualityStage;
  elapsedMs: number;
  code?: (typeof failureCodes)[number];
};

// Copy only allowlisted scalar fields. Never forward errors or signaling payloads.
export function parseQualityEvent(value: unknown): QualityEvent | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const v = value as Record<string, unknown>;
  const keys = ["version", "attemptId", "event", "role", "locale", "retry", "stage", "elapsedMs", "code"];
  if (Object.keys(v).some((key) => !keys.includes(key))) return null;
  if (v.version !== 1 || typeof v.attemptId !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v.attemptId)) return null;
  if (!qualityEvents.includes(v.event as QualityEvent["event"]) || !qualityStages.includes(v.stage as QualityStage)) return null;
  if (!["member", "guest"].includes(v.role as string) || !["ko", "en", "ja"].includes(v.locale as string) || typeof v.retry !== "boolean") return null;
  if (typeof v.elapsedMs !== "number" || !Number.isSafeInteger(v.elapsedMs) || v.elapsedMs < 0 || v.elapsedMs > 86_400_000) return null;
  if (v.event === "failed" ? !failureCodes.includes(v.code as NonNullable<QualityEvent["code"]>) : v.code !== undefined) return null;
  return {
    version: 1, attemptId: v.attemptId, event: v.event as QualityEvent["event"],
    role: v.role as QualityEvent["role"], locale: v.locale as QualityEvent["locale"],
    retry: v.retry, stage: v.stage as QualityStage, elapsedMs: v.elapsedMs,
    ...(v.event === "failed" ? { code: v.code as QualityEvent["code"] } : {}),
  };
}

export function sendQualityEvent(event: QualityEvent) {
  try {
    const body = JSON.stringify(event);
    if (navigator.sendBeacon?.("/api/experience-quality", new Blob([body], { type: "application/json" }))) return;
    void fetch("/api/experience-quality", {
      method: "POST", body, headers: { "Content-Type": "application/json" },
      credentials: "omit", keepalive: true,
    }).catch(() => undefined);
  } catch {
    // Diagnostics must not interrupt the camera or connection lifecycle.
  }
}

export function createQualityAttempt(
  context: Pick<QualityEvent, "attemptId" | "role" | "locale" | "retry">,
  emit: (event: QualityEvent) => void,
  now: () => number = () => performance.now(),
) {
  const started = now();
  let stage: QualityStage = "session";
  let terminal = false;
  const emitted = new Set<QualityEvent["event"]>();
  const record = (event: QualityEvent["event"], code?: QualityEvent["code"]) => {
    if (terminal || emitted.has(event)) return;
    emitted.add(event);
    if (["failed", "ended", "cancelled"].includes(event)) terminal = true;
    try {
      emit({ version: 1, ...context, event, stage, elapsedMs: Math.min(86_400_000, Math.max(0, Math.round(now() - started))), ...(code ? { code } : {}) });
    } catch {
      // A failing diagnostics adapter must not fail an experience attempt.
    }
  };
  record("started");
  return {
    stage(next: QualityStage) { if (!terminal) stage = next; },
    connected() { record("transport_connected"); },
    firstFrame() { record("first_frame"); stage = "streaming"; },
    fail(error: unknown) {
      const name = (error as { name?: unknown } | null)?.name;
      const code = name === "NotAllowedError" ? "permission_denied"
        : name === "NotFoundError" ? "camera_missing"
        : name === "TimeoutError" ? "timeout"
        : ["session", "queue"].includes(stage) ? "request_failed" : "connection_failed";
      record("failed", code);
    },
    finish(cancelled: boolean) { record(cancelled ? "cancelled" : "ended"); },
  };
}

// requestVideoFrameCallback confirms a frame submitted for composition. Older
// browsers use playing + decoded dimensions, which is a weaker fallback.
export function observeFirstFrame(video: HTMLVideoElement, onFrame: () => void) {
  let active = true;
  let frameId: number | undefined;
  const finish = () => {
    if (!active) return;
    active = false;
    video.removeEventListener("playing", fallback);
    video.removeEventListener("loadeddata", fallback);
    onFrame();
  };
  const fallback = () => {
    if (!video.paused && video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) finish();
  };
  if (typeof video.requestVideoFrameCallback === "function") {
    frameId = video.requestVideoFrameCallback(finish);
  } else {
    video.addEventListener("playing", fallback);
    video.addEventListener("loadeddata", fallback);
    fallback();
  }
  return () => {
    active = false;
    if (frameId !== undefined) video.cancelVideoFrameCallback(frameId);
    video.removeEventListener("playing", fallback);
    video.removeEventListener("loadeddata", fallback);
  };
}
