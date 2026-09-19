"use client";

import Link from "next/link";
import { localeLabels, locales } from "@/lib/locales";
import { useLocale } from "@/components/locale-provider";
import { cn } from "@/lib/cn";

export function LanguageSwitch({ className }: { className?: string }) {
  const { locale, messages, localizePath } = useLocale();

  return (
    <nav
      aria-label={messages.common.languageNav}
      className={cn("flex flex-wrap items-center gap-2", className)}
    >
      {locales.map((code) => {
        const current = code === locale;
        return (
          <Link
            key={code}
            href={localizePath(code)}
            hrefLang={code}
            lang={code}
            aria-current={current ? "page" : undefined}
            aria-label={localeLabels[code]}
            className={
              current
                ? "text-base font-semibold leading-none text-text-primary md:text-lg"
                : "text-base leading-none text-text-secondary hover:underline md:text-lg"
            }
          >
            {code.toUpperCase()}
          </Link>
        );
      })}
    </nav>
  );
}
