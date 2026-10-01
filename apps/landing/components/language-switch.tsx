"use client";

import { useRouter } from "next/navigation";
import { isLocale, localeLabels, locales, switchLocalePath } from "@/lib/locales";
import { useLocale } from "@/components/locale-provider";
import { cn } from "@/lib/cn";

export function LanguageSwitch({ className }: { className?: string }) {
  const { locale, messages } = useLocale();
  const router = useRouter();

  return (
    <div className={cn("relative inline-flex items-center", className)}>
      <select
        aria-label={messages.common.languageNav}
        value={locale}
        onChange={(event) => {
          const next = event.target.value;
          if (!isLocale(next) || next === locale) return;
          document.cookie = `NEXT_LOCALE=${next}; Path=/; SameSite=Lax`;
          router.push(
            switchLocalePath(window.location.pathname, next, window.location.search, window.location.hash),
            { scroll: false },
          );
        }}
        className="cursor-pointer appearance-none rounded-full border border-surface-primary bg-transparent py-2 pl-3 pr-8 text-base font-semibold leading-none text-text-primary transition-colors hover:bg-background-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-text-primary md:text-lg"
      >
        {locales.map((code) => (
          <option key={code} value={code} lang={code} aria-label={localeLabels[code]}>
            {code.toUpperCase()}
          </option>
        ))}
      </select>
      <svg aria-hidden="true" viewBox="0 0 12 8" className="pointer-events-none absolute right-3 h-2 w-3" fill="none">
        <path d="m1 1 5 5 5-5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </div>
  );
}
