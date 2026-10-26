// Dev-only mock: download a finished job's result. Returns the uploaded bytes back so the
// end-to-end flow has a real file to download. Unreachable in production.
import {
  getMockResult,
  hasLandingSession,
  mockDisabled,
  notFoundResponse,
  unauthorizedResponse,
} from "@/lib/vod-mock-store";

export const dynamic = "force-dynamic";

export async function GET(request: Request, { params }: { params: Promise<{ id: string }> }) {
  if (mockDisabled()) return notFoundResponse();
  if (!(await hasLandingSession())) return unauthorizedResponse();
  const { id } = await params;
  const result = getMockResult(request.url, id);
  if (!result) {
    // Either unknown id or the job is not finished yet.
    return new Response(JSON.stringify({ error: "result_not_ready" }), {
      status: 409,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  }
  const { record } = result;
  const downloadName = record.fileName.replace(/(\.[^.]+)?$/, "-innolive$1");
  const hasBytes = record.bytes.byteLength > 0;
  const body = hasBytes ? record.bytes : new TextEncoder().encode("InnoLive mock result\n");
  // Uint8Array is a valid runtime BodyInit; the cast sidesteps the ArrayBufferLike/ArrayBuffer
  // generic mismatch in the DOM lib types.
  return new Response(body as unknown as BodyInit, {
    headers: {
      "Content-Type": hasBytes ? record.contentType : "text/plain; charset=utf-8",
      "Content-Disposition": `attachment; filename="${downloadName}"`,
      "Cache-Control": "no-store",
    },
  });
}
