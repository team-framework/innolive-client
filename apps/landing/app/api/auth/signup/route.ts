import { NextResponse } from "next/server";
import { noStore, setSignupTokenCookie } from "@/lib/auth-server";

const signupTokenPattern = /^[a-f0-9]{64}$/i;

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return noStore(
      NextResponse.json(
        { error: { code: "invalid_signup_token", message: "회원가입 인증 정보가 올바르지 않습니다." } },
        { status: 400 },
      ),
    );
  }

  const signupToken =
    body && typeof body === "object"
      ? (body as { signup_token?: unknown }).signup_token
      : undefined;
  if (typeof signupToken !== "string" || !signupTokenPattern.test(signupToken)) {
    return noStore(
      NextResponse.json(
        { error: { code: "invalid_signup_token", message: "회원가입 인증 정보가 올바르지 않습니다." } },
        { status: 400 },
      ),
    );
  }

  const response = NextResponse.json({ verification_required: true });
  setSignupTokenCookie(response, signupToken);
  return noStore(response);
}
