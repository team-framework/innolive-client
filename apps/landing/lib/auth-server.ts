import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { getInnoLiveServerUrl } from "@/lib/auth-config";

export const ACCESS_TOKEN_COOKIE = "innolive_access_token";
export const REFRESH_TOKEN_COOKIE = "innolive_refresh_token";

export type TokenPair = {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  refresh_expires_in: number;
};

export async function readAuthCookies() {
  const store = await cookies();
  return {
    access: store.get(ACCESS_TOKEN_COOKIE)?.value,
    refresh: store.get(REFRESH_TOKEN_COOKIE)?.value,
  };
}

export function isTokenPair(value: unknown): value is TokenPair {
  if (!value || typeof value !== "object") return false;
  const pair = value as Partial<TokenPair>;
  return (
    typeof pair.access_token === "string" &&
    pair.access_token.length > 0 &&
    typeof pair.refresh_token === "string" &&
    pair.refresh_token.length > 0 &&
    typeof pair.token_type === "string" &&
    typeof pair.expires_in === "number" &&
    pair.expires_in > 0 &&
    typeof pair.refresh_expires_in === "number" &&
    pair.refresh_expires_in > 0
  );
}

export function accessTokenIsFresh(token: string | undefined) {
  if (!token) return false;
  try {
    const payload = token.split(".")[1];
    if (!payload) return false;
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as {
      exp?: unknown;
    };
    return typeof claims.exp === "number" && claims.exp > Math.floor(Date.now() / 1000) + 5;
  } catch {
    return false;
  }
}

export function setSessionCookies(response: NextResponse, pair: TokenPair) {
  const secure = process.env.NODE_ENV === "production";
  response.cookies.set({
    name: ACCESS_TOKEN_COOKIE,
    value: pair.access_token,
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    maxAge: pair.expires_in,
  });
  response.cookies.set({
    name: REFRESH_TOKEN_COOKIE,
    value: pair.refresh_token,
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    maxAge: pair.refresh_expires_in,
  });
}

export function clearSessionCookies(response: NextResponse) {
  response.cookies.set({
    name: ACCESS_TOKEN_COOKIE,
    value: "",
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: 0,
  });
  response.cookies.set({
    name: REFRESH_TOKEN_COOKIE,
    value: "",
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: 0,
  });
}

export function noStore(response: NextResponse) {
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Pragma", "no-cache");
  return response;
}

export async function refreshSession(refreshToken: string) {
  let response: Response;
  try {
    response = await fetch(`${getInnoLiveServerUrl()}/auth/refresh`, {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken }),
    });
  } catch {
    return null;
  }
  if (!response.ok) return null;
  try {
    const pair: unknown = await response.json();
    return isTokenPair(pair) ? pair : null;
  } catch {
    return null;
  }
}

export async function logoutSession(refreshToken: string) {
  try {
    await fetch(`${getInnoLiveServerUrl()}/auth/logout`, {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken }),
    });
  } catch {
    // Local session cookies are cleared even when the upstream is unavailable.
  }
}
