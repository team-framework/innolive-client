// Dev-only in-memory store + guards for the VOD mock. Server module (uses next/headers).
// Every route handler bails via `mockDisabled()` before touching the store, and a production
// build (NODE_ENV === "production") always 404s regardless of NEXT_PUBLIC_VOD_MOCK.
import { cookies } from "next/headers";
import { ACCESS_TOKEN_COOKIE } from "@/lib/auth-server";
import { isVodMockEnabled } from "@/lib/features";
import { VOD_ORIGINAL_DELETE_HOURS, VOD_RESULT_RETENTION_DAYS } from "@/lib/vod-limits";
import { computeMockState, VOD_MOCK_TOTAL_SEC } from "@/lib/vod-mock-timeline";

type MockRecord = {
  id: string;
  fileName: string;
  createdAtMs: number;
  contentType: string;
  bytes: Uint8Array;
};

// Survive HMR in dev by stashing the store on globalThis.
const globalStore = globalThis as unknown as { __vodMockStore?: Map<string, MockRecord> };
const store: Map<string, MockRecord> = (globalStore.__vodMockStore ??= new Map());

// Cap stored bytes so a large upload can't exhaust dev memory; the result download is capped too.
const MAX_STORED_BYTES = 32 * 1024 * 1024;

export function mockDisabled(): boolean {
  // Hard production guard first, independent of the flag helpers.
  if (process.env.NODE_ENV === "production") return true;
  return !isVodMockEnabled();
}

// The mock accepts any request that carries a landing session cookie; it never validates the
// token, so the local test fixture (an unsigned dummy JWT) is accepted.
export async function hasLandingSession(): Promise<boolean> {
  const value = (await cookies()).get(ACCESS_TOKEN_COOKIE)?.value;
  return typeof value === "string" && value.length > 0;
}

export function notFoundResponse(): Response {
  return new Response("Not Found", { status: 404, headers: { "Cache-Control": "no-store" } });
}

export function unauthorizedResponse(): Response {
  return new Response(JSON.stringify({ error: "unauthenticated" }), {
    status: 401,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function parseSpeed(url: string): number {
  const raw = new URL(url).searchParams.get("speed");
  const speed = raw ? Number(raw) : 1;
  return Number.isFinite(speed) && speed > 0 ? Math.min(speed, 60) : 1;
}

// The mock emits the finalized contract's wire shape: snake_case keys, integer 0..100 progress,
// a failure object (always null here — the mock never fails), and completed_at. It deliberately
// omits the original filename, matching the server (the client remembers names locally).
export type MockJobView = {
  job_id: string;
  status: string;
  progress: number;
  queue_position: number | null;
  eta_sec: number | null;
  created_at: string;
  updated_at: string;
  completed_at: string | null;
  original_delete_at: string | null;
  result_expires_at: string | null;
  failure: { code: string; message: string } | null;
};

function viewOf(record: MockRecord, speed: number): MockJobView {
  const now = Date.now();
  const elapsedSec = (now - record.createdAtMs) / 1000;
  const state = computeMockState(elapsedSec, speed);
  let completedAt: string | null = null;
  let originalDeleteAt: string | null = null;
  let resultExpiresAt: string | null = null;
  if (state.status === "done") {
    const doneAtMs = record.createdAtMs + (VOD_MOCK_TOTAL_SEC / speed) * 1000;
    completedAt = new Date(doneAtMs).toISOString();
    originalDeleteAt = new Date(doneAtMs + VOD_ORIGINAL_DELETE_HOURS * 3600 * 1000).toISOString();
    resultExpiresAt = new Date(doneAtMs + VOD_RESULT_RETENTION_DAYS * 24 * 3600 * 1000).toISOString();
  }
  return {
    job_id: record.id,
    status: state.status,
    progress: Math.round(state.progress * 100),
    queue_position: state.queuePosition,
    eta_sec: state.etaSec,
    created_at: new Date(record.createdAtMs).toISOString(),
    updated_at: new Date(now).toISOString(),
    completed_at: completedAt,
    original_delete_at: originalDeleteAt,
    result_expires_at: resultExpiresAt,
    failure: null,
  };
}

// Returns the created job in the contract's wire shape (201 VodJob), freshly uploaded.
export async function createMockJob(file: File): Promise<MockJobView> {
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer.byteLength > MAX_STORED_BYTES ? MAX_STORED_BYTES : buffer.byteLength);
  bytes.set(new Uint8Array(buffer, 0, bytes.byteLength));
  const id = crypto.randomUUID();
  const record: MockRecord = {
    id,
    fileName: file.name || `${id}.mp4`,
    createdAtMs: Date.now(),
    contentType: file.type || "application/octet-stream",
    bytes,
  };
  store.set(id, record);
  return viewOf(record, 1);
}

export function listMockJobs(requestUrl: string): MockJobView[] {
  const speed = parseSpeed(requestUrl);
  return [...store.values()]
    .sort((a, b) => b.createdAtMs - a.createdAtMs)
    .map((record) => viewOf(record, speed));
}

export function getMockJob(requestUrl: string, id: string): MockJobView | null {
  const record = store.get(id);
  if (!record) return null;
  return viewOf(record, parseSpeed(requestUrl));
}

export function deleteMockJob(id: string): boolean {
  return store.delete(id);
}

// Returns the stored bytes when the job is done, otherwise null (result not ready / unknown).
export function getMockResult(requestUrl: string, id: string): { record: MockRecord } | null {
  const record = store.get(id);
  if (!record) return null;
  const state = computeMockState((Date.now() - record.createdAtMs) / 1000, parseSpeed(requestUrl));
  if (state.status !== "done") return null;
  return { record };
}
