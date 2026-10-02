import type { Metadata } from "next";
import { defaultLocale, isLocale, localePath, locales } from "@/lib/locales";

export const siteOrigin = "https://innolive.studio";

export const sitemapPaths = ["/", "/pricing", "/try-out", "/support", "/privacy", "/terms"] as const;

export function pageAlternates(locale: string, path: string) {
  const resolvedLocale = isLocale(locale) ? locale : defaultLocale;
  return {
    canonical: `${siteOrigin}${localePath(resolvedLocale, path)}`,
    languages: Object.fromEntries([
      ...locales.map((language) => [language, `${siteOrigin}${localePath(language, path)}`]),
      ["x-default", `${siteOrigin}${localePath(defaultLocale, path)}`],
    ]),
  };
}

export function pageMetadata(
  locale: string,
  path: string,
  copy: { title: string; description: string },
): Metadata {
  const resolvedLocale = isLocale(locale) ? locale : defaultLocale;
  const alternates = pageAlternates(resolvedLocale, path);
  const ogLocales = { ko: "ko_KR", en: "en_US", ja: "ja_JP" } as const;
  return {
    ...copy,
    alternates,
    openGraph: {
      ...copy,
      type: "website",
      siteName: "InnoLive",
      url: alternates.canonical,
      locale: ogLocales[resolvedLocale],
      alternateLocale: locales.filter((language) => language !== resolvedLocale).map((language) => ogLocales[language]),
    },
    twitter: { card: "summary", ...copy },
  };
}
