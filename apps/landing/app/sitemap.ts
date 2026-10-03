import type { MetadataRoute } from "next";
import { seoGuides } from "@/lib/seo-guides";
import { siteOrigin } from "@/lib/seo";
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
  return [...localizedPages, ...seoGuides.map(({ path }) => ({ url: `${siteOrigin}/ko${path}` }))];
}
