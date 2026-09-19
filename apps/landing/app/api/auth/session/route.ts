import { NextResponse } from "next/server";
import {
  accessTokenIsFresh,
  clearSessionCookies,
  isTokenPair,
  noStore,
  readAuthCookies,
  refreshSession,
  setSessionCookies,
} from "@/lib/auth-server";

export async function GET() {
  const { access, refresh } = await readAuthCookies();
  if (accessTokenIsFresh(access)) {
    return noStore(NextResponse.json({ authenticated: true }));
  }

  if (!refresh) {
    const response = NextResponse.json({ authenticated: false });
    clearSessionCookies(response);
    return noStore(response);
  }

  const pair = await refreshSession(refresh);
  if (!pair) {
    const response = NextResponse.json({ authenticated: false });
    clearSessionCookies(response);
    return noStore(response);
  }

  const response = NextResponse.json({ authenticated: true });
  setSessionCookies(response, pair);
  return noStore(response);
}

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return noStore(NextResponse.json({ error: "invalid_session" }, { status: 400 }));
  }
  if (!isTokenPair(body)) {
    return noStore(NextResponse.json({ error: "invalid_session" }, { status: 400 }));
  }

  const response = NextResponse.json({ authenticated: true });
  setSessionCookies(response, body);
  return noStore(response);
}
