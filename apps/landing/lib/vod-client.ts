// Single typed client for the VOD API. Everything that depends on the server contract lives
// here: types, base-URL resolution, response parsing/validation, status mapping, and the
// browser-side request functions. When the contract changes, this is the only file to touch.
//
// FINALIZED CONTRACT (team-framework/innolive-server, branch feat/vod-jobs/#521,
// api/vod/openapi.yaml — "InnoLive VOD 작업 API" v0.1.0):
//   POST   /vod/jobs            (multipart, single "file" part) -> 201 VodJob (+ Location header)
//   GET    /vod/jobs            -> { jobs: VodJob[]; next_cursor: string | null }
//   GET    /vod/jobs/{job_id}   -> VodJob
//   GET    /vod/jobs/{job_id}/result -> binary MP4 (attachment; server-generated vod-{id}.mp4)
//   DELETE /vod/jobs/{job_id}   -> 204 (cancel + logical delete; the job then 404s)
// Notes that shaped this client:
//   - The path prefix is `/vod` (NOT `/api/v1/vod`); the server mounts resources at its origin.
//   - Wire fields are snake_case (job_id, queue_position, eta_sec, created_at, updated_at,
//     completed_at, original_delete_at, result_expires_at, failure). camelCase conversion is a
//     UI-layer concern, done here.
//   - progress is an integer 0..100 percentage, independent of upload transfer; normalized to 0..1.
//   - Wire may include optional `original_filename` (server #522). Download Content-Disposition
//     stays `vod-{id}.mp4`. `fileName` prefers the wire field, then local upload memory.
//   - The contract deliberately has NO `cancelled` status: DELETE cancels AND removes the job, so
//     a deleted/cancelled job vanishes from the list (404). We still accept a `cancelled` status
//     defensively so the UI degrades gracefully if the server ever emits one. See VOD_STATUSES.
import { isProductionBuild, isVodMockEnabled } from "./features.ts";

// The 7 statuses in the finalized contract, plus a defensively-tolerated `cancelled` that the
// contract does NOT emit (DELETE removes the job instead). Keeping it here means a stray
// `cancelled` from the server is rendered, not dropped during parsing.
export const VOD_STATUSES = [
  "uploaded",
  "queued",
  "processing",
  "paused",
  "done",
  "failed",
  "expired",
  "cancelled",
] as const;

export type VodStatus = (typeof VOD_STATUSES)[number];

// failure is present only in the `failed` state (null otherwise). Codes are stable; message is
// display-only. We keep code as a plain string since the server may add codes over time.
export type VodFailureCode = "processing_failed" | "storage_failed";
export type VodFailure = { code: string; message: string };

export type VodJob = {
  id: string; // wire: job_id
  status: VodStatus;
  progress: number; // normalized 0..1 (wire: integer 0..100)
  queuePosition: number | null;
  etaSec: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  completedAt: string | null;
  originalDeleteAt: string | null;
  resultExpiresAt: string | null;
  failure: VodFailure | null;
  // From wire `original_filename` when present; otherwise local upload memory / sessionStorage.
  fileName: string | null;
};

// ---------------------------------------------------------------------------
// Parsing / validation (pure)
// ---------------------------------------------------------------------------

export function normalizeVodStatus(value: unknown): VodStatus | null {
  if (typeof value !== "string") return null;
  // Tolerate the American spelling as an alias for our defensive `cancelled`.
  if (value === "canceled") return "cancelled";
  return (VOD_STATUSES as readonly string[]).includes(value) ? (value as VodStatus) : null;
}

function clamp01(value: number): number {
  if (value < 0) return 0;
  if (value > 1) return 1;
  return value;
}

// The contract sends progress as an integer 0..100 percentage. Normalize to a 0..1 fraction.
export function normalizeProgress(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return 0;
  return clamp01(value / 100);
}

function optionalNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function optionalString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function firstString(...values: unknown[]): string | null {
  for (const value of values) {
    const str = optionalString(value);
    if (str) return str;
  }
  return null;
}

function firstNumber(...values: unknown[]): number | null {
  for (const value of values) {
    const num = optionalNumber(value);
    if (num !== null) return num;
  }
  return null;
}

export function parseVodFailure(value: unknown): VodFailure | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  const code = optionalString(raw.code);
  const message = optionalString(raw.message);
  if (!code && !message) return null;
  return { code: code ?? "unknown", message: message ?? "" };
}

// Parse one job object. Returns null when required fields (job_id, status) are missing/invalid.
// Reads snake_case (the wire format) with a camelCase fallback for resilience.
export function parseVodJob(value: unknown): VodJob | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  const id = firstString(raw.job_id, raw.id);
  const status = normalizeVodStatus(raw.status);
  if (!id || !status) return null;
  return {
    id,
    status,
    progress: normalizeProgress(raw.progress),
    queuePosition: firstNumber(raw.queue_position, raw.queuePosition),
    etaSec: firstNumber(raw.eta_sec, raw.etaSec),
    createdAt: firstString(raw.created_at, raw.createdAt),
    updatedAt: firstString(raw.updated_at, raw.updatedAt),
    completedAt: firstString(raw.completed_at, raw.completedAt),
    originalDeleteAt: firstString(raw.original_delete_at, raw.originalDeleteAt),
    resultExpiresAt: firstString(raw.result_expires_at, raw.resultExpiresAt),
    failure: parseVodFailure(raw.failure),
    fileName: firstString(raw.original_filename, raw.originalFilename, raw.file_name, raw.fileName),
  };
}

// Accept either a bare array or a { jobs: [...] } envelope; drop entries that fail to parse.
// next_cursor pagination is defined by the contract but not consumed yet (the UI shows the
// first page; large histories would need cursor follow-up — noted in the README/summary).
export function parseVodJobList(value: unknown): VodJob[] {
  const list = Array.isArray(value)
    ? value
    : value && typeof value === "object" && Array.isArray((value as { jobs?: unknown }).jobs)
      ? (value as { jobs: unknown[] }).jobs
      : [];
  return list.map(parseVodJob).filter((job): job is VodJob => job !== null);
}

// ---------------------------------------------------------------------------
// Status mapping (pure)
// ---------------------------------------------------------------------------

// Active jobs are still progressing, so the UI keeps polling while any exist.
export function isActiveVodStatus(status: VodStatus): boolean {
  return status === "uploaded" || status === "queued" || status === "processing" || status === "paused";
}

export function canDownloadVodResult(status: VodStatus): boolean {
  return status === "done";
}

// Users can always remove a job. Per the contract, DELETE on an active job cancels it; on a
// finished/failed/expired job it clears it. Either way the job is gone afterwards.
export function canDeleteVodJob(status: VodStatus): boolean {
  void status;
  return true;
}

export type VodTone = "progress" | "paused" | "success" | "danger" | "neutral";

export function vodStatusTone(status: VodStatus): VodTone {
  switch (status) {
    case "uploaded":
    case "queued":
    case "processing":
      return "progress";
    case "paused":
      return "paused";
    case "done":
      return "success";
    case "failed":
    case "expired":
      return "danger";
    case "cancelled":
      return "neutral";
    default:
      return "neutral";
  }
}

// i18n key for a status lives under `video.status.<status>`; the status name is the key.
export function vodStatusLabelKey(status: VodStatus): VodStatus {
  return status;
}

// A determinate progress bar makes sense for queued/processing/paused/done; other states don't.
export function hasVodProgressBar(status: VodStatus): boolean {
  return status === "queued" || status === "processing" || status === "paused" || status === "done";
}

// ---------------------------------------------------------------------------
// Base URL resolution (pure) + config
// ---------------------------------------------------------------------------

// Mock uses a same-origin dev route; the real client targets the InnoLive server at `/vod`.
// Returns null when no usable real base URL exists, so the UI shows an unavailable state instead
// of guessing. In production the mock route is never returned, whatever `useMock` says, and the
// server URL must be an absolute http(s) URL (a relative value could point back at /api/dev-vod).
export function resolveVodBaseUrl(opts: {
  production: boolean;
  useMock: boolean;
  serverUrl: string | undefined;
}): string | null {
  if (opts.useMock && !opts.production) return "/api/dev-vod";
  const base = opts.serverUrl?.trim();
  if (!base) return null;
  let parsed: URL;
  try {
    parsed = new URL(base);
  } catch {
    return null;
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;
  return `${base.replace(/\/+$/, "")}/vod`;
}

export type VodClientConfig = { baseUrl: string; useMock: boolean };

// null means the VOD API is not configured for this build: the page renders an unavailable
// state. Production never mocks, so a production build without a server URL is unavailable.
export function getVodConfig(): VodClientConfig | null {
  const production = isProductionBuild();
  const useMock = !production && isVodMockEnabled();
  const baseUrl = resolveVodBaseUrl({
    production,
    useMock,
    serverUrl: process.env.NEXT_PUBLIC_INNOLIVE_SERVER_URL,
  });
  return baseUrl ? { baseUrl, useMock } : null;
}

// Server-generated download name (matches the contract's Content-Disposition fallback).
export function vodResultFileName(job: Pick<VodJob, "id" | "fileName">): string {
  return job.fileName ?? `vod-${job.id}.mp4`;
}

// ---------------------------------------------------------------------------
// Browser request helpers
// ---------------------------------------------------------------------------

export class VodRequestError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(code: string, message: string, status = 0) {
    super(message);
    this.name = "VodRequestError";
    this.code = code;
    this.status = status;
  }
}

// The mock relies on the landing session cookie (credentials: include). The real API expects a
// bearer token obtained from the established /api/auth/access-token route.
async function authHeaders(useMock: boolean): Promise<Record<string, string>> {
  if (useMock) return {};
  let response: Response;
  try {
    response = await fetch("/api/auth/access-token", {
      method: "POST",
      cache: "no-store",
      credentials: "include",
    });
  } catch {
    throw new VodRequestError("network_error", "서버에 연결하지 못했습니다.");
  }
  if (!response.ok) throw new VodRequestError("unauthenticated", "로그인이 필요합니다.", response.status);
  const body = (await response.json().catch(() => null)) as { access_token?: unknown } | null;
  const token = typeof body?.access_token === "string" ? body.access_token : "";
  if (!token) throw new VodRequestError("unauthenticated", "로그인이 필요합니다.", 401);
  return { Authorization: `Bearer ${token}` };
}

// Pull the stable error code out of the contract's { error: { code, message } } envelope so
// callers can branch on code rather than status text. Non-JSON proxy errors are tolerated.
async function readErrorCode(response: Response): Promise<string | null> {
  const body = (await response.json().catch(() => null)) as { error?: { code?: unknown } } | null;
  return typeof body?.error?.code === "string" ? body.error.code : null;
}

async function vodFetch(config: VodClientConfig, path: string, init: RequestInit = {}): Promise<Response> {
  const headers = await authHeaders(config.useMock);
  let response: Response;
  try {
    response = await fetch(`${config.baseUrl}${path}`, {
      ...init,
      cache: "no-store",
      credentials: config.useMock ? "include" : "omit",
      headers: { ...headers, ...(init.headers as Record<string, string> | undefined) },
    });
  } catch {
    throw new VodRequestError("network_error", "서버에 연결하지 못했습니다.");
  }
  if (!response.ok) {
    if (response.status === 401) throw new VodRequestError("unauthenticated", "로그인이 필요합니다.", 401);
    const code = await readErrorCode(response);
    throw new VodRequestError(code ?? "request_failed", `VOD request failed (${response.status})`, response.status);
  }
  return response;
}

export async function fetchVodJobs(config: VodClientConfig): Promise<VodJob[]> {
  const response = await vodFetch(config, "/jobs");
  return parseVodJobList(await response.json().catch(() => null));
}

// The contract answers 404 both for a deleted job and for another user's job (no existence
// leak), so the client treats 404 as "this job is gone" rather than as a failure.
export function isVodJobGone(err: unknown): boolean {
  return err instanceof VodRequestError && err.status === 404;
}

export async function fetchVodJob(config: VodClientConfig, id: string): Promise<VodJob | null> {
  try {
    const response = await vodFetch(config, `/jobs/${encodeURIComponent(id)}`);
    return parseVodJob(await response.json().catch(() => null));
  } catch (err) {
    if (isVodJobGone(err)) return null;
    throw err;
  }
}

export async function deleteVodJob(config: VodClientConfig, id: string): Promise<void> {
  try {
    await vodFetch(config, `/jobs/${encodeURIComponent(id)}`, { method: "DELETE" });
  } catch (err) {
    // Already gone (deleted elsewhere or not ours): the desired end state holds.
    if (isVodJobGone(err)) return;
    throw err;
  }
}

// Upload via XMLHttpRequest so we can report determinate upload progress (0..1). The response is
// the created VodJob (201). Designed so a chunked/resumable transport can be added later behind
// this same signature. Returns the parsed job; callers remember the file name for display.
export function uploadVodJob(
  config: VodClientConfig,
  file: File,
  options: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<VodJob> {
  return authHeaders(config.useMock).then(
    (headers) =>
      new Promise<VodJob>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open("POST", `${config.baseUrl}/jobs`);
        xhr.responseType = "json";
        xhr.withCredentials = config.useMock;
        for (const [key, value] of Object.entries(headers)) xhr.setRequestHeader(key, value);

        if (options.signal) {
          if (options.signal.aborted) {
            reject(new VodRequestError("aborted", "업로드가 취소되었습니다."));
            return;
          }
          options.signal.addEventListener("abort", () => xhr.abort(), { once: true });
        }

        xhr.upload.onprogress = (event) => {
          if (event.lengthComputable && options.onProgress) {
            options.onProgress(event.total > 0 ? event.loaded / event.total : 0);
          }
        };
        xhr.onerror = () => reject(new VodRequestError("network_error", "업로드에 실패했습니다."));
        xhr.onabort = () => reject(new VodRequestError("aborted", "업로드가 취소되었습니다."));
        xhr.onload = () => {
          if (xhr.status === 401) {
            reject(new VodRequestError("unauthenticated", "로그인이 필요합니다.", 401));
            return;
          }
          if (xhr.status < 200 || xhr.status >= 300) {
            const code =
              xhr.response && typeof xhr.response === "object"
                ? ((xhr.response as { error?: { code?: unknown } }).error?.code as string | undefined)
                : undefined;
            reject(new VodRequestError(code ?? "request_failed", `Upload failed (${xhr.status})`, xhr.status));
            return;
          }
          // The contract returns the created job; the draft returned only { id }. Accept both:
          // parse a full job, else synthesize a minimal one from an { id }/{ job_id } body.
          const body = xhr.response as Record<string, unknown> | null;
          const job = parseVodJob(body);
          if (job) {
            options.onProgress?.(1);
            resolve(job);
            return;
          }
          const id = firstString(body?.job_id, body?.id);
          if (!id) {
            reject(new VodRequestError("invalid_response", "업로드 응답을 확인하지 못했습니다.", xhr.status));
            return;
          }
          options.onProgress?.(1);
          resolve({
            id,
            status: "uploaded",
            progress: 0,
            queuePosition: null,
            etaSec: null,
            createdAt: null,
            updatedAt: null,
            completedAt: null,
            originalDeleteAt: null,
            resultExpiresAt: null,
            failure: null,
            fileName: null,
          });
        };

        const form = new FormData();
        // Content-Type is left unset so the browser adds the multipart boundary (contract).
        form.append("file", file, file.name);
        xhr.send(form);
      }),
  );
}

// Download the processed result. Fetches with auth so the real (cross-origin, bearer) API works,
// then triggers a browser download. For the mock this is a same-origin cookie-authed request.
export async function downloadVodResult(config: VodClientConfig, job: VodJob): Promise<void> {
  const response = await vodFetch(config, `/jobs/${encodeURIComponent(job.id)}/result`);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = vodResultFileName(job);
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
