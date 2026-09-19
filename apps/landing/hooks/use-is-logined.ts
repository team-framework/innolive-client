"use client";

import { useCallback, useEffect, useState } from "react";
import { usePathname } from "next/navigation";

export function useIsLogined() {
  const pathname = usePathname();
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
    void loadSession().then((authenticated) => {
      if (active) setIsLogined(authenticated);
    });
    return () => {
      active = false;
    };
  }, [loadSession, pathname]);

  const refresh = useCallback(async () => {
    setIsLogined(await loadSession());
  }, [loadSession]);

  return {
    isLogined: isLogined === true,
    isLoading: isLogined === null,
    refresh,
  };
}
