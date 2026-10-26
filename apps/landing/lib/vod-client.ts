// Single typed client for the VOD API. Everything that depends on the (still-draft) server
// contract lives here: types, base-URL resolution, response parsing/validation, status mapping,
// and the browser-side request functions. When the server team finalizes the contract, this is
// the only file that should need to change.
//
// API CONTRACT DRAFT (final contract pending from the server team):
//   POST   /api/v1/vod/jobs            (multipart, field "file") -> { id }
//   GET    /api/v1/vod/jobs            -> Job[]  (or { jobs: Job[] })
//   GET    /api/v1/vod/jobs/{id}       -> Job
//   GET    /api/v1/vod/jobs/{id}/result-> binary download
//   DELETE /api/v1/vod/jobs/{id}       -> 204
// Job shape (assumptions marked):
//   status: one of VOD_STATUSES
//   progress: 0..1 OR 0..100 — normalized here to a 0..1 fraction (values > 1 treated as %)
//   queuePosition, etaSec: numbers, may be absent
//   originalDeleteAt, resultExpiresAt: ISO strings, present once processing completes
//   fileName, createdAt: ASSUMED fields (not in the draft spec) used for display
import { isVodMockEnabled } from "./features.ts";

export const VOD_STATUSES = [
  "uploaded",
  "queued",
  "processing",
  "paused",
  "done",
  "failed",
  "expired",
] as const;

export type VodStatus = (typeof VOD_STATUSES)[number];

export type VodJob = {
  id: string;
  fileName: string;
  status: VodStatus;
  progress: number; // normalized 0..1
  queuePosition: number | null;
  etaSec: number | null;
  originalDeleteAt: string | null;
  resultExpiresAt: string | null;
  createdAt: string | null;
};

// ---------------------------------------------------------------------------
// Parsing / validation (pure)
// ---------------------------------------------------------------------------

export function normalizeVodStatus(value: unknown): VodStatus | null {
  if (typeof value !== "string") return null;
  return (VOD_STATUSES as readonly string[]).includes(value) ? (value as VodStatus) : null;
}

// The contract may send progress as a 0..1 fraction or a 0..100 percentage. Normalize to 0..1.
export function normalizeProgress(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return 0;
  const fraction = value > 1 ? value / 100 : value;
  if (fraction < 0) return 0;
  if (fraction > 1) return 1;
  return fraction;
}

function optionalNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function optionalString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

// Parse one job object. Returns null when required fields (id, status) are missing/invalid.
export function parseVodJob(value: unknown): VodJob | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  const id = optionalString(raw.id);
  const status = normalizeVodStatus(raw.status);
  if (!id || !status) return null;
  return {
    id,
    fileName: optionalString(raw.fileName) ?? optionalString(raw.filename) ?? id,
    status,
    progress: normalizeProgress(raw.progress),
    queuePosition: optionalNumber(raw.queuePosition),
    etaSec: optionalNumber(raw.etaSec),
    originalDeleteAt: optionalString(raw.originalDeleteAt),
    resultExpiresAt: optionalString(raw.resultExpiresAt),
    createdAt: optionalString(raw.createdAt),
  };
}

// Accept either a bare array or a { jobs: [...] } envelope; drop entries that fail to parse.
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

// Users can always remove a job (cancel in-flight work or clear a finished/failed one).
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

// Mock uses a same-origin dev route; the real client targets the InnoLive server.
export function resolveVodBaseUrl(opts: { useMock: boolean; serverUrl: string | undefined }): string {
  if (opts.useMock) return "/api/dev-vod";
  const base = opts.serverUrl?.trim();
  if (!base) throw new VodRequestError("configuration_error", "NEXT_PUBLIC_INNOLIVE_SERVER_URL is not configured");
  return `${base.replace(/\/$/, "")}/api/v1/vod`;
}

export type VodClientConfig = { baseUrl: string; useMock: boolean };

export function getVodConfig(): VodClientConfig {
  const useMock = isVodMockEnabled();
  return { baseUrl: resolveVodBaseUrl({ useMock, serverUrl: process.env.NEXT_PUBLIC_INNOLIVE_SERVER_URL }), useMock };
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
    throw new VodRequestError("request_failed", `VOD request failed (${response.status})`, response.status);
  }
  return response;
}

export async function fetchVodJobs(config: VodClientConfig): Promise<VodJob[]> {
  const response = await vodFetch(config, "/jobs");
  return parseVodJobList(await response.json().catch(() => null));
}

export async function fetchVodJob(config: VodClientConfig, id: string): Promise<VodJob | null> {
  const response = await vodFetch(config, `/jobs/${encodeURIComponent(id)}`);
  return parseVodJob(await response.json().catch(() => null));
}

export async function deleteVodJob(config: VodClientConfig, id: string): Promise<void> {
  await vodFetch(config, `/jobs/${encodeURIComponent(id)}`, { method: "DELETE" });
}

// Upload via XMLHttpRequest so we can report determinate upload progress (0..1). Designed so a
// chunked/resumable transport can be added later behind this same function signature.
export function uploadVodJob(
  config: VodClientConfig,
  file: File,
  options: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<{ id: string }> {
  return authHeaders(config.useMock).then(
    (headers) =>
      new Promise<{ id: string }>((resolve, reject) => {
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
          if (xhr.status < 200 || xhr.status >= 300) {
            reject(new VodRequestError("request_failed", `Upload failed (${xhr.status})`, xhr.status));
            return;
          }
          const body = xhr.response as { id?: unknown } | null;
          const id = typeof body?.id === "string" ? body.id : "";
          if (!id) {
            reject(new VodRequestError("invalid_response", "업로드 응답을 확인하지 못했습니다.", xhr.status));
            return;
          }
          options.onProgress?.(1);
          resolve({ id });
        };

        const form = new FormData();
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
  anchor.download = job.fileName || `${job.id}.mp4`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
