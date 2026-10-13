"use client";
import { useEffect, useRef } from "react";
import { usePathname } from "next/navigation";
import { useLocale } from "@/components/locale-provider";
import { trackConversion, type ConversionEventName } from "@/lib/conversion-analytics";
export function ConversionTracker() {
  const pathname = usePathname();
  const { locale } = useLocale();
  const last = useRef<string | null>(null);
  useEffect(() => {
    if (last.current === pathname) return;
    last.current = pathname;
    const route = pathname.replace(/^\/(ko|en|ja)/, "") || "/";
    const event = ({ "/": "landing_viewed", "/pricing": "pricing_viewed", "/try-out": "tryout_viewed", "/signup": "signup_viewed" } as Record<string, ConversionEventName>)[route];
    if (event) trackConversion(event, locale);
  }, [pathname, locale]);
  return null;
}
