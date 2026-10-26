// Dev-only in-memory store + guards for the VOD mock. Server module (uses next/headers).
// Never imported into the production bundle path: every route handler bails via `mockDisabled()`
// before touching this, and NODE_ENV === "production" forces the mock off regardless of flags.
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

export type MockJobView = {
  id: string;
  fileName: string;
  status: string;
  progress: number;
  queuePosition: number | null;
  etaSec: number | null;
  originalDeleteAt: string | null;
  resultExpiresAt: string | null;
  createdAt: string;
};

function viewOf(record: MockRecord, speed: number): MockJobView {
  const elapsedSec = (Date.now() - record.createdAtMs) / 1000;
  const state = computeMockState(elapsedSec, speed);
  let originalDeleteAt: string | null = null;
  let resultExpiresAt: string | null = null;
  if (state.status === "done") {
    const doneAtMs = record.createdAtMs + (VOD_MOCK_TOTAL_SEC / speed) * 1000;
    originalDeleteAt = new Date(doneAtMs + VOD_ORIGINAL_DELETE_HOURS * 3600 * 1000).toISOString();
    resultExpiresAt = new Date(doneAtMs + VOD_RESULT_RETENTION_DAYS * 24 * 3600 * 1000).toISOString();
  }
  return {
    id: record.id,
    fileName: record.fileName,
    status: state.status,
    progress: state.progress,
    queuePosition: state.queuePosition,
    etaSec: state.etaSec,
    originalDeleteAt,
    resultExpiresAt,
    createdAt: new Date(record.createdAtMs).toISOString(),
  };
}

export async function createMockJob(file: File): Promise<{ id: string }> {
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer.byteLength > MAX_STORED_BYTES ? MAX_STORED_BYTES : buffer.byteLength);
  bytes.set(new Uint8Array(buffer, 0, bytes.byteLength));
  const id = crypto.randomUUID();
  store.set(id, {
    id,
    fileName: file.name || `${id}.mp4`,
    createdAtMs: Date.now(),
    contentType: file.type || "application/octet-stream",
    bytes,
  });
  return { id };
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
