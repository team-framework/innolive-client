import { NextResponse } from "next/server";
import {
  authErrorResponse,
  clearSignupTokenCookie,
  noStore,
  postToAuth,
  readSignupToken,
} from "@/lib/auth-server";

const verificationCodePattern = /^\d{6}$/;

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return noStore(
      NextResponse.json(
        { error: { code: "invalid_verification_code", message: "6자리 인증 코드를 확인해 주세요." } },
        { status: 400 },
      ),
    );
  }
  const verificationCode =
    body && typeof body === "object"
      ? (body as { verification_code?: unknown }).verification_code
      : undefined;
  if (
    typeof verificationCode !== "string" ||
    !verificationCodePattern.test(verificationCode)
  ) {
    return noStore(
      NextResponse.json(
        { error: { code: "invalid_verification_code", message: "6자리 인증 코드를 확인해 주세요." } },
        { status: 400 },
      ),
    );
  }

  const signupToken = await readSignupToken();
  if (!signupToken) {
    return noStore(
      NextResponse.json(
        { error: { code: "invalid_signup_token", message: "회원가입 인증 시간이 만료됐습니다." } },
        { status: 400 },
      ),
    );
  }

  const { response, payload } = await postToAuth("/auth/native/verify-email", {
    signup_token: signupToken,
    verification_code: verificationCode,
  });
  if (!response?.ok) return authErrorResponse(response, payload);

  const result = NextResponse.json({ email_verified: true });
  clearSignupTokenCookie(result);
  return noStore(result);
}
