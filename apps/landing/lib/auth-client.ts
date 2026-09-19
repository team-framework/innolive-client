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

export async function requestAuth(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  let url: string;
  try {
    url = `${getInnoLiveServerUrl()}${path}`;
  } catch (error) {
    throw new AuthRequestError(
      "configuration_error",
      error instanceof Error ? error.message : "서버 주소가 설정되지 않았습니다.",
    );
  }

  let response: Response;
  try {
    response = await fetch(url, {
      ...init,
      cache: "no-store",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        ...init.headers,
      },
    });
  } catch {
    throw new AuthRequestError(
      "network_error",
      "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    );
  }

  if (!response.ok) {
    let body: ServerErrorBody = {};
    try {
      body = (await response.json()) as ServerErrorBody;
    } catch {
      // Keep the status-based fallback below when the server returned no JSON.
    }
    const code = typeof body.error?.code === "string" ? body.error.code : "server_error";
    const message =
      typeof body.error?.message === "string"
        ? body.error.message
        : "인증 요청을 처리하지 못했습니다.";
    throw new AuthRequestError(code, message, response.status);
  }

  return response;
}

export async function saveSession(pair: unknown) {
  const response = await fetch("/api/auth/session", {
    method: "POST",
    cache: "no-store",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(pair),
  });
  if (!response.ok) {
    throw new AuthRequestError(
      "session_storage_error",
      "로그인 세션을 저장하지 못했습니다.",
      response.status,
    );
  }
}

export function authErrorMessage(error: unknown) {
  if (!(error instanceof AuthRequestError)) {
    return "인증 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  const messages: Record<string, string> = {
    configuration_error: "서버 주소가 설정되지 않았습니다.",
    network_error: "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    email_already_registered: "이미 가입된 이메일입니다.",
    email_delivery_unavailable: "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요.",
    email_delivery_failed: "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.",
    email_auth_unavailable: "이메일 인증을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
    invalid_signup_token: "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요.",
    invalid_verification_code: "인증 코드가 올바르지 않거나 만료됐습니다.",
    invalid_email_credentials: "이메일 또는 비밀번호가 올바르지 않습니다.",
    too_many_signup_requests: "가입 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
    too_many_requests: "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
    origin_not_allowed: "현재 주소에서는 인증을 사용할 수 없습니다.",
    session_storage_error: "로그인 세션을 저장하지 못했습니다.",
  };
  return messages[error.code] ?? error.message;
}
