// Dev-only mock: list and create VOD jobs. Unreachable in production (NODE_ENV guard in
// isVodMockEnabled -> mockDisabled). See apps/landing/README.md.
import {
  createMockJob,
  hasLandingSession,
  listMockJobs,
  mockDisabled,
  notFoundResponse,
  unauthorizedResponse,
} from "@/lib/vod-mock-store";

export const dynamic = "force-dynamic";

const jsonHeaders = { "Content-Type": "application/json", "Cache-Control": "no-store" };

export async function GET(request: Request) {
  if (mockDisabled()) return notFoundResponse();
  if (!(await hasLandingSession())) return unauthorizedResponse();
  return new Response(JSON.stringify({ jobs: listMockJobs(request.url) }), { headers: jsonHeaders });
}

export async function POST(request: Request) {
  if (mockDisabled()) return notFoundResponse();
  if (!(await hasLandingSession())) return unauthorizedResponse();

  const form = await request.formData().catch(() => null);
  const file = form?.get("file");
  if (!(file instanceof File)) {
    return new Response(JSON.stringify({ error: { code: "bad_request", message: "file is required" } }), {
      status: 400,
      headers: jsonHeaders,
    });
  }
  const job = await createMockJob(file);
  // 201 returns the created job (contract) with a Location header, like the real server.
  return new Response(JSON.stringify(job), {
    status: 201,
    headers: { ...jsonHeaders, Location: `/vod/jobs/${job.job_id}` },
  });
}
