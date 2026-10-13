import { parseQualityEvent } from "../../../lib/experience-quality.ts";

export const dynamic = "force-dynamic";
const headers = { "Cache-Control": "no-store" };
const maxBodyBytes = 1_024;
let windowStarted = 0;
let windowCount = 0;
function allowCollection() {
  const now = Date.now();
  if (now < windowStarted || now - windowStarted >= 60_000) { windowStarted = now; windowCount = 0; }
  if (windowCount >= 600) return false;
  windowCount++;
  return true;
}

async function persistEvent(event: NonNullable<ReturnType<typeof parseQualityEvent>>) {
  const base = process.env.EXPERIENCE_QUALITY_SERVER_URL?.trim();
  const key = process.env.EXPERIENCE_QUALITY_INGEST_KEY?.trim();
  if (!base || !key || key.length < 32) return false;
  try {
    const url = new URL("/experience-quality", base);
    if (url.username || url.password || (url.protocol !== "https:" &&
      !(url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)))) return false;
    const revision = process.env.INNOLIVE_WEB_REVISION?.trim() ?? "";
    const release = /^[a-zA-Z0-9._-]{1,64}$/.test(revision) ? revision : "unknown";
    const response = await fetch(url, {
      method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
      body: JSON.stringify({ ...event, release }), credentials: "omit", cache: "no-store",
      redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    return response.status === 204;
  } catch {
    return false;
  }
}

export async function POST(request: Request) {
  const origin = request.headers.get("origin");
  const requestURL = new URL(request.url);
  const host = request.headers.get("host") ?? requestURL.host;
  const protocol = request.headers.get("x-forwarded-proto") ?? requestURL.protocol.slice(0, -1);
  // Next's internal request URL may use the container hostname behind a proxy.
  if (!origin || origin !== `${protocol}://${host}` || request.headers.get("sec-fetch-site") === "cross-site") {
    return new Response(null, { status: 403, headers });
  }
  if (request.headers.get("content-type")?.split(";")[0].trim() !== "application/json") {
    return new Response(null, { status: 415, headers });
  }
  if (Number(request.headers.get("content-length")) > maxBodyBytes) {
    return new Response(null, { status: 413, headers });
  }
  if (!allowCollection()) return new Response(null, { status: 429, headers: { ...headers, "Retry-After": "60" } });
  const reader = request.body?.getReader();
  if (!reader) return new Response(null, { status: 400, headers });
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > maxBodyBytes) {
        await reader.cancel();
        return new Response(null, { status: 413, headers });
      }
      chunks.push(value);
    }
    const body = new Uint8Array(bytes);
    let offset = 0;
    for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
    const event = parseQualityEvent(JSON.parse(new TextDecoder().decode(body)));
    if (!event) return new Response(null, { status: 400, headers });
    if (!await persistEvent(event)) {
      console.warn(JSON.stringify({ kind: "experience_quality_storage_unavailable" }));
      return new Response(null, { status: 503, headers });
    }
    return new Response(null, { status: 204, headers });
  } catch {
    return new Response(null, { status: 400, headers });
  } finally {
    reader.releaseLock();
  }
}
