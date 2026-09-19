"use client";

import { useCallback, useEffect, useState } from "react";
import { AUTH_STATE_CHANGE_EVENT } from "@/lib/auth-client";

export function useIsLogined() {
  const [isLogined, setIsLogined] = useState<boolean | null>(null);

  const loadSession = useCallback(async () => {
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
  }, []);

  useEffect(() => {
    let active = true;
    const refresh = () => {
      void loadSession().then((authenticated) => {
        if (active) setIsLogined(authenticated);
      });
    };
    refresh();
    window.addEventListener(AUTH_STATE_CHANGE_EVENT, refresh);
    return () => {
      active = false;
      window.removeEventListener(AUTH_STATE_CHANGE_EVENT, refresh);
    };
  }, [loadSession]);

  const refresh = useCallback(async () => {
    setIsLogined(await loadSession());
  }, [loadSession]);

  return {
    isLogined: isLogined === true,
    isLoading: isLogined === null,
    refresh,
  };
}
