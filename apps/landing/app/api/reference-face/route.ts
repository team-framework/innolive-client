import { NextResponse } from "next/server";
import {
  accessTokenIsFresh,
  clearSessionCookies,
  noStore,
  readAuthCookies,
  refreshSession,
  setSessionCookies,
  type TokenPair,
} from "@/lib/auth-server";
import { getInnoLiveServerUrl } from "@/lib/auth-config";

const MAX_FILE_SIZE = 10 * 1024 * 1024;
const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

function errorResponse(
  message: string,
  status: number,
  clearCookies = false,
  session?: TokenPair,
) {
  const response = NextResponse.json({ error: message }, { status });
  if (clearCookies) {
    clearSessionCookies(response);
  } else if (session) {
    setSessionCookies(response, session);
  }
  return noStore(response);
}

async function upstreamResponse(
  response: Response,
  session?: TokenPair,
  clearCookies = false,
) {
  let body: string;
  try {
    body = await response.text();
  } catch {
    return errorResponse("invalid upstream response", 502, clearCookies, session);
  }

  let result: NextResponse;
  if (!body || [204, 205, 304].includes(response.status)) {
    result = new NextResponse(null, {
      status: response.status,
      headers: { "Cache-Control": "no-store" },
    });
  } else {
    try {
      result = NextResponse.json(JSON.parse(body), {
        status: response.status,
        headers: { "Cache-Control": "no-store" },
      });
    } catch {
      result = new NextResponse(body, {
        status: response.status,
        headers: {
          "Cache-Control": "no-store",
          "Content-Type": "text/plain; charset=utf-8",
        },
      });
    }
  }

  if (clearCookies) {
    clearSessionCookies(result);
  } else if (session) {
    setSessionCookies(result, session);
  }
  return noStore(result);
}

async function postReferenceFace(file: File, token: string) {
  const body = new FormData();
  body.append("image", file, "reference-face.jpg");
  return fetch(`${getInnoLiveServerUrl()}/reference-face`, {
    method: "POST",
    cache: "no-store",
    headers: { Authorization: `Bearer ${token}` },
    body,
  });
}

export async function POST(request: Request) {
  const { access, refresh } = await readAuthCookies();
  let token = access;
  let session: TokenPair | undefined;
  let refreshed = false;

  if (!accessTokenIsFresh(token)) {
    if (!refresh) return errorResponse("unauthenticated", 401, true);
    session = (await refreshSession(refresh)) ?? undefined;
    refreshed = true;
    if (!session) return errorResponse("unauthenticated", 401, true);
    token = session.access_token;
  }
  if (!token) return errorResponse("unauthenticated", 401, true);

  let formData: FormData;
  try {
    formData = await request.formData();
  } catch {
    return errorResponse("invalid multipart form data", 400, false, session);
  }

  const entries = [...formData.entries()];
  const imageEntry = entries[0];
  if (entries.length !== 1 || imageEntry?.[0] !== "image" || !(imageEntry[1] instanceof File)) {
    return errorResponse("image file is required", 400, false, session);
  }

  const file = imageEntry[1];
  if (!ALLOWED_TYPES.has(file.type)) {
    return errorResponse("unsupported image type", 400, false, session);
  }
  if (file.size > MAX_FILE_SIZE) {
    return errorResponse("image file is too large", 413, false, session);
  }

  let upstream: Response;
  try {
    upstream = await postReferenceFace(file, token);
  } catch {
    return errorResponse("reference-face service unavailable", 503, false, session);
  }

  if (upstream.status === 401 && !refreshed) {
    refreshed = true;
    if (!refresh) return upstreamResponse(upstream, undefined, true);

    session = (await refreshSession(refresh)) ?? undefined;
    if (!session) return upstreamResponse(upstream, undefined, true);

    try {
      upstream = await postReferenceFace(file, session.access_token);
    } catch {
      return errorResponse("reference-face service unavailable", 503, false, session);
    }
  }

  return upstreamResponse(upstream, session, upstream.status === 401);
}
