let inFlight: Promise<boolean> | null = null;

// Every concurrent caller shares one request: the server revokes the whole refresh
// family when one refresh token is rotated twice, so parallel checks log the user out.
export function loadAuthSession() {
  if (inFlight) return inFlight;
  const request = requestAuthSession().finally(() => {
    if (inFlight === request) inFlight = null;
  });
  inFlight = request;
  return request;
}

export function discardPendingAuthSession() {
  inFlight = null;
}

async function requestAuthSession() {
  try {
    const response = await fetch("/api/auth/session", {
      cache: "no-store",
      credentials: "include",
    });
    if (!response.ok) {
      return false;
    }
    const body = (await response.json()) as { authenticated?: unknown };
    return body.authenticated === true;
  } catch {
    return false;
  }
}
