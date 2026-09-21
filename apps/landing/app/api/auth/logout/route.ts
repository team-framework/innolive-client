import { NextResponse } from "next/server";
import {
  clearSessionCookies,
  logoutSession,
  noStore,
  readAuthCookies,
} from "@/lib/auth-server";

export async function POST() {
  const { refresh } = await readAuthCookies();
  if (refresh) await logoutSession(refresh);

  const response = new NextResponse(null, { status: 204 });
  clearSessionCookies(response);
  return noStore(response);
}
