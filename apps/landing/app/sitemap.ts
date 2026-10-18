import type { MetadataRoute } from "next";
import { seoGuides } from "@/lib/seo-guides";
import { seoGuideLocales } from "@/lib/seo-guide-paths";
import { locales } from "@/lib/locales";
import { pageAlternates, sitemapPaths } from "@/lib/seo";

export default function sitemap(): MetadataRoute.Sitemap {
  const localizedPages = sitemapPaths.flatMap((path) => locales.map((locale) => {
    const alternates = pageAlternates(locale, path);
    return {
      url: alternates.canonical,
      alternates: { languages: alternates.languages },
    };
  }));
  return [...localizedPages, ...seoGuides.flatMap(({ path, comparison }) => seoGuideLocales.map((locale) => {
    const alternates = pageAlternates(locale, path);
    return { url: alternates.canonical, alternates: { languages: alternates.languages }, ...(comparison ? { lastModified: comparison.checkedAt } : {}) };
  }))];
}
