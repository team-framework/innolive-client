"use client";

import { usePathname } from "next/navigation";
import { useEffect, useLayoutEffect } from "react";

export function RouteScrollReset() {
  const pathname = usePathname();

  useLayoutEffect(() => {
    const restoration = window.history.scrollRestoration;
    window.history.scrollRestoration = "manual";
    return () => {
      window.history.scrollRestoration = restoration;
    };
  }, []);

  useEffect(() => {
    // Hash links keep their target; ordinary page changes start at the top.
    if (window.location.hash) return;

    const reset = () => window.scrollTo({ top: 0, left: 0, behavior: "instant" });
    reset();
    // Run after the outgoing page releases its scroll animation and layout.
    const frame = window.requestAnimationFrame(reset);
    return () => window.cancelAnimationFrame(frame);
  }, [pathname]);

  return null;
}
