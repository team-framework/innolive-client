// Dev-only mock: read and delete a single VOD job. Unreachable in production.
import {
  deleteMockJob,
  getMockJob,
  hasLandingSession,
  mockDisabled,
  notFoundResponse,
  unauthorizedResponse,
} from "@/lib/vod-mock-store";

export const dynamic = "force-dynamic";

const jsonHeaders = { "Content-Type": "application/json", "Cache-Control": "no-store" };

export async function GET(request: Request, { params }: { params: Promise<{ id: string }> }) {
  if (mockDisabled()) return notFoundResponse();
  if (!(await hasLandingSession())) return unauthorizedResponse();
  const { id } = await params;
  const job = getMockJob(request.url, id);
  if (!job) return notFoundResponse();
  return new Response(JSON.stringify(job), { headers: jsonHeaders });
}

export async function DELETE(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  if (mockDisabled()) return notFoundResponse();
  if (!(await hasLandingSession())) return unauthorizedResponse();
  const { id } = await params;
  if (!deleteMockJob(id)) return notFoundResponse();
  return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
}
