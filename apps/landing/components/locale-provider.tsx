"use client";

import { usePathname } from "next/navigation";
import {
  createContext,
  useContext,
  type ReactNode,
} from "react";
import {
  localePath,
  switchLocalePath,
  type Locale,
} from "@/lib/locales";
import type { Messages } from "@/lib/messages";

type LocaleContextValue = {
  locale: Locale;
  messages: Messages;
  href: (path: string) => string;
  localizePath: (next: Locale) => string;
};

const LocaleContext = createContext<LocaleContextValue | null>(null);

export function LocaleProvider({
  locale,
  messages,
  children,
}: {
  locale: Locale;
  messages: Messages;
  children: ReactNode;
}) {
  const pathname = usePathname();
  const value: LocaleContextValue = {
    locale,
    messages,
    href: (path) => localePath(locale, path),
    localizePath: (next) => switchLocalePath(pathname, next),
  };

  return (
    <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>
  );
}

export function useLocale() {
  const context = useContext(LocaleContext);
  if (!context) {
    throw new Error("useLocale must be used within LocaleProvider");
  }
  return context;
}
