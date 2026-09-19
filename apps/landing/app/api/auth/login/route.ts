import { NextResponse } from "next/server";
import {
  authErrorResponse,
  isTokenPair,
  noStore,
  postToAuth,
  setSessionCookies,
} from "@/lib/auth-server";

function invalidRequest() {
  return noStore(
    NextResponse.json(
      { error: { code: "invalid_request", message: "이메일과 비밀번호를 확인해 주세요." } },
      { status: 400 },
    ),
  );
}

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalidRequest();
  }

  if (
    !body ||
    typeof body !== "object" ||
    typeof (body as { email?: unknown }).email !== "string" ||
    typeof (body as { password?: unknown }).password !== "string"
  ) {
    return invalidRequest();
  }

  const { response, payload } = await postToAuth("/auth/sign-in", body);
  if (!response?.ok) return authErrorResponse(response, payload);
  if (!isTokenPair(payload)) {
    return noStore(
      NextResponse.json(
        { error: { code: "invalid_token_response", message: "로그인 정보를 확인하지 못했습니다." } },
        { status: 502 },
      ),
    );
  }

  const result = NextResponse.json({ authenticated: true });
  setSessionCookies(result, payload);
  return noStore(result);
}
