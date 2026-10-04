import { parseQualityEvent } from "../../../lib/experience-quality.ts";

export const dynamic = "force-dynamic";
const headers = { "Cache-Control": "no-store" };
const maxBodyBytes = 1_024;

export async function POST(request: Request) {
  const origin = request.headers.get("origin");
  if (!origin || origin !== new URL(request.url).origin || request.headers.get("sec-fetch-site") === "cross-site") {
    return new Response(null, { status: 403, headers });
  }
  if (request.headers.get("content-type")?.split(";")[0].trim() !== "application/json") {
    return new Response(null, { status: 415, headers });
  }
  if (Number(request.headers.get("content-length")) > maxBodyBytes) {
    return new Response(null, { status: 413, headers });
  }
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
    console.info(JSON.stringify({ kind: "experience_quality", receivedAt: new Date().toISOString(), ...event }));
    return new Response(null, { status: 204, headers });
  } catch {
    return new Response(null, { status: 400, headers });
  } finally {
    reader.releaseLock();
  }
}
