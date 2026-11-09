"use client";

import { useCallback, useEffect, useState } from "react";
import { AUTH_STATE_CHANGE_EVENT } from "@/lib/auth-client";
import { loadAuthSession } from "@/lib/auth-session-client";

export function useIsLogined() {
  const [isLogined, setIsLogined] = useState<boolean | null>(null);

  useEffect(() => {
    let active = true;
    const refresh = () => {
      void loadAuthSession().then((authenticated) => {
        if (active) setIsLogined(authenticated);
      });
    };
    refresh();
    window.addEventListener(AUTH_STATE_CHANGE_EVENT, refresh);
    return () => {
      active = false;
      window.removeEventListener(AUTH_STATE_CHANGE_EVENT, refresh);
    };
  }, []);

  const refresh = useCallback(async () => {
    setIsLogined(await loadAuthSession());
  }, []);

  return {
    isLogined: isLogined === true,
    isLoading: isLogined === null,
    refresh,
  };
}
