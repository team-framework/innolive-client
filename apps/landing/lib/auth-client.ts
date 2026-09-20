import { getInnoLiveServerUrl } from "@/lib/auth-config";

type ServerErrorBody = {
  error?: {
    code?: unknown;
    message?: unknown;
  };
};

export class AuthRequestError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status = 0) {
    super(message);
    this.name = "AuthRequestError";
    this.code = code;
    this.status = status;
  }
}

export const AUTH_STATE_CHANGE_EVENT = "innolive-auth-state-change";

function notifyAuthStateChanged() {
  window.dispatchEvent(new Event(AUTH_STATE_CHANGE_EVENT));
}

async function request(url: string, body: unknown, credentials: RequestCredentials) {
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      cache: "no-store",
      credentials,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw new AuthRequestError(
      "network_error",
      "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    );
  }

  if (!response.ok) {
    let payload: ServerErrorBody = {};
    try {
      payload = (await response.json()) as ServerErrorBody;
    } catch {
      // Status-based fallback is used when no error JSON was returned.
    }
    const code = typeof payload.error?.code === "string" ? payload.error.code : "server_error";
    const message =
      typeof payload.error?.message === "string"
        ? payload.error.message
        : "인증 요청을 처리하지 못했습니다.";
    throw new AuthRequestError(code, message, response.status);
  }

  return response;
}

async function requestAuthServer(path: string, body: unknown) {
  let url: string;
  try {
    url = `${getInnoLiveServerUrl()}${path}`;
  } catch (error) {
    throw new AuthRequestError(
      "configuration_error",
      error instanceof Error ? error.message : "서버 주소가 설정되지 않았습니다.",
    );
  }

  return request(url, body, "omit");
}

async function requestLandingAuth(path: string, body: unknown) {
  return request(path, body, "include");
}

export async function startSignup(email: string, password: string) {
  const response = await requestAuthServer("/auth/native/sign-up", { email, password });
  const payload = (await response.json()) as { signup_token?: unknown };
  if (typeof payload.signup_token !== "string" || !payload.signup_token) {
    throw new AuthRequestError(
      "invalid_signup_token",
      "회원가입 인증 정보를 받지 못했습니다.",
    );
  }
  await requestLandingAuth("/api/auth/signup", { signup_token: payload.signup_token });
}

export async function completeSignup(verificationCode: string) {
  await requestLandingAuth("/api/auth/verify-email", {
    verification_code: verificationCode,
  });
}

export async function signIn(email: string, password: string) {
  await requestLandingAuth("/api/auth/login", { email, password });
  notifyAuthStateChanged();
}

export async function signOut() {
  await requestLandingAuth("/api/auth/logout", {});
  notifyAuthStateChanged();
}

export function authErrorMessage(
  error: unknown,
  copy: { fallback: string } & Record<string, string | undefined>,
) {
  if (!(error instanceof AuthRequestError)) {
    return copy.fallback;
  }

  return copy[error.code] ?? copy.fallback;
}
