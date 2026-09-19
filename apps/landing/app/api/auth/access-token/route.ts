import { NextResponse } from "next/server";
import {
  accessTokenIsFresh,
  clearSessionCookies,
  noStore,
  readAuthCookies,
  refreshSession,
  setSessionCookies,
} from "@/lib/auth-server";

export async function POST() {
  const { access, refresh } = await readAuthCookies();
  if (accessTokenIsFresh(access)) {
    return noStore(NextResponse.json({ access_token: access }));
  }

  if (!refresh) {
    const response = NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    clearSessionCookies(response);
    return noStore(response);
  }

  const pair = await refreshSession(refresh);
  if (!pair) {
    const response = NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    clearSessionCookies(response);
    return noStore(response);
  }

  const response = NextResponse.json({ access_token: pair.access_token });
  setSessionCookies(response, pair);
  return noStore(response);
}
