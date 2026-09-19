import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { getInnoLiveServerUrl } from "@/lib/auth-config";

export const ACCESS_TOKEN_COOKIE = "accessToken";
export const REFRESH_TOKEN_COOKIE = "refreshToken";
const SIGNUP_TOKEN_COOKIE = "signupToken";

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

export async function readSignupToken() {
  return (await cookies()).get(SIGNUP_TOKEN_COOKIE)?.value;
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

export function setSignupTokenCookie(response: NextResponse, token: string) {
  response.cookies.set({
    name: SIGNUP_TOKEN_COOKIE,
    value: token,
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: 5 * 60,
  });
}

export function clearSignupTokenCookie(response: NextResponse) {
  response.cookies.set({
    name: SIGNUP_TOKEN_COOKIE,
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

type AuthErrorBody = {
  error?: {
    code?: unknown;
    message?: unknown;
  };
};

export async function postToAuth(path: string, body: unknown) {
  try {
    const response = await fetch(`${getInnoLiveServerUrl()}${path}`, {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const payload: unknown = await response.json().catch(() => null);
    return { response, payload };
  } catch {
    return { response: null, payload: null };
  }
}

export function authErrorResponse(response: Response | null, payload: unknown) {
  const body = payload as AuthErrorBody | null;
  const code = typeof body?.error?.code === "string" ? body.error.code : "auth_unavailable";
  const message = typeof body?.error?.message === "string" ? body.error.message : "인증 서버에 연결하지 못했습니다.";
  return noStore(NextResponse.json({ error: { code, message } }, { status: response?.status ?? 503 }));
}

export async function refreshSession(refreshToken: string) {
  const { response, payload } = await postToAuth("/auth/refresh", {
    refresh_token: refreshToken,
  });
  return response?.ok && isTokenPair(payload) ? payload : null;
}

export async function logoutSession(refreshToken: string) {
  await postToAuth("/auth/logout", { refresh_token: refreshToken });
}
