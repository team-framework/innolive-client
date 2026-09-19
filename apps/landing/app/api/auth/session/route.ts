import { NextResponse } from "next/server";
import {
  accessTokenIsFresh,
  clearSessionCookies,
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
