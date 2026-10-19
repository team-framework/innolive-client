import { isSeoGuidePath, seoGuideLocales } from "@/lib/seo-guide-paths";
import type { Metadata } from "next";
import { defaultLocale, isLocale, localePath, locales } from "@/lib/locales";

export const siteOrigin = "https://innolive.studio";

export const sitemapPaths = ["/", "/about", "/pricing", "/try-out", "/support", "/privacy", "/terms", "/guides/live-face-blur"] as const;

export function pageAlternates(locale: string, path: string) {
  const resolvedLocale = isLocale(locale) ? locale : defaultLocale;
  return {
    canonical: `${siteOrigin}${localePath(resolvedLocale, path)}`,
    types: { "application/rss+xml": `${siteOrigin}/feed.xml` },
    languages: Object.fromEntries([
      ...(isSeoGuidePath(path) ? seoGuideLocales : locales).map((language) => [language, `${siteOrigin}${localePath(language, path)}`]),
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
    ...(["/login", "/signup", "/try-out/experience"].includes(path) ? { robots: { index: false, follow: true } } : {}),
    alternates,
    openGraph: {
      ...copy,
      type: "website",
      siteName: "InnoLive",
      url: alternates.canonical,
      locale: ogLocales[resolvedLocale],
      alternateLocale: (isSeoGuidePath(path) ? seoGuideLocales : locales).filter((language) => language !== resolvedLocale).map((language) => ogLocales[language]),
      images: [{
        url: `${siteOrigin}/brand/opengraph.png`,
        width: 1200,
        height: 630,
        type: "image/png",
        alt: "InnoLive",
      }],
    },
    twitter: {
      card: "summary_large_image",
      ...copy,
      images: [{ url: `${siteOrigin}/brand/opengraph.png`, alt: "InnoLive" }],
    },
  };
}
